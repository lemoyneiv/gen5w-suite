package hmgapi.mods.wireless;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

import java.util.Set;

import hmgapi.hook.CarEvents;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * Clean parked/shutdown wireless projection teardown and post-quick-stop
 * SoftAP+BT-ready reconnect coordinator for Android Auto ({@code HKMC_GPM})
 * and CarPlay ({@code HKMC_DiPO}).
 *
 * Design invariants:
 *  - Per-boot RAM-only phone tracking ({@link FocusPolicy.ParkSessionPolicy}):
 *    never writes persistent {@code Settings.Global} phone-choice lock-outs, so
 *    switching between household phones works freely.
 *  - Never triggers SoftAP or wireless projection while HMG-API Home Wi-Fi or
 *    OpenLink Dark Standby holds {@code wlan0}.
 *  - Waits until BOTH Connectivity SoftAP ({@code WIFI_AP_STATE_ENABLED == 13})
 *    and Bluetooth ({@code STATE_ON == 12}) are ready before nudging
 *    {@code connectWirelessSession} or {@code restartBonjour}.
 */
public final class ParkSession {
    private ParkSession() {}

    static final String ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    static final String EXTRA_WIFI_AP_STATE = "wifi_state";

    private static final Object GPM_CONNECT_TOKEN = new Object();
    private static final Object GPM_CHECK_TOKEN = new Object();
    private static final Object DIPO_CHECK_TOKEN = new Object();

    private static final FocusPolicy.ParkSessionPolicy GPM_POLICY = new FocusPolicy.ParkSessionPolicy();
    private static final FocusPolicy.ParkSessionPolicy DIPO_POLICY = new FocusPolicy.ParkSessionPolicy();

    private static volatile Object sGalService;
    private static volatile boolean sGpmReceiverRegistered;
    private static volatile Object sCpService;
    private static volatile boolean sDipoReceiverRegistered;

    public static void gpm(final ModContext mod, final LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        final String[] required = {
            "gpm.receiver_service.on_create",
            "gpm.sensor_source.init",
            "gpm.receiver.destroy",
            "gpm.receiver_service.disconnected",
            "gpm.receiver_service.connection_manager",
            "gpm.connection_manager.send_byebye",
            "gpm.receiver_service.wireless_explorer",
            "gpm.wireless_explorer.connected_device",
            "gpm.receiver_service.connect_wireless_session"
        };
        for (String id : required) {
            if (!Points.has(id)) {
                mod.log("park session: GPM off on this car (" + id + " not mapped)");
                return;
            }
        }

        mod.hook(cl, "gpm.receiver_service.on_create", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                sGalService = p.thisObject;
                Context ctx = p.thisObject instanceof Context ? (Context) p.thisObject : app.context;
                ensureGpmReceivers(mod, cl, ctx);
            }
        });

        mod.hook(cl, "gpm.sensor_source.init", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                String addr = connectedWirelessAddr(cl);
                GPM_POLICY.onAaConnected(addr);
                cancelGpmPending();
                ensureGpmReceivers(mod, cl, app.context);
            }
        });

        MethodHook onEnd = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                String addr = connectedWirelessAddr(cl);
                GPM_POLICY.onAaDisconnected(addr);
            }
        };
        mod.hook(cl, "gpm.receiver.destroy", onEnd);
        mod.hook(cl, "gpm.receiver_service.disconnected", onEnd);

        ensureGpmReceivers(mod, cl, app.context);

        try {
            mod.onCarEvent(app, new CarEvents.Listener() {
                @Override
                public void onCarEvent(final String event) {
                    if (!mod.enabled()) return;
                    if (CarEvents.PARKED.equals(event) || CarEvents.SHUTDOWN.equals(event)) {
                        cancelGpmPending();
                        GPM_POLICY.noteAaPhone(connectedWirelessAddr(cl));
                        if (!GPM_POLICY.onParkOrShutdown(true)) return;
                        FocusGuard.mainHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                sendAaByeBye(mod, cl, event);
                            }
                        });
                    } else if (CarEvents.UNPARKED.equals(event) || CarEvents.WAKE.equals(event)) {
                        GPM_POLICY.onWakeOrUnpark();
                        scheduleGpmCheck(mod, cl, app.context, 1200L, null);
                        scheduleGpmCheck(mod, cl, app.context, 5200L, null);
                    }
                }
            });
        } catch (Throwable t) {
            mod.error("park session: GPM onCarEvent", t);
        }

        mod.log("park session: wireless Android Auto parked teardown & reconnect ready in " + app.processName);
    }

    public static void dipo(final ModContext mod, final LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        final String[] required = {
            "dipo.carplay.session_started",
            "dipo.carplay.session_finalized",
            "dipo.carplay.disconnect",
            "dipo.carplay.restart_bonjour"
        };
        for (String id : required) {
            if (!Points.has(id)) {
                mod.log("park session: DiPO off on this car (" + id + " not mapped)");
                return;
            }
        }

        mod.hook(cl, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                sCpService = p.thisObject;
                int transport = (p.args != null && p.args.length > 0 && p.args[0] instanceof Integer)
                        ? ((Integer) p.args[0]).intValue() : 0;
                DIPO_POLICY.onCpStarted(transport);
                FocusGuard.mainHandler().removeCallbacksAndMessages(DIPO_CHECK_TOKEN);
                ensureDipoReceivers(mod, cl, app.context);
            }
        });

        mod.hook(cl, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (p.thisObject != null) sCpService = p.thisObject;
                DIPO_POLICY.onCpFinalized();
            }
        });

        ensureDipoReceivers(mod, cl, app.context);

        try {
            mod.onCarEvent(app, new CarEvents.Listener() {
                @Override
                public void onCarEvent(final String event) {
                    if (!mod.enabled()) return;
                    if (CarEvents.PARKED.equals(event) || CarEvents.SHUTDOWN.equals(event)) {
                        FocusGuard.mainHandler().removeCallbacksAndMessages(DIPO_CHECK_TOKEN);
                        if (!DIPO_POLICY.onParkOrShutdown(false)) return;
                        FocusGuard.mainHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                Object cp = sCpService;
                                if (cp == null) return;
                                try {
                                    Object r = Points.call(cl, "dipo.carplay.disconnect", cp);
                                    mod.log("park session: CarPlay disconnect on " + event + " -> " + r);
                                } catch (Throwable t) {
                                    mod.error("park session: disconnectCarPlay", t);
                                }
                            }
                        });
                    } else if (CarEvents.UNPARKED.equals(event) || CarEvents.WAKE.equals(event)) {
                        DIPO_POLICY.onWakeOrUnpark();
                        scheduleDipoCheck(mod, cl, app.context, 900L);
                        scheduleDipoCheck(mod, cl, app.context, 3800L);
                    }
                }
            });
        } catch (Throwable t) {
            mod.error("park session: DiPO onCarEvent", t);
        }

        mod.log("park session: wireless CarPlay parked teardown & Bonjour resume ready in " + app.processName);
    }

    private static void ensureGpmReceivers(final ModContext mod, final ClassLoader cl, final Context ctx) {
        if (sGpmReceiverRegistered || ctx == null) return;
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        synchronized (ParkSession.class) {
            if (sGpmReceiverRegistered) return;
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
                f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
                f.addAction(ACTION_WIFI_AP_STATE_CHANGED);
                appCtx.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (!mod.enabled() || intent == null) return;
                        String act = intent.getAction();
                        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(act)) {
                            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                            String addr = dev != null ? dev.getAddress() : null;
                            if (addr != null && !addr.isEmpty()) {
                                scheduleGpmCheck(mod, cl, c, 400L, addr);
                            }
                        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(act)) {
                            int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                            if (st == FocusPolicy.BT_STATE_ON) {
                                scheduleGpmCheck(mod, cl, c, 900L, null);
                            }
                        } else if (ACTION_WIFI_AP_STATE_CHANGED.equals(act)) {
                            int st = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1);
                            if (st == FocusPolicy.WIFI_AP_STATE_ENABLED) {
                                scheduleGpmCheck(mod, cl, c, 900L, null);
                            }
                        }
                    }
                }, f);
                sGpmReceiverRegistered = true;
            } catch (Throwable t) {
                mod.error("park session: GPM receiver", t);
            }
        }
    }

    private static void ensureDipoReceivers(final ModContext mod, final ClassLoader cl, final Context ctx) {
        if (sDipoReceiverRegistered || ctx == null) return;
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        synchronized (ParkSession.class) {
            if (sDipoReceiverRegistered) return;
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
                f.addAction(ACTION_WIFI_AP_STATE_CHANGED);
                appCtx.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (!mod.enabled() || intent == null) return;
                        String act = intent.getAction();
                        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(act)) {
                            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == FocusPolicy.BT_STATE_ON) {
                                scheduleDipoCheck(mod, cl, c, 600L);
                            }
                        } else if (ACTION_WIFI_AP_STATE_CHANGED.equals(act)) {
                            if (intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1) == FocusPolicy.WIFI_AP_STATE_ENABLED) {
                                scheduleDipoCheck(mod, cl, c, 600L);
                            }
                        }
                    }
                }, f);
                sDipoReceiverRegistered = true;
            } catch (Throwable t) {
                mod.error("park session: DiPO receiver", t);
            }
        }
    }

    private static void sendAaByeBye(ModContext mod, ClassLoader cl, String event) {
        Object svc = sGalService;
        if (svc == null) return;
        try {
            Object cm = Points.get(cl, "gpm.receiver_service.connection_manager", svc);
            if (cm != null) {
                Points.call(cl, "gpm.connection_manager.send_byebye", cm, Integer.valueOf(1));
                mod.log("park session: sent Android Auto ByeByeRequest(1) on " + event);
            }
        } catch (Throwable t) {
            mod.error("park session: send_byebye", t);
        }
    }

    private static void scheduleGpmCheck(
            final ModContext mod,
            final ClassLoader cl,
            final Context ctx,
            long delayMs,
            final String aclAddrOrNull) {
        if (ctx == null) return;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                evaluateGpmReconnect(mod, cl, ctx, aclAddrOrNull);
            }
        };
        FocusGuard.mainHandler().postAtTime(r, GPM_CHECK_TOKEN, SystemClock.uptimeMillis() + Math.max(0L, delayMs));
    }

    private static void evaluateGpmReconnect(
            final ModContext mod,
            final ClassLoader cl,
            final Context ctx,
            String aclAddrOrNull) {
        if (!mod.enabled() || sGalService == null || ctx == null) return;
        if (!connectedWirelessAddr(cl).isEmpty() || FocusGuard.isAndroidAutoConnected(ctx) || FocusGuard.isCarPlayConnected(ctx)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean hold = FocusPolicy.isHomeWifiOrParkedLeaseActiveOnDevice(now, readSysProp("init.svc.wpa_supplicant", ""));
        int apState = readWifiApState(ctx);
        int btState = readBtState();
        int resumeMode = FocusPolicy.extractProjectionResumeModeOnDevice(now);
        String resumeAddr = FocusPolicy.extractProjectionResumeBtAddrOnDevice(now);

        final String target = GPM_POLICY.selectAaReconnectTarget(
                apState, btState, hold, resumeMode, resumeAddr, aclAddrOrNull, now);
        if (target.isEmpty() || !isBondedOrUnknown(target)) {
            return;
        }

        long connectDelayMs = (aclAddrOrNull != null && !aclAddrOrNull.isEmpty()) ? 3200L : 2400L;
        FocusGuard.mainHandler().removeCallbacksAndMessages(GPM_CONNECT_TOKEN);
        Runnable connectRun = new Runnable() {
            @Override
            public void run() {
                if (!mod.enabled()) return;
                Object svc = sGalService;
                if (svc == null) return;
                if (!connectedWirelessAddr(cl).isEmpty()
                        || FocusGuard.isAndroidAutoConnected(ctx)
                        || FocusGuard.isCarPlayConnected(ctx)) {
                    return;
                }
                long t = SystemClock.elapsedRealtime();
                if (FocusPolicy.isHomeWifiOrParkedLeaseActiveOnDevice(t, readSysProp("init.svc.wpa_supplicant", ""))) {
                    return;
                }
                if (readWifiApState(ctx) != FocusPolicy.WIFI_AP_STATE_ENABLED || readBtState() != FocusPolicy.BT_STATE_ON) {
                    return;
                }
                try {
                    FocusGuard.onAaConnecting(mod, ctx);
                    Points.call(cl, "gpm.receiver_service.connect_wireless_session", svc, target);
                    mod.log("park session: requested wireless Android Auto reconnect");
                    scheduleGpmCheck(mod, cl, ctx, 6500L, null);
                } catch (Throwable ex) {
                    mod.error("park session: connect_wireless_session", ex);
                }
            }
        };
        FocusGuard.mainHandler().postAtTime(connectRun, GPM_CONNECT_TOKEN, SystemClock.uptimeMillis() + connectDelayMs);
    }

    private static void scheduleDipoCheck(
            final ModContext mod,
            final ClassLoader cl,
            final Context ctx,
            long delayMs) {
        if (ctx == null) return;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (!mod.enabled() || ctx == null) return;
                Object cp = sCpService;
                if (cp == null || FocusGuard.isCarPlayConnected(ctx) || FocusGuard.isAndroidAutoConnected(ctx)) {
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                boolean hold = FocusPolicy.isHomeWifiOrParkedLeaseActiveOnDevice(now, readSysProp("init.svc.wpa_supplicant", ""));
                int apState = readWifiApState(ctx);
                int btState = readBtState();
                int resumeMode = FocusPolicy.extractProjectionResumeModeOnDevice(now);
                if (!DIPO_POLICY.shouldRestartCarPlayBonjour(apState, btState, hold, resumeMode, now)) {
                    return;
                }
                try {
                    Object res = Points.call(cl, "dipo.carplay.restart_bonjour", cp);
                    mod.log("park session: CarPlay restartBonjour -> " + res);
                } catch (Throwable t) {
                    mod.error("park session: restart_bonjour", t);
                }
            }
        };
        FocusGuard.mainHandler().postAtTime(r, DIPO_CHECK_TOKEN, SystemClock.uptimeMillis() + Math.max(0L, delayMs));
    }

    private static void cancelGpmPending() {
        FocusGuard.mainHandler().removeCallbacksAndMessages(GPM_CONNECT_TOKEN);
        FocusGuard.mainHandler().removeCallbacksAndMessages(GPM_CHECK_TOKEN);
    }

    private static String connectedWirelessAddr(ClassLoader cl) {
        Object svc = sGalService;
        if (svc == null) return "";
        try {
            Object we = Points.get(cl, "gpm.receiver_service.wireless_explorer", svc);
            if (we == null) return "";
            Object dev = Points.call(cl, "gpm.wireless_explorer.connected_device", we);
            return dev instanceof String ? FocusPolicy.normalizeBtMac((String) dev) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int readWifiApState(Context ctx) {
        if (ctx == null) return -1;
        try {
            Object wifi = ctx.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return -1;
            Object st = wifi.getClass().getMethod("getWifiApState").invoke(wifi);
            if (st instanceof Integer) return ((Integer) st).intValue();
            boolean on = Boolean.TRUE.equals(wifi.getClass().getMethod("isWifiApEnabled").invoke(wifi));
            return on ? FocusPolicy.WIFI_AP_STATE_ENABLED : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int readBtState() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) return -1;
            return bt.isEnabled() ? FocusPolicy.BT_STATE_ON : bt.getState();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isBondedOrUnknown(String normalizedMac) {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) return true;
            Set<BluetoothDevice> bonded = bt.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) return true;
            for (BluetoothDevice d : bonded) {
                if (d != null && normalizedMac.equals(FocusPolicy.normalizeBtMac(d.getAddress()))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String readSysProp(String key, String fallback) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
