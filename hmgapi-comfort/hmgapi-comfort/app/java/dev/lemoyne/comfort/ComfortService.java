package dev.lemoyne.comfort;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import hmgapi.client.HmgApi;

/**
 * Non-root sticky service hosting {@link ComfortEngine} and listening for Android + Mobis
 * screen wake/sleep broadcasts and HMG-API state/settings updates.
 */
public final class ComfortService extends Service {
    public static final String ACTION_MOBIS_SCREEN_ON = "com.mobis.intent.action.SCREEN_ON";
    public static final String ACTION_MOBIS_SCREEN_OFF = "com.mobis.intent.action.SCREEN_OFF";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ComfortEngine engine;

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || engine == null) return;
            String act = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(act) || ACTION_MOBIS_SCREEN_OFF.equals(act)) {
                engine.onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(act) || ACTION_MOBIS_SCREEN_ON.equals(act)) {
                engine.onScreenOn();
            } else if (HmgApi.ACTION_STATE_CHANGED.equals(act)) {
                checkEnabledAndReload();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        engine = ComfortEngine.getInstance(this);
        engine.ensureStarted();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(ACTION_MOBIS_SCREEN_ON);
        f.addAction(ACTION_MOBIS_SCREEN_OFF);
        f.addAction(HmgApi.ACTION_STATE_CHANGED);
        registerReceiver(eventReceiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (engine != null) {
            engine.ensureStarted();
            if (intent != null && ACTION_MOBIS_SCREEN_ON.equals(intent.getAction())) {
                engine.onScreenOn();
            }
            engine.requestSettingsReload();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(eventReceiver);
        } catch (Throwable ignored) {}
        if (engine != null) {
            engine.stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void checkEnabledAndReload() {
        Thread t = new Thread("ComfortStateCheck") {
            @Override
            public void run() {
                try {
                    HmgApi api = HmgApi.connect().timeout(2000);
                    if (api.available() && api.mod() == null) {
                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                stopSelf();
                            }
                        });
                        return;
                    }
                    if (engine != null) {
                        engine.requestSettingsReload();
                    }
                } catch (Throwable ignored) {}
            }
        };
        t.setDaemon(true);
        t.start();
    }
}
