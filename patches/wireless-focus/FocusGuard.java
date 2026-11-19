package hmgapi.mods.wireless;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import hmgapi.hook.Hooks;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * Startup focus guard between Bluetooth Audio ({@code HKMC_BtAudio}) and Phone
 * Projection (CarPlay in {@code HKMC_DiPO}, Android Auto in {@code HKMC_GPM}).
 *
 * Shared across all three processes so CarPlay and Android Auto use one policy
 * and one set of HMG-API hook-point readers:
 *  - {@code com.daudio.av.app.btaudio.ui}: runs {@code HKMC_BtAudio} alone on
 *    Gen5W (FM/AM/DAB radio, SiriusXM, USB music/video, DiPO, and GPM each run
 *    in their own separate processes). Declines media {@code requestAudioFocus}
 *    and self-launches strictly during the projection startup/connect window,
 *    yields boot-raced focus listeners, and executes the one-shot startup
 *    reclaim (deferring while any reverse / rear-view / surround camera is up).
 *  - {@code com.google.android.projection.sink}: observes Android Auto session
 *    start/end and notifies {@code HKMC_BtAudio}.
 *  - {@code com.daudio.app.dipo}: observes CarPlay session start/end (and
 *    wireless SPP connect/abort from {@code Bridge}) and notifies {@code HKMC_BtAudio}.
 */
public final class FocusGuard {
    private FocusGuard() {}

    static final String PROJECTION_FOCUS = "hmgapi.mods.wireless.PROJECTION_FOCUS";
    static final int MAX_CAMERA_RETRIES = 45;
    private static final long BOOT_CATCHUP_UPTIME_MS = 45_000L;
    private static final long LOG_THROTTLE_MS = 2_000L;

    static final FocusPolicy POLICY = new FocusPolicy();
    private static final Object RECLAIM_TOKEN = new Object();
    private static final ThreadLocal<Boolean> IN_ABANDON = new ThreadLocal<Boolean>();
    private static final WeakHashMap<AudioManager.OnAudioFocusChangeListener, AudioManager> btListeners =
            new WeakHashMap<AudioManager.OnAudioFocusChangeListener, AudioManager>();

    private static volatile Handler sMainHandler;
    private static volatile boolean aaSessionUp;
    private static volatile boolean cpSessionUp;
    private static volatile long lastBlockedLogAt = -LOG_THROTTLE_MS;

    /** Cached framework managers/services resolved strictly via HMG-API hook points. */
    private static volatile Object sModeManager;
    private static volatile boolean sModeManagerTried;
    private static volatile Object sModeService;
    private static volatile Object sMicomAudio;
    private static volatile boolean sMicomAudioTried;
    private static volatile Object sPgsService;
    private static volatile Object sGpmManager;
    private static volatile boolean sGpmManagerTried;

    public static boolean active(ModContext mod) {
        return mod != null && mod.enabled() && mod.settingBoolean("projection_focus", true);
    }

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    static Handler mainHandler() {
        Handler h = sMainHandler;
        if (h == null) {
            sMainHandler = h = new Handler(Looper.getMainLooper());
        }
        return h;
    }

    public static void btAudio(final ModContext mod, final LoadedApp app) {
        // Confirm we only hook HKMC_BtAudio's dedicated process (radio, SXM, USB media,
        // DiPO, and GPM each run in their own separate processes on Gen5W).
        if (!FocusPolicy.PKG_BTAUDIO.equals(app.packageName)
                || !FocusPolicy.PKG_BTAUDIO.equals(app.processName)) {
            return;
        }

        MethodHook focusHook = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (!active(mod)) return;
                int stream = (Integer) p.args[1];
                int mode = p.args.length >= 4 ? (Integer) p.args[3] : 0;
                long t = now();
                if (POLICY.shouldBlockBtAudioFocus(stream, mode, t)) {
                    p.setResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
                    if (t - lastBlockedLogAt >= LOG_THROTTLE_MS) {
                        lastBlockedLogAt = t;
                        mod.log("projection focus: declined startup Bluetooth Audio requestAudioFocus (stream="
                                + stream + ", mode=" + mode + ")");
                    }
                }
            }

            @Override
            protected void afterHookedMethod(Param p) {
                if (!active(mod)) return;
                int stream = (Integer) p.args[1];
                if (stream == FocusPolicy.STREAM_VOICE_CALL || stream == FocusPolicy.STREAM_RING) return;
                if (Integer.valueOf(AudioManager.AUDIOFOCUS_REQUEST_GRANTED).equals(p.getResult())
                        && p.args[0] instanceof AudioManager.OnAudioFocusChangeListener
                        && p.thisObject instanceof AudioManager) {
                    synchronized (btListeners) {
                        btListeners.put((AudioManager.OnAudioFocusChangeListener) p.args[0], (AudioManager) p.thisObject);
                    }
                }
            }
        };

        try {
            Hooks.findAndHookMethod(AudioManager.class, "requestAudioFocus",
                    AudioManager.OnAudioFocusChangeListener.class, int.class, int.class, int.class, focusHook);
        } catch (Throwable t) {
            mod.log("projection focus: 4-arg AudioManager.requestAudioFocus unavailable (" + t.getMessage() + ")");
        }
        try {
            Hooks.findAndHookMethod(AudioManager.class, "requestAudioFocus",
                    AudioManager.OnAudioFocusChangeListener.class, int.class, int.class, focusHook);
            Hooks.findAndHookMethod(AudioManager.class, "abandonAudioFocus",
                    AudioManager.OnAudioFocusChangeListener.class, new MethodHook() {
                        @Override
                        protected void beforeHookedMethod(Param p) {
                            if (IN_ABANDON.get() != null) return;
                            if (p.args[0] instanceof AudioManager.OnAudioFocusChangeListener) {
                                synchronized (btListeners) {
                                    btListeners.remove(p.args[0]);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            mod.error("projection focus: AudioManager hooks", t);
        }

        MethodHook startActHook = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (!active(mod)) return;
                Intent intent = p.args.length > 0 && p.args[0] instanceof Intent ? (Intent) p.args[0] : null;
                if (POLICY.shouldBlockBtActivityLaunch(targetPackage(intent), now())) {
                    p.setResult(null);
                    mod.log("projection focus: suppressed startup Bluetooth Audio activity launch");
                }
            }
        };
        try {
            Hooks.findAndHookMethod(ContextWrapper.class, "startActivity", Intent.class, startActHook);
            Hooks.findAndHookMethod(ContextWrapper.class, "startActivity", Intent.class, Bundle.class, startActHook);
            Hooks.findAndHookMethod(Activity.class, "startActivityForResult", Intent.class, int.class, Bundle.class, startActHook);
        } catch (Throwable t) {
            mod.error("projection focus: startActivity hooks", t);
        }

        try {
            Hooks.findAndHookMethod(Activity.class, "onResume", new MethodHook() {
                @Override
                protected void afterHookedMethod(Param p) {
                    if (!active(mod)) return;
                    long t = now();
                    POLICY.onBtScreenOpened(currentMode(app.context), t);
                    if (!POLICY.inStartupWindow(t)) {
                        cancelReclaims();
                    }
                }
            });
        } catch (Throwable t) {
            mod.error("projection focus: Activity.onResume hook", t);
        }

        try {
            app.context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (!active(mod) || i == null) return;
                    int src = FocusPolicy.parseSource(i.getStringExtra("source"));
                    String state = i.getStringExtra("state");
                    boolean yieldNow = POLICY.onProjectionEvent(src, state, now());
                    if (yieldNow) {
                        yieldBtAudioFocus(mod);
                    }
                    if ("connected".equals(state)) {
                        scheduleReclaim(mod, app.context, 400L, 0);
                        scheduleReclaim(mod, app.context, 1800L, 0);
                    } else if ("aborted".equals(state) || "disconnected".equals(state)) {
                        cancelReclaims();
                    }
                }
            }, new IntentFilter(PROJECTION_FOCUS));
        } catch (Throwable t) {
            mod.error("projection focus: receiver in btaudio", t);
        }

        // If HKMC_BtAudio forked slightly after CarPlay/AA already connected during early boot,
        // catch up once so boot-restored Mode 54 still yields to the active startup session.
        if (SystemClock.uptimeMillis() < BOOT_CATCHUP_UPTIME_MS) {
            mainHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!active(mod) || POLICY.inStartupWindow(now())) return;
                    if (currentMode(app.context) != FocusPolicy.MODE_BT_AUDIO) return;
                    int src = isCarPlayConnected(app.context) ? FocusPolicy.SOURCE_CARPLAY
                            : isAndroidAutoConnected(app.context) ? FocusPolicy.SOURCE_ANDROID_AUTO
                            : FocusPolicy.SOURCE_NONE;
                    if (src != FocusPolicy.SOURCE_NONE && POLICY.onProjectionEvent(src, "connected", now())) {
                        yieldBtAudioFocus(mod);
                        scheduleReclaim(mod, app.context, 250L, 0);
                    }
                }
            }, 300L);
        }

        mod.log("projection focus: startup focus guard active in " + app.processName);
    }

    public static void gpm(final ModContext mod, final LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        if (!Points.has("gpm.sensor_source.init")
                || !Points.has("gpm.receiver.destroy")
                || !Points.has("gpm.receiver_service.disconnected")) {
            mod.log("projection focus: GPM hook points missing on this build; AA focus notifications off");
            return;
        }
        mod.hook(cl, "gpm.sensor_source.init", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                aaSessionUp = true;
                if (!active(mod)) return;
                sendFocusBroadcast(mod, app.context, "aa", "connected");
            }
        });
        MethodHook ended = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (!aaSessionUp) return;
                aaSessionUp = false;
                if (active(mod)) sendFocusBroadcast(mod, app.context, "aa", "disconnected");
            }
        };
        mod.hook(cl, "gpm.receiver.destroy", ended);
        mod.hook(cl, "gpm.receiver_service.disconnected", ended);
        mod.log("projection focus: Android Auto session observer in " + app.processName);
    }

    public static void onAaConnecting(ModContext mod, Context context) {
        if (!active(mod) || aaSessionUp) return;
        sendFocusBroadcast(mod, context, "aa", "connecting");
    }

    public static void dipo(final ModContext mod, final LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        for (String id : new String[] {
                "dipo.carplay.session_created",
                "dipo.carplay.session_started",
                "dipo.carplay.session_finalized" }) {
            if (!Points.has(id)) {
                mod.log("projection focus: CarPlay focus off on this car (" + id + " not mapped)");
                return;
            }
        }
        mod.hook(cl, "dipo.carplay.session_created", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                onCpConnecting(mod, app.context);
            }
        });
        mod.hook(cl, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                cpSessionUp = true;
                if (!active(mod)) return;
                sendFocusBroadcast(mod, app.context, "cp", "connected");
            }
        });
        mod.hook(cl, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (!cpSessionUp) return;
                cpSessionUp = false;
                if (active(mod)) sendFocusBroadcast(mod, app.context, "cp", "disconnected");
            }
        });
        mod.log("projection focus: CarPlay session observer in " + app.processName);
    }

    public static void onCpConnecting(ModContext mod, Context context) {
        if (!active(mod) || cpSessionUp) return;
        sendFocusBroadcast(mod, context, "cp", "connecting");
    }

    public static void onCpAborted(ModContext mod, Context context) {
        if (!active(mod) || cpSessionUp) return;
        sendFocusBroadcast(mod, context, "cp", "aborted");
    }

    static void sendFocusBroadcast(ModContext mod, Context context, String source, String state) {
        if (context == null) return;
        try {
            Intent i = new Intent(PROJECTION_FOCUS);
            i.setPackage(FocusPolicy.PKG_BTAUDIO);
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra("source", source);
            i.putExtra("state", state);
            context.sendBroadcast(i);
        } catch (Throwable t) {
            mod.error("projection focus broadcast (" + source + ":" + state + ")", t);
        }
    }

    static void yieldBtAudioFocus(final ModContext mod) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                List<Map.Entry<AudioManager.OnAudioFocusChangeListener, AudioManager>> entries;
                synchronized (btListeners) {
                    if (btListeners.isEmpty()) return;
                    entries = new ArrayList<Map.Entry<AudioManager.OnAudioFocusChangeListener, AudioManager>>(btListeners.entrySet());
                    btListeners.clear();
                }
                POLICY.noteBtFocusPreempted();
                IN_ABANDON.set(Boolean.TRUE);
                int count = 0;
                try {
                    for (Map.Entry<AudioManager.OnAudioFocusChangeListener, AudioManager> e : entries) {
                        AudioManager.OnAudioFocusChangeListener l = e.getKey();
                        AudioManager am = e.getValue();
                        if (l == null) continue;
                        count++;
                        try {
                            l.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
                        } catch (Throwable ignored) {}
                        try {
                            if (am != null) am.abandonAudioFocus(l);
                        } catch (Throwable ignored) {}
                    }
                } finally {
                    IN_ABANDON.remove();
                }
                if (count > 0) {
                    mod.log("projection focus: yielded " + count + " startup Bluetooth Audio focus listener(s)");
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler().post(r);
    }

    static void scheduleReclaim(final ModContext mod, final Context context, long delayMs, final int attempt) {
        if (context == null) return;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (!active(mod)) return;
                boolean cp = isCarPlayConnected(context);
                boolean aa = isAndroidAutoConnected(context);
                int mode = currentMode(context);
                int audio = micomAudioMode(context);
                boolean pgsCam = isPgsCameraActive();
                String top = topPackage(context);
                int action = POLICY.decideReclaim(cp, aa, mode, audio, pgsCam, top, now());
                if (action == FocusPolicy.ACTION_DEFER_CAMERA) {
                    if (attempt < MAX_CAMERA_RETRIES) {
                        scheduleReclaim(mod, context, 1000L, attempt + 1);
                    }
                    return;
                }
                String shortcut = FocusPolicy.shortcutForAction(action);
                if (shortcut != null) {
                    try {
                        context.sendBroadcast(new Intent(shortcut));
                        mod.log("projection focus: startup reclaim -> " + shortcut
                                + " (mode=" + mode + ", audio=" + audio + ", top=" + top + ")");
                    } catch (Throwable t) {
                        mod.error("projection focus reclaim", t);
                    }
                }
            }
        };
        mainHandler().postAtTime(r, RECLAIM_TOKEN, SystemClock.uptimeMillis() + Math.max(0L, delayMs));
    }

    static void cancelReclaims() {
        Handler h = sMainHandler;
        if (h != null) h.removeCallbacksAndMessages(RECLAIM_TOKEN);
    }

    static String targetPackage(Intent intent) {
        if (intent == null) return null;
        ComponentName c = intent.getComponent();
        if (c != null && c.getPackageName() != null) return c.getPackageName();
        return intent.getPackage();
    }

    private static Context appContext(Context c) {
        if (c == null) return null;
        Context app = c.getApplicationContext();
        return app != null ? app : c;
    }

    static boolean isCarPlayConnected(Context c) {
        if (cpSessionUp) return true;
        if (c == null || !Points.has("framework.dipo_manager.current_stat")) return false;
        try {
            Object dipo = c.getSystemService("dipo");
            return dipo != null && Integer.valueOf(1).equals(Points.call(null, "framework.dipo_manager.current_stat", dipo));
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isAndroidAutoConnected(Context c) {
        if (aaSessionUp) return true;
        if (c == null || !Points.has("framework.gpm_manager.new") || !Points.has("framework.gpm_manager.is_activated")) {
            return false;
        }
        try {
            Object gpm = sGpmManager;
            if (gpm == null && !sGpmManagerTried) {
                sGpmManagerTried = true;
                gpm = sGpmManager = Points.newInstance(null, "framework.gpm_manager.new", appContext(c));
            }
            return gpm != null && Boolean.TRUE.equals(Points.call(null, "framework.gpm_manager.is_activated", gpm));
        } catch (Throwable t) {
            return false;
        }
    }

    static int currentMode(Context c) {
        if (c == null) return -1;
        if (Points.has("framework.mode_manager.new") && Points.has("framework.mode_manager.current_mode")) {
            try {
                Object mm = sModeManager;
                if (mm == null && !sModeManagerTried) {
                    sModeManagerTried = true;
                    mm = sModeManager = Points.newInstance(null, "framework.mode_manager.new", appContext(c));
                }
                if (mm != null) {
                    Object r = Points.call(null, "framework.mode_manager.current_mode", mm);
                    if (r instanceof Integer) return (Integer) r;
                }
            } catch (Throwable ignored) {}
        }
        if (Points.has("framework.service_manager.get_service")
                && Points.has("framework.mode_service_stub.as_interface")
                && Points.has("framework.mode_service.current_mode")) {
            try {
                Object svc = sModeService;
                if (svc == null) {
                    IBinder b = (IBinder) Points.call(null, "framework.service_manager.get_service", null, "com.hkmc.micom.mode");
                    if (b == null) return -1;
                    svc = sModeService = Points.call(null, "framework.mode_service_stub.as_interface", null, b);
                }
                if (svc != null) {
                    Object r = Points.call(null, "framework.mode_service.current_mode", svc);
                    if (r instanceof Integer) return (Integer) r;
                }
            } catch (Throwable t) {
                sModeService = null;
            }
        }
        return -1;
    }

    static int micomAudioMode(Context c) {
        if (c == null || !Points.has("framework.micom_audio.new") || !Points.has("framework.micom_audio.mode")) {
            return -1;
        }
        try {
            Object mam = sMicomAudio;
            if (mam == null && !sMicomAudioTried) {
                sMicomAudioTried = true;
                mam = sMicomAudio = Points.newInstance(null, "framework.micom_audio.new", appContext(c));
            }
            if (mam != null) {
                Object r = Points.call(null, "framework.micom_audio.mode", mam);
                if (r instanceof Integer) return (Integer) r;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    static boolean isPgsCameraActive() {
        if (!Points.has("framework.service_manager.get_service")
                || !Points.has("framework.pgs_service_stub.as_interface")) {
            return false;
        }
        boolean hasRev = Points.has("framework.pgs_service.reverse_status");
        boolean hasRear = Points.has("framework.pgs_service.driving_rear_view_on");
        boolean hasFront = Points.has("framework.pgs_service.front_view_on");
        if (!hasRev && !hasRear && !hasFront) return false;
        try {
            Object pgs = sPgsService;
            if (pgs == null) {
                IBinder b = (IBinder) Points.call(null, "framework.service_manager.get_service", null, "com.hkmc.micom.pgs");
                if (b == null) return false;
                pgs = sPgsService = Points.call(null, "framework.pgs_service_stub.as_interface", null, b);
            }
            if (pgs == null) return false;
            if (hasRev && Boolean.TRUE.equals(Points.call(null, "framework.pgs_service.reverse_status", pgs))) return true;
            if (hasRear && Boolean.TRUE.equals(Points.call(null, "framework.pgs_service.driving_rear_view_on", pgs))) return true;
            if (hasFront && Boolean.TRUE.equals(Points.call(null, "framework.pgs_service.front_view_on", pgs))) return true;
        } catch (Throwable t) {
            sPgsService = null;
        }
        return false;
    }

    static String topPackage(Context c) {
        if (c == null) return null;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) return null;
            return tasks.get(0).topActivity.getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }
}
