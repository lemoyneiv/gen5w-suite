package dev.lemoyne.comfort;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import hmgapi.client.HmgApi;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Background coordinator for Comfort & Startup:
 * - Event-driven startup notice dismissal on cold boot and QuickBoot wake
 * - Multi-tier cold/warm seat & steering wheel climate on EV Ready
 */
public final class ComfortEngine {
    private static final String TAG = "HmgComfort";
    private static final String[] READ_KEYS = {
        "hv.ready", "gear.park", "outside_temp_f", "outside_temp", "wheel_heat",
        "seat_heat.driver", "seat_heat.passenger", "seat_heat.rear_left", "seat_heat.rear_right",
        "seat_vent.driver", "seat_vent.passenger",
        "seat_state.driver", "seat_state.passenger", "seat_state.rear_left", "seat_state.rear_right"
    };

    public interface Listener {
        void onStateUpdated(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final ComfortConfig config;
        public final Map<String, String> readings;
        public final String lastClimateStatus;
        public final String lastWelcomeStatus;
        public final boolean connected;

        Snapshot(ComfortConfig config, Map<String, String> readings,
                 String lastClimateStatus, String lastWelcomeStatus, boolean connected) {
            this.config = config;
            this.readings = readings;
            this.lastClimateStatus = lastClimateStatus;
            this.lastWelcomeStatus = lastWelcomeStatus;
            this.connected = connected;
        }
    }

    private static ComfortEngine instance;

    public static synchronized ComfortEngine getInstance(Context ctx) {
        if (instance == null) {
            instance = new ComfortEngine(ctx.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final ComfortPolicy.DriveTracker driveTracker = new ComfortPolicy.DriveTracker();
    private final ComfortPolicy.WelcomeTracker welcomeTracker =
            new ComfortPolicy.WelcomeTracker(SystemClock.elapsedRealtime());

    private volatile ComfortConfig config = ComfortConfig.defaults();
    private volatile Map<String, String> lastReadings = new LinkedHashMap<String, String>();
    private volatile String lastClimateStatus = "Waiting for EV Ready";
    private volatile String lastWelcomeStatus = "Armed for startup";
    private volatile boolean connected;
    private volatile boolean running;
    private volatile boolean reloadSettings = true;
    private volatile boolean forceClimateRun;
    private volatile boolean forceWelcomeRun;
    private Thread worker;
    private int welcomeAttempts;

    private ComfortEngine(Context appContext) {
        this.appContext = appContext;
    }

    public synchronized void ensureStarted() {
        if (running && worker != null && worker.isAlive()) return;
        running = true;
        worker = new Thread("ComfortEngineLoop") {
            @Override
            public void run() {
                loop();
            }
        };
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
        l.onStateUpdated(snapshot());
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(config, new LinkedHashMap<String, String>(lastReadings),
                lastClimateStatus, lastWelcomeStatus, connected);
    }

    public void onScreenOff() {
        welcomeTracker.onScreenOff();
        welcomeAttempts = 0;
        wakeWorker();
    }

    public void onScreenOn() {
        welcomeTracker.onScreenOn(SystemClock.elapsedRealtime());
        welcomeAttempts = 0;
        wakeWorker();
    }

    public void requestSettingsReload() {
        reloadSettings = true;
        wakeWorker();
    }

    public void triggerClimateNow() {
        forceClimateRun = true;
        wakeWorker();
    }

    public void triggerWelcomeNow() {
        forceWelcomeRun = true;
        wakeWorker();
    }

    private synchronized void wakeWorker() {
        if (worker != null) worker.interrupt();
    }

    private void loop() {
        Log.i(TAG, "[hmgapi-comfort] background coordinator started");
        while (running) {
            try {
                long now = SystemClock.elapsedRealtime();
                long sleepGap = now - SystemClock.uptimeMillis();
                driveTracker.onSuspendCheck(sleepGap);
                welcomeTracker.onSuspendCheck(sleepGap, now);

                HmgApi api = HmgApi.connect().timeout(3000);
                if (!api.available()) {
                    connected = false;
                    notifyListeners();
                    sleepQuietly(3000);
                    continue;
                }
                String modId = api.mod();
                if (modId == null) {
                    connected = false;
                    notifyListeners();
                    sleepQuietly(5000);
                    continue;
                }
                connected = true;

                if (reloadSettings) {
                    reloadSettings = false;
                    config = parseConfig(api.settings());
                }

                Map<String, String> v = api.vehicle(READ_KEYS);
                lastReadings = v;

                // 1. Startup safety notice auto-dismiss
                if (forceWelcomeRun || (config.welcomeDismiss
                        && welcomeTracker.readyToDismiss(SystemClock.elapsedRealtime(), config.welcomeDelayMs))) {
                    boolean manual = forceWelcomeRun;
                    forceWelcomeRun = false;
                    handleWelcome(api, v, manual);
                }

                // 2. Cold/Warm auto-climate on EV Ready rising edge (or manual test button)
                boolean manualClimate = forceClimateRun;
                forceClimateRun = false;
                if (manualClimate || (config.climateEnabled && driveTracker.shouldTrigger(v.get("hv.ready")))) {
                    runClimateSequence(api, manualClimate);
                }

                notifyListeners();
                sleepQuietly(2500);
            } catch (Throwable t) {
                Log.w(TAG, "[hmgapi-comfort] loop error: " + t);
                sleepQuietly(3000);
            }
        }
        Log.i(TAG, "[hmgapi-comfort] background coordinator stopped");
    }

    private void handleWelcome(HmgApi api, Map<String, String> v, boolean manual) {
        String park = v.get("gear.park");
        if ("0".equals(park) && !manual) {
            welcomeTracker.markDone();
            welcomeAttempts = 0;
            lastWelcomeStatus = stamp("Skipped (shifted out of Park)");
            return;
        }
        try {
            String res = api.command("startup_notice", "dismiss");
            welcomeTracker.markDone();
            welcomeAttempts = 0;
            lastWelcomeStatus = stamp("Dismissed (" + res + ")");
            Log.i(TAG, "[hmgapi-comfort] startup_notice dismiss -> " + res);
        } catch (HmgApi.HmgApiException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("once per start") || msg.contains("hasn't allowed") || msg.contains("didn't allow")) {
                welcomeTracker.markDone();
                welcomeAttempts = 0;
                lastWelcomeStatus = stamp(msg);
                Log.i(TAG, "[hmgapi-comfort] startup_notice -> " + msg);
            } else {
                welcomeAttempts++;
                if (welcomeAttempts >= 8) {
                    welcomeTracker.markDone();
                    welcomeAttempts = 0;
                    lastWelcomeStatus = stamp("Gave up: " + msg);
                } else {
                    lastWelcomeStatus = stamp("Retrying (" + welcomeAttempts + "): " + msg);
                }
            }
        } catch (IOException e) {
            welcomeAttempts++;
            if (welcomeAttempts >= 8) {
                welcomeTracker.markDone();
                welcomeAttempts = 0;
            }
            lastWelcomeStatus = stamp("Error: " + e.getMessage());
        }
    }

    private void runClimateSequence(HmgApi api, boolean manual) throws IOException {
        if (!manual) {
            sleepQuietly(2000);   // let BCM/USM/VR settle after EV Ready transition
        }
        // Wait up to 30 s for valid post-wake outside_temp_f
        Map<String, String> v = null;
        float tempF = Float.NaN;
        long deadline = SystemClock.elapsedRealtime() + 30000L;
        while (running && SystemClock.elapsedRealtime() < deadline) {
            v = api.vehicle(READ_KEYS);
            lastReadings = v;
            if (!manual && !"1".equals(v.get("hv.ready"))) {
                lastClimateStatus = stamp("Aborted (EV Ready turned off)");
                return;
            }
            tempF = ComfortPolicy.parseAmbientF(v.get("outside_temp_f"));
            if (!Float.isNaN(tempF)) break;
            lastClimateStatus = stamp("Waiting for valid outside temperature...");
            notifyListeners();
            sleepQuietly(1500);
        }

        if (Float.isNaN(tempF) || v == null) {
            driveTracker.markCompleted();
            lastClimateStatus = stamp("Skipped (outside temperature unavailable)");
            Log.w(TAG, "[hmgapi-comfort] outside_temp_f unavailable after 30 s; skipping climate");
            return;
        }

        ComfortPolicy.Plan plan = ComfortPolicy.evaluateClimate(config, tempF, v);
        if (plan.actions.isEmpty()) {
            driveTracker.markCompleted();
            lastClimateStatus = stamp(plan.summary);
            Log.i(TAG, "[hmgapi-comfort] " + plan.summary);
            return;
        }

        boolean appliedAll = executeActionsWithRetry(api, plan.actions);
        driveTracker.markCompleted();
        lastClimateStatus = stamp(plan.summary + (appliedAll ? "" : " (partial)"));
        Log.i(TAG, "[hmgapi-comfort] " + lastClimateStatus);
        if (config.toastOnApply) {
            HmgApi.toast(appContext, "Comfort: " + plan.summary);
        }
    }

    private boolean executeActionsWithRetry(HmgApi api, List<ComfortPolicy.Action> actions) throws IOException {
        List<ComfortPolicy.Action> pending = new ArrayList<ComfortPolicy.Action>(actions);
        for (int attempt = 0; attempt < 3 && !pending.isEmpty() && running; attempt++) {
            boolean waitingOwner = false;
            List<ComfortPolicy.Action> sent = new ArrayList<ComfortPolicy.Action>();
            for (ComfortPolicy.Action act : pending) {
                ComfortPolicy.Action actual = act;
                try {
                    api.command(act.command, act.args());
                    sent.add(actual);
                } catch (HmgApi.HmgApiException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.contains("two levels") && "medium".equals(act.level)) {
                        actual = new ComfortPolicy.Action(act.command, act.target, ComfortPolicy.fallbackTwoStep(act.level));
                        try {
                            api.command(actual.command, actual.args());
                            sent.add(actual);
                        } catch (IOException e2) {
                            Log.w(TAG, "[hmgapi-comfort] two-step fallback failed: " + e2);
                        }
                    } else if (msg.contains("waiting for the owner")) {
                        waitingOwner = true;
                        sent.add(actual);
                    } else {
                        Log.w(TAG, "[hmgapi-comfort] command refused (" + act + "): " + msg);
                    }
                }
                sleepQuietly(220);
            }
            if (waitingOwner) {
                lastClimateStatus = stamp("Waiting for owner command approval on screen...");
                notifyListeners();
                sleepQuietly(5000);
            } else {
                sleepQuietly(1500);
            }
            Map<String, String> check = api.vehicle(READ_KEYS);
            lastReadings = check;
            List<ComfortPolicy.Action> retry = new ArrayList<ComfortPolicy.Action>();
            for (ComfortPolicy.Action act : sent) {
                if ("wheel_heat".equals(act.command)) {
                    if ("0".equals(check.get("wheel_heat"))) retry.add(act);
                } else {
                    String st = check.get("seat_state." + act.target);
                    if ("2".equals(st)) retry.add(act);
                }
            }
            pending = retry;
        }
        return pending.isEmpty();
    }

    public static ComfortConfig parseConfig(JSONObject j) {
        ComfortConfig d = ComfortConfig.defaults();
        if (j == null) return d;
        return new ComfortConfig(
                j.optBoolean("welcome_dismiss", d.welcomeDismiss),
                j.optInt("welcome_delay_ms", d.welcomeDelayMs),
                j.optBoolean("climate_enabled", d.climateEnabled),
                j.optInt("heat_high_f", d.heatHighF),
                j.optInt("heat_med_f", d.heatMedF),
                j.optInt("heat_low_f", d.heatLowF),
                j.optBoolean("wheel_enabled", d.wheelEnabled),
                j.optInt("wheel_f", d.wheelF),
                j.optString("passenger_policy", d.passengerPolicy),
                j.optString("rear_left_policy", d.rearLeftPolicy),
                j.optString("rear_right_policy", d.rearRightPolicy),
                j.optBoolean("vent_enabled", d.ventEnabled),
                j.optInt("vent_low_f", d.ventLowF),
                j.optInt("vent_med_f", d.ventMedF),
                j.optInt("vent_high_f", d.ventHighF),
                j.optBoolean("toast_on_apply", d.toastOnApply));
    }

    public static JSONObject toJson(ComfortConfig c) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("welcome_dismiss", c.welcomeDismiss);
        j.put("welcome_delay_ms", c.welcomeDelayMs);
        j.put("climate_enabled", c.climateEnabled);
        j.put("heat_high_f", c.heatHighF);
        j.put("heat_med_f", c.heatMedF);
        j.put("heat_low_f", c.heatLowF);
        j.put("wheel_enabled", c.wheelEnabled);
        j.put("wheel_f", c.wheelF);
        j.put("passenger_policy", c.passengerPolicy);
        j.put("rear_left_policy", c.rearLeftPolicy);
        j.put("rear_right_policy", c.rearRightPolicy);
        j.put("vent_enabled", c.ventEnabled);
        j.put("vent_low_f", c.ventLowF);
        j.put("vent_med_f", c.ventMedF);
        j.put("vent_high_f", c.ventHighF);
        j.put("toast_on_apply", c.toastOnApply);
        return j;
    }

    private void notifyListeners() {
        Snapshot s = snapshot();
        List<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<Listener>(listeners);
        }
        for (Listener l : copy) {
            try {
                l.onStateUpdated(s);
            } catch (Throwable ignored) {}
        }
    }

    private static String stamp(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        return "[" + t + "] " + msg;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }
}
