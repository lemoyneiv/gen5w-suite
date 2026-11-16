package dev.lemoyne.comfort;

import android.os.SystemClock;
import android.util.Log;

import hmgapi.client.HmgApi;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Ultra-low-latency boot & QuickBoot STR resume daemon run directly under app_process
 * (user=system, start=boot) by hmgapid.
 *
 * Unlike Android BroadcastReceivers or Services—which wait behind PackageManager sync,
 * ActivityManager startup, BOOT_COMPLETED broadcast queues, and multi-signal vehicle reads—
 * WelcomeDaemon starts at t=0, polls sys.boot_completed in memory every 50 ms on cold boot,
 * and watches both kernel monotonic-vs-uptime suspend gaps (>=3 s) and /data/hmgapi/public/wake
 * every 150 ms across QuickBoot STR cycles.
 */
public final class WelcomeDaemon {
    private static final String TAG = "HmgComfort";
    private static final File STATE_FILE = new File("/data/hmgapi/public/state.json");
    private static final File WAKE_FILE = new File("/data/hmgapi/public/wake");

    private static Method getPropMethod;
    private static boolean welcomeDismiss = true;
    private static int welcomeDelayMs = 0;
    private static long lastStateMtime = -1L;

    private WelcomeDaemon() {}

    public static void main(String[] args) {
        initSystemProperties();
        log("welcome fast-path daemon started");

        // 1. Cold-boot wait: poll sys.boot_completed in-process every 50 ms
        while (!"1".equals(getProp("sys.boot_completed"))) {
            sleepQuietly(50);
        }

        long lastSleepGap = currentSleepGap();
        long lastWakeSeq = readWakeSeq();

        refreshSettings(true);
        if (welcomeDismiss) {
            dismissWithRetry("cold-boot");
        }

        // 2. Resident QuickBoot STR & wake.sh watcher (150 ms response)
        while (true) {
            sleepQuietly(150);
            refreshSettings(false);

            long gap = currentSleepGap();
            long wakeSeq = readWakeSeq();
            boolean gapWoke = gap - lastSleepGap >= 3000L;
            boolean seqWoke = wakeSeq > lastWakeSeq;
            lastSleepGap = gap;
            lastWakeSeq = wakeSeq;

            if ((gapWoke || seqWoke) && welcomeDismiss) {
                dismissWithRetry(gapWoke ? "quickboot-gap" : "wake-hook");
                // Re-baseline after dismissal in case time advanced during retries
                lastSleepGap = currentSleepGap();
                lastWakeSeq = readWakeSeq();
            }
        }
    }

    private static void dismissWithRetry(String trigger) {
        int delay = welcomeDelayMs;
        if (delay > 0) {
            sleepQuietly(delay);
        }
        for (int attempt = 1; attempt <= 40; attempt++) {
            try {
                HmgApi api = HmgApi.connect().timeout(1500);
                if (!api.available()) {
                    sleepQuietly(100);
                    continue;
                }
                if (api.mod() == null) {
                    log("welcome fast-path (" + trigger + "): mod disabled");
                    return;
                }
                String res = api.command("startup_notice", "dismiss");
                log("welcome fast-path (" + trigger + ", attempt " + attempt + ") -> " + res);
                return;
            } catch (HmgApi.HmgApiException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("once per start")
                        || msg.contains("not in P")
                        || msg.contains("hasn't allowed")
                        || msg.contains("didn't allow")) {
                    log("welcome fast-path (" + trigger + "): " + msg);
                    return;
                }
                sleepQuietly(100);
            } catch (Throwable ignored) {
                sleepQuietly(100);
            }
        }
        log("welcome fast-path (" + trigger + "): timed out waiting for vehicle hub");
    }

    private static void refreshSettings(boolean force) {
        long mtime = STATE_FILE.isFile() ? STATE_FILE.lastModified() : 0L;
        if (!force && mtime == lastStateMtime) return;
        try {
            HmgApi api = HmgApi.connect().timeout(1500);
            if (!api.available() || api.mod() == null) return;
            JSONObject s = api.settings();
            if (s != null) {
                welcomeDismiss = s.optBoolean("welcome_dismiss", true);
                int d = s.optInt("welcome_delay_ms", 0);
                welcomeDelayMs = Math.max(0, Math.min(10000, d));
                lastStateMtime = mtime;
            }
        } catch (Throwable ignored) {}
    }

    private static long currentSleepGap() {
        return SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();
    }

    static long readWakeSeq() {
        if (!WAKE_FILE.isFile()) return 0L;
        FileInputStream in = null;
        try {
            in = new FileInputStream(WAKE_FILE);
            byte[] b = new byte[32];
            int n = in.read(b);
            if (n <= 0) return 0L;
            return Long.parseLong(new String(b, 0, n, "UTF-8").trim());
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void initSystemProperties() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            getPropMethod = sp.getMethod("get", String.class);
        } catch (Throwable ignored) {}
    }

    private static String getProp(String key) {
        if (getPropMethod != null) {
            try {
                Object v = getPropMethod.invoke(null, key);
                return v == null ? "" : String.valueOf(v).trim();
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String msg) {
        String line = "[hmgapi-comfort] " + msg;
        System.out.println(line);
        try {
            Log.i(TAG, line);
        } catch (Throwable ignored) {}
    }
}
