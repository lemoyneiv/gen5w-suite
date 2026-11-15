package dev.lemoyne.comfort;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import hmgapi.client.HmgApi;

/**
 * Starts {@link ComfortService} on cold boot, QuickBoot Mobis SCREEN_ON, or live mod enablement.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (context == null) return;
        final PendingResult pending = goAsync();
        final String action = intent == null ? null : intent.getAction();
        Thread worker = new Thread("ComfortBootCheck") {
            @Override
            public void run() {
                try {
                    HmgApi api = HmgApi.connect().timeout(2000);
                    if (!api.available() || api.mod() != null) {
                        Intent svc = new Intent(context, ComfortService.class);
                        if (action != null) svc.setAction(action);
                        context.startService(svc);
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        pending.finish();
                    } catch (Throwable ignored) {}
                }
            }
        };
        worker.setDaemon(true);
        worker.start();
    }
}
