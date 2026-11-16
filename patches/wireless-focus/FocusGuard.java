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
 * Runtime focus arbitrator ensuring Phone Projection (CarPlay / Android Auto) always
 * wins audio, mode, and screen conflicts against Bluetooth Audio (HKMC_BtAudio).
 *
 * Installed into:
 *   - com.daudio.av.app.btaudio.ui: blocks requestAudioFocus (mode 54 / 3-arg) and self-UI
 *     launches whenever Projection is connecting or active; immediately yields any held BT Audio
 *     focus listener on Projection startup so MediaFocusControl never pops back to mode 54;
 *     and reclaims Projection focus if ModeManagerService resumes MusicActivity over Projection.
 *   - com.google.android.projection.sink: broadcasts Android Auto connecting/connected/disconnected
 *     transitions and reclaims focus if Bluetooth Audio grabbed ModeType 54 earlier during boot.
 *   (CarPlay's matching broadcasts + reclaim run in hmgapi.mods.wireless.dipo.Dipo / Bridge.)
 */
final class FocusGuard {
    private FocusGuard() {}

    static final String PROJECTION_FOCUS = "hmgapi.mods.wireless.PROJECTION_FOCUS";
    static final int MAX_CAMERA_RETRIES = 12;
    private static final long LOG_THROTTLE_MS = 2_000L;

    static final FocusPolicy POLICY = new FocusPolicy();
    private static final Object RECLAIM_TOKEN = new Object();
    private static final ThreadLocal<Boolean> IN_ABANDON = new ThreadLocal<Boolean>();
    private static final WeakHashMap<AudioManager.OnAudioFocusChangeListener, AudioManager> btListeners =
            new WeakHashMap<AudioManager.OnAudioFocusChangeListener, AudioManager>();
    private static final WeakHashMap<Activity, Boolean> resumedActivities =
            new WeakHashMap<Activity, Boolean>();

    private static volatile Handler sMainHandler;
    private static volatile boolean aaSessionUp;
    private static volatile long lastBlockedLogAt = -LOG_THROTTLE_MS;

    static boolean active(ModContext mod) {
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

    static void btAudio(final ModContext mod, final LoadedApp app) {
        MethodHook focusHook = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (!active(mod)) return;
                int stream = (Integer) p.args[1];
                int mode = p.args.length >= 4 ? (Integer) p.args[3] : 0;
                long t = now();
                boolean cp = isCarPlayConnected(app.context);
                boolean aa = isAndroidAutoConnected(app.context);
                if (POLICY.shouldBlockBtAudioFocus(stream, mode, cp, aa, t)) {
                    p.setResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
                    if (t - lastBlockedLogAt >= LOG_THROTTLE_MS) {
                        lastBlockedLogAt = t;
                        mod.log("projection focus: denied Bluetooth Audio requestAudioFocus (stream=" + stream
                                + ", mode=" + mode + ", cp=" + cp + ", aa=" + aa + ")");
                    }
                }
            }

            @Override
            protected void afterHookedMethod(Param p) {
                if (!active(mod)) return;
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
                String targetPkg = targetPackage(intent);
                if (POLICY.shouldBlockBtActivityLaunch(targetPkg,
                        isCarPlayConnected(app.context), isAndroidAutoConnected(app.context), now())) {
                    p.setResult(null);
                    mod.log("projection focus: suppressed Bluetooth Audio activity launch while Projection owns focus");
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
                    if (p.thisObject instanceof Activity) {
                        synchronized (resumedActivities) {
                            resumedActivities.put((Activity) p.thisObject, Boolean.TRUE);
                        }
                    }
                    if (!active(mod)) return;
                    if (POLICY.connectedSource(isCarPlayConnected(app.context), isAndroidAutoConnected(app.context), now()) != FocusPolicy.SOURCE_NONE) {
                        scheduleReclaim(mod, app.context, 150L, 0);
                    }
                }
            });
            Hooks.findAndHookMethod(Activity.class, "onPause", new MethodHook() {
                @Override
                protected void afterHookedMethod(Param p) {
                    if (p.thisObject instanceof Activity) {
                        synchronized (resumedActivities) {
                            resumedActivities.remove(p.thisObject);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            mod.error("projection focus: Activity lifecycle hooks", t);
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
                        scheduleReclaim(mod, app.context, 250L, 0);
                    } else if ("disconnected".equals(state)) {
                        cancelReclaims();
                    }
                }
            }, new IntentFilter(PROJECTION_FOCUS));
        } catch (Throwable t) {
            mod.error("projection focus: receiver in btaudio", t);
        }
        mod.log("projection focus: Bluetooth Audio yields to Phone Projection in " + app.processName);
    }

    static void gpm(final ModContext mod, final LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, "gpm.sensor_source.init", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                aaSessionUp = true;
                if (!active(mod)) return;
                POLICY.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connected", now());
                sendFocusBroadcast(mod, app.context, "aa", "connected");
                scheduleReclaim(mod, app.context, 450L, 0);
                scheduleReclaim(mod, app.context, 2050L, 0);
            }
        });
        MethodHook ended = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (!aaSessionUp) return;
                aaSessionUp = false;
                POLICY.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "disconnected", now());
                cancelReclaims();
                if (active(mod)) sendFocusBroadcast(mod, app.context, "aa", "disconnected");
            }
        };
        mod.hook(cl, "gpm.receiver.destroy", ended);
        mod.hook(cl, "gpm.receiver_service.disconnected", ended);
        mod.log("projection focus: Android Auto focus arbitrator in " + app.processName);
    }

    static void onAaConnecting(ModContext mod, Context context) {
        if (!active(mod) || aaSessionUp) return;
        POLICY.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connecting", now());
        sendFocusBroadcast(mod, context, "aa", "connecting");
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
                    mod.log("projection focus: yielded " + count + " Bluetooth Audio focus listener(s) to Phone Projection");
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
                String top = topPackage(context);
                boolean btResumed = hasResumedBtActivity();
                int action = POLICY.decideReclaim(cp, aa, mode, audio, btResumed, top, now());
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
                        mod.log("projection focus: reclaimed focus from Bluetooth Audio -> " + shortcut
                                + " (mode=" + mode + ", audio=" + audio + ", btResumed=" + btResumed + ", top=" + top + ")");
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

    static boolean hasResumedBtActivity() {
        synchronized (resumedActivities) {
            return !resumedActivities.isEmpty();
        }
    }

    static String targetPackage(Intent intent) {
        if (intent == null) return null;
        ComponentName c = intent.getComponent();
        if (c != null && c.getPackageName() != null) return c.getPackageName();
        return intent.getPackage();
    }

    static boolean isCarPlayConnected(Context c) {
        if (c == null) return false;
        try {
            Object dipo = c.getSystemService("dipo");
            return dipo != null && Integer.valueOf(1).equals(Points.call(null, "framework.dipo_manager.current_stat", dipo));
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isAndroidAutoConnected(Context c) {
        if (c == null) return false;
        try {
            Object gpm = Points.newInstance(null, "framework.gpm_manager.new", c);
            return Boolean.TRUE.equals(Points.call(null, "framework.gpm_manager.is_activated", gpm));
        } catch (Throwable t) {
            return false;
        }
    }

    static int currentMode(Context c) {
        if (c == null) return -1;
        try {
            Class<?> cls = Class.forName("com.hkmc.mode.ModeManager");
            Object mm = cls.getConstructor(Context.class).newInstance(c);
            return (Integer) cls.getMethod("getCurrentMode").invoke(mm);
        } catch (Throwable ignored) {}
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "com.hkmc.micom.mode");
            if (b == null) return -1;
            Object svc = Class.forName("com.hkmc.mode.IModeManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, b);
            return (Integer) Class.forName("com.hkmc.mode.IModeManager")
                    .getMethod("getCurrentMode").invoke(svc);
        } catch (Throwable t) {
            return -1;
        }
    }

    static int micomAudioMode(Context c) {
        if (c == null) return -1;
        try {
            Class<?> cls = Class.forName("com.hkmc.media.MicomAudioManager");
            Object mam = cls.getConstructor(Context.class).newInstance(c);
            return (Integer) cls.getMethod("getAudioMode").invoke(mam);
        } catch (Throwable t) {
            return -1;
        }
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
