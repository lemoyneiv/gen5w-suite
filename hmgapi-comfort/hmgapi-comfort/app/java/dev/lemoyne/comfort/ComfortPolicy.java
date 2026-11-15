package dev.lemoyne.comfort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java decision logic and session trackers for Comfort & Startup, testable on a desktop JVM.
 */
public final class ComfortPolicy {
    private ComfortPolicy() {}

    public static final class Action {
        public final String command;   // "seat_heat", "seat_vent", or "wheel_heat"
        public final String target;    // seat name ("driver", ...) or "on" for wheel_heat
        public final String level;     // "low"/"medium"/"high" for seats, "" for wheel_heat

        public Action(String command, String target, String level) {
            this.command = command;
            this.target = target;
            this.level = level == null ? "" : level;
        }

        public String[] args() {
            return level.isEmpty() ? new String[] { target } : new String[] { target, level };
        }

        @Override
        public String toString() {
            return level.isEmpty() ? command + " " + target : command + " " + target + " " + level;
        }
    }

    public static final class Plan {
        public final List<Action> actions;
        public final String summary;

        public Plan(List<Action> actions, String summary) {
            this.actions = Collections.unmodifiableList(actions);
            this.summary = summary;
        }
    }

    /** Rejects NaN, Infinite, and DATC's -40.0 F raw-zero uninitialized wake sentinel. */
    public static boolean validAmbientF(float f) {
        return !Float.isNaN(f) && !Float.isInfinite(f) && Math.abs(f + 40f) > 0.05f && f >= -58f && f <= 158f;
    }

    /** Parses an outside_temp_f reading string ("28.5" or "-"), returning Float.NaN when unavailable or sentinel. */
    public static float parseAmbientF(String text) {
        if (text == null || text.isEmpty() || "-".equals(text)) return Float.NaN;
        try {
            float f = Float.parseFloat(text.trim());
            return validAmbientF(f) ? f : Float.NaN;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /**
     * Evaluates cold-start heat, steering wheel heat, and summer ventilation targets for a drive,
     * skipping any seat or wheel already active from Bluelink remote climate or manual driver input.
     */
    public static Plan evaluateClimate(ComfortConfig cfg, float tempF, Map<String, String> readings) {
        if (!cfg.climateEnabled || !validAmbientF(tempF)) {
            return new Plan(Collections.<Action>emptyList(), "Climate disabled or invalid ambient temp");
        }
        List<Action> out = new ArrayList<Action>();
        List<String> notes = new ArrayList<String>();

        int driverHeat = 0;
        if (tempF <= cfg.heatHighF) driverHeat = 3;
        else if (tempF <= cfg.heatMedF) driverHeat = 2;
        else if (tempF <= cfg.heatLowF) driverHeat = 1;

        if (driverHeat > 0) {
            addSeatAction(out, notes, readings, "seat_heat", "driver", driverHeat, false);
            addSeatAction(out, notes, readings, "seat_heat", "passenger",
                    followerLevel(driverHeat, cfg.passengerPolicy), false);
            addSeatAction(out, notes, readings, "seat_heat", "rear_left",
                    followerLevel(driverHeat, cfg.rearLeftPolicy), true);
            addSeatAction(out, notes, readings, "seat_heat", "rear_right",
                    followerLevel(driverHeat, cfg.rearRightPolicy), true);
        } else if (cfg.ventEnabled && tempF >= cfg.ventLowF) {
            int driverVent = tempF >= cfg.ventHighF ? 3 : (tempF >= cfg.ventMedF ? 2 : 1);
            addSeatAction(out, notes, readings, "seat_vent", "driver", driverVent, false);
            addSeatAction(out, notes, readings, "seat_vent", "passenger",
                    followerLevel(driverVent, cfg.passengerPolicy), false);
        }

        if (cfg.wheelEnabled && tempF <= cfg.wheelF) {
            String wh = readings == null ? null : readings.get("wheel_heat");
            if ("1".equals(wh)) {
                notes.add("wheel already on");
            } else {
                out.add(new Action("wheel_heat", "on", ""));
                notes.add("wheel on");
            }
        }

        String summary = notes.isEmpty()
                ? String.format("No climate needed at %.1f F", tempF)
                : String.format("%.1f F: %s", tempF, join(notes));
        return new Plan(out, summary);
    }

    public static int followerLevel(int driverLevel, String policy) {
        if (driverLevel <= 0 || ComfortConfig.POLICY_OFF.equals(policy)) return 0;
        if (ComfortConfig.POLICY_MATCH.equals(policy)) return driverLevel;
        if (ComfortConfig.POLICY_LOWER.equals(policy)) return Math.max(0, driverLevel - 1);
        return 0;
    }

    /** Fallback level when a 2-step rear seat refuses "medium": drops to "low". */
    public static String fallbackTwoStep(String level) {
        return "medium".equals(level) ? "low" : level;
    }

    private static void addSeatAction(
            List<Action> out,
            List<String> notes,
            Map<String, String> readings,
            String cmd,
            String seat,
            int level,
            boolean rear) {
        if (level <= 0) return;
        if (isSeatAlreadyActive(readings, seat)) {
            notes.add(seat + " kept (already active)");
            return;
        }
        String lvl = levelName(level);
        out.add(new Action(cmd, seat, lvl));
        notes.add(seat + ("seat_vent".equals(cmd) ? " vent " : " heat ") + lvl + (rear ? "" : ""));
    }

    /** BCM seat_state: 2 is off, 3-5 ventilation, 6-8 heat; 0/15/- unknown. */
    public static boolean isSeatAlreadyActive(Map<String, String> readings, String seat) {
        if (readings == null) return false;
        String raw = readings.get("seat_state." + seat);
        if (raw != null && !"-".equals(raw)) {
            try {
                int st = Integer.parseInt(raw.trim());
                if (st >= 3 && st <= 8) return true;
            } catch (NumberFormatException ignored) {}
        }
        String h = readings.get("seat_heat." + seat);
        if (h != null && ("1".equals(h) || "2".equals(h) || "3".equals(h))) return true;
        String v = readings.get("seat_vent." + seat);
        return v != null && ("1".equals(v) || "2".equals(v) || "3".equals(v));
    }

    public static String levelName(int level) {
        return level >= 3 ? "high" : (level == 2 ? "medium" : "low");
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /**
     * Tracks EV_RDY drive sessions across cold boots, parked power-cycles, and QuickBoot STR suspends.
     */
    public static final class DriveTracker {
        private boolean appliedThisDrive;
        private int offTicks;
        private long lastSleepGap = Long.MIN_VALUE;

        public synchronized void onSuspendCheck(long sleepGapMs) {
            if (lastSleepGap != Long.MIN_VALUE && sleepGapMs - lastSleepGap >= 5000L) {
                appliedThisDrive = false;
                offTicks = 0;
            }
            lastSleepGap = sleepGapMs;
        }

        /** Returns true when a new EV_RDY drive needs auto-climate evaluation. */
        public synchronized boolean shouldTrigger(String hvReady) {
            if ("1".equals(hvReady)) {
                offTicks = 0;
                return !appliedThisDrive;
            }
            if ("0".equals(hvReady)) {
                offTicks++;
                if (offTicks >= 2) {
                    appliedThisDrive = false;
                }
            }
            return false;
        }

        public synchronized void markCompleted() {
            appliedThisDrive = true;
        }

        public synchronized void reset() {
            appliedThisDrive = false;
            offTicks = 0;
        }
    }

    /**
     * Tracks startup safety notice dismissal across cold boots, SCREEN_OFF/SCREEN_ON, and QuickBoot STR suspends.
     */
    public static final class WelcomeTracker {
        private boolean pending = true;   // armed on initial cold boot
        private boolean screenOn = true;
        private long wakeAtMs;
        private long lastSleepGap = Long.MIN_VALUE;

        public WelcomeTracker(long nowMs) {
            this.wakeAtMs = nowMs;
        }

        public synchronized void onScreenOff() {
            screenOn = false;
            pending = true;
        }

        public synchronized void onScreenOn(long nowMs) {
            if (!screenOn || pending) {
                wakeAtMs = nowMs;
            }
            screenOn = true;
            if (!pending) pending = true;
        }

        public synchronized void onSuspendCheck(long sleepGapMs, long nowMs) {
            if (lastSleepGap != Long.MIN_VALUE && sleepGapMs - lastSleepGap >= 5000L) {
                pending = true;
                screenOn = true;
                wakeAtMs = nowMs;
            }
            lastSleepGap = sleepGapMs;
        }

        public synchronized boolean readyToDismiss(long nowMs, int delayMs) {
            return pending && screenOn && (nowMs - wakeAtMs >= delayMs);
        }

        public synchronized void markDone() {
            pending = false;
        }
    }
}
