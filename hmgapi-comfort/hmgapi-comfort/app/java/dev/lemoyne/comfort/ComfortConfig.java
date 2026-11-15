package dev.lemoyne.comfort;

/**
 * Immutable configuration for Comfort & Startup, using integer thresholds so
 * HMG-API's SettingsForm (Long.parseLong) and JSON round-tripping always agree.
 */
public final class ComfortConfig {
    public static final String POLICY_OFF = "off";
    public static final String POLICY_MATCH = "match";
    public static final String POLICY_LOWER = "lower";

    public final boolean welcomeDismiss;
    public final int welcomeDelayMs;
    public final boolean climateEnabled;
    public final int heatHighF;
    public final int heatMedF;
    public final int heatLowF;
    public final boolean wheelEnabled;
    public final int wheelF;
    public final String passengerPolicy;
    public final String rearLeftPolicy;
    public final String rearRightPolicy;
    public final boolean ventEnabled;
    public final int ventLowF;
    public final int ventMedF;
    public final int ventHighF;
    public final boolean toastOnApply;

    public ComfortConfig(
            boolean welcomeDismiss,
            int welcomeDelayMs,
            boolean climateEnabled,
            int heatHighF,
            int heatMedF,
            int heatLowF,
            boolean wheelEnabled,
            int wheelF,
            String passengerPolicy,
            String rearLeftPolicy,
            String rearRightPolicy,
            boolean ventEnabled,
            int ventLowF,
            int ventMedF,
            int ventHighF,
            boolean toastOnApply) {
        this.welcomeDismiss = welcomeDismiss;
        this.welcomeDelayMs = clamp(welcomeDelayMs, 0, 10000);
        this.climateEnabled = climateEnabled;
        int hh = clamp(heatHighF, -30, 80);
        int hm = Math.max(hh, clamp(heatMedF, -30, 85));
        int hl = Math.max(hm, clamp(heatLowF, -30, 90));
        this.heatHighF = hh;
        this.heatMedF = hm;
        this.heatLowF = hl;
        this.wheelEnabled = wheelEnabled;
        this.wheelF = clamp(wheelF, -30, 90);
        this.passengerPolicy = normPolicy(passengerPolicy);
        this.rearLeftPolicy = normPolicy(rearLeftPolicy);
        this.rearRightPolicy = normPolicy(rearRightPolicy);
        this.ventEnabled = ventEnabled;
        int vl = Math.max(hl + 5, clamp(ventLowF, 60, 120));
        int vm = Math.max(vl, clamp(ventMedF, 60, 125));
        int vh = Math.max(vm, clamp(ventHighF, 60, 130));
        this.ventLowF = vl;
        this.ventMedF = vm;
        this.ventHighF = vh;
        this.toastOnApply = toastOnApply;
    }

    public static ComfortConfig defaults() {
        return new ComfortConfig(
                true, 800,
                true, 25, 38, 50,
                true, 45,
                POLICY_OFF, POLICY_OFF, POLICY_OFF,
                true, 75, 85, 95,
                false);
    }

    public static String normPolicy(String p) {
        if (POLICY_MATCH.equals(p) || POLICY_LOWER.equals(p)) return p;
        return POLICY_OFF;
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
