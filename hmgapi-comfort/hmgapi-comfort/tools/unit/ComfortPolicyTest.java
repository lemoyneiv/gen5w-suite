package dev.lemoyne.comfort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Off-car unit tests for ComfortPolicy: multi-tier winter heat, summer ventilation,
 * follower seat policies, 2-step rear seat fallback, Bluelink active preservation,
 * DATC -40 F wake sentinel filtering, and QuickBoot session trackers.
 */
public final class ComfortPolicyTest {
    private static int fail;

    private static void check(String what, Object want, Object got) {
        String w = String.valueOf(want), g = String.valueOf(got);
        if (w.equals(g)) {
            System.out.println("ok    " + what);
        } else {
            System.out.println("FAIL  " + what + ": wanted [" + w + "], got [" + g + "]");
            fail = 1;
        }
    }

    private static String actions(List<ComfortPolicy.Action> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(list.get(i).toString());
        }
        return sb.toString();
    }

    private static Map<String, String> state(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    public static void main(String[] args) {
        ComfortConfig def = ComfortConfig.defaults();

        // 1. Sentinel filtering
        check("rejects -40.0 F DATC sentinel", true, Float.isNaN(ComfortPolicy.parseAmbientF("-40.0")));
        check("rejects - dash", true, Float.isNaN(ComfortPolicy.parseAmbientF("-")));
        check("accepts 24.5 F", "24.5", ComfortPolicy.parseAmbientF("24.5"));

        // 2. Multi-tier winter heating (driver + wheel)
        ComfortPolicy.Plan p20 = ComfortPolicy.evaluateClimate(def, 20.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("20 F -> driver high + wheel on", "seat_heat driver high | wheel_heat on", actions(p20.actions));

        ComfortPolicy.Plan p34 = ComfortPolicy.evaluateClimate(def, 34.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("34 F -> driver medium + wheel on", "seat_heat driver medium | wheel_heat on", actions(p34.actions));

        ComfortPolicy.Plan p48 = ComfortPolicy.evaluateClimate(def, 48.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("48 F -> driver low only (above 45 F wheel threshold)", "seat_heat driver low", actions(p48.actions));

        ComfortPolicy.Plan p62 = ComfortPolicy.evaluateClimate(def, 62.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("62 F mild -> no actions", "", actions(p62.actions));

        // 3. Follower seat policies (match vs 1 step lower) + 2-step rear fallback
        ComfortConfig fam = new ComfortConfig(
                true, 800, true, 25, 38, 50, true, 45,
                ComfortConfig.POLICY_MATCH, ComfortConfig.POLICY_LOWER, ComfortConfig.POLICY_MATCH,
                true, 75, 85, 95, false);
        ComfortPolicy.Plan pfam = ComfortPolicy.evaluateClimate(fam, 22.0f, state());
        check("22 F family policies (match pass, -1 rear_left, match rear_right)",
                "seat_heat driver high | seat_heat passenger high | seat_heat rear_left medium | seat_heat rear_right high | wheel_heat on",
                actions(pfam.actions));
        check("2-step rear fallback maps medium -> low", "low", ComfortPolicy.fallbackTwoStep("medium"));
        check("2-step rear fallback preserves high", "high", ComfortPolicy.fallbackTwoStep("high"));

        // 4. Summer seat ventilation (front seats only, rear never ventilated)
        ComfortConfig passLower = new ComfortConfig(
                true, 800, true, 25, 38, 50, true, 45,
                ComfortConfig.POLICY_LOWER, ComfortConfig.POLICY_MATCH, ComfortConfig.POLICY_MATCH,
                true, 75, 85, 95, false);
        ComfortPolicy.Plan p78 = ComfortPolicy.evaluateClimate(passLower, 78.0f, state());
        check("78 F -> driver vent low (passenger -1 = off)", "seat_vent driver low", actions(p78.actions));

        ComfortPolicy.Plan p88 = ComfortPolicy.evaluateClimate(fam, 88.0f, state());
        check("88 F -> driver + passenger vent medium", "seat_vent driver medium | seat_vent passenger medium", actions(p88.actions));

        ComfortPolicy.Plan p98 = ComfortPolicy.evaluateClimate(fam, 98.0f, state());
        check("98 F -> driver + passenger vent high", "seat_vent driver high | seat_vent passenger high", actions(p98.actions));

        // 5. Bluelink / manual climate preservation
        ComfortPolicy.Plan pBlue = ComfortPolicy.evaluateClimate(fam, 18.0f,
                state("seat_state.driver", "8", "seat_state.passenger", "2", "wheel_heat", "1"));
        check("Bluelink active driver & wheel preserved, passenger still warmed",
                "seat_heat passenger high | seat_heat rear_left medium | seat_heat rear_right high",
                actions(pBlue.actions));

        // 6. DriveTracker across cold boot, drive off, and QuickBoot STR resume
        ComfortPolicy.DriveTracker dt = new ComfortPolicy.DriveTracker();
        dt.onSuspendCheck(1000L);
        check("cold boot EV_RDY=1 triggers", true, dt.shouldTrigger("1"));
        dt.markCompleted();
        check("same drive EV_RDY=1 does not re-trigger", false, dt.shouldTrigger("1"));
        check("single EV_RDY=0 glitch does not reset", false, dt.shouldTrigger("0"));
        check("still completed after 1 glitch", false, dt.shouldTrigger("1"));
        dt.shouldTrigger("0");
        dt.shouldTrigger("0");
        check("sustained EV_RDY=0 resets for next drive", true, dt.shouldTrigger("1"));
        dt.markCompleted();
        dt.onSuspendCheck(601000L);   // 10 min QuickBoot STR sleep
        check("QuickBoot STR sleep gap re-arms drive trigger", true, dt.shouldTrigger("1"));

        // 7. WelcomeTracker across cold boot, SCREEN_OFF/SCREEN_ON, and QuickBoot STR resume
        ComfortPolicy.WelcomeTracker wt = new ComfortPolicy.WelcomeTracker(1000L);
        wt.onSuspendCheck(500L, 1000L);
        check("before delay -> false", false, wt.readyToDismiss(1500L, 800));
        check("after 800 ms delay -> true", true, wt.readyToDismiss(1800L, 800));
        wt.markDone();
        check("done -> false", false, wt.readyToDismiss(3000L, 800));
        wt.onScreenOff();
        check("while screen off -> false", false, wt.readyToDismiss(5000L, 800));
        wt.onScreenOn(6000L);
        check("screen on before delay -> false", false, wt.readyToDismiss(6500L, 800));
        check("screen on after delay -> true", true, wt.readyToDismiss(6800L, 800));
        wt.markDone();
        wt.onSuspendCheck(300500L, 10000L);   // QuickBoot resume without broadcast
        check("QuickBoot STR resume re-arms welcome dismiss", true, wt.readyToDismiss(10800L, 800));

        System.exit(fail);
    }
}
