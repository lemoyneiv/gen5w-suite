package hmgapi.mods.wireless;

import java.util.Locale;

/**
 * Pure policy and state machine for arbitrating audio, mode, and screen focus between
 * Bluetooth Audio (com.daudio.av.app.btaudio.ui, ModeType 54, MICOM audio mode 14)
 * and Phone Projection (CarPlay ModeType 115 / MICOM 22; Android Auto ModeType 116 / MICOM 23).
 *
 * Kept free of Android framework classes so tools/focus/test.sh exercises all startup race
 * windows, camera deferrals, radio preservation, and disconnect transitions on the host JVM.
 */
public final class FocusPolicy {
    public static final int SOURCE_NONE = 0;
    public static final int SOURCE_CARPLAY = 1;
    public static final int SOURCE_ANDROID_AUTO = 2;

    public static final int MODE_NONE = 0;
    public static final int MODE_BT_AUDIO = 54;
    public static final int MODE_CARPLAY = 115;
    public static final int MODE_ANDROID_AUTO = 116;

    public static final int MICOM_BT_AUDIO = 14;
    public static final int MICOM_CARPLAY = 22;
    public static final int MICOM_ANDROID_AUTO = 23;

    public static final int STREAM_VOICE_CALL = 0;
    public static final int STREAM_RING = 2;

    /** Max duration to hold off Bluetooth Audio while wireless CP/AA negotiates BT -> Wi-Fi. */
    public static final long CONNECTING_LEASE_MS = 15_000L;
    /** Grace window after "connected" broadcast before live DiPOManager/GpmManager query is sole truth. */
    public static final long CONNECTED_GRACE_MS = 10_000L;
    /** Minimum spacing between shortcut broadcasts triggered by repeated Activity resumes. */
    public static final long RECLAIM_COOLDOWN_MS = 1_500L;

    public static final int ACTION_NONE = 0;
    public static final int ACTION_DEFER_CAMERA = 1;
    public static final int ACTION_RECLAIM_CARPLAY = 2;
    public static final int ACTION_RECLAIM_ANDROID_AUTO = 3;

    public static final String PKG_BTAUDIO = "com.daudio.av.app.btaudio.ui";
    public static final String SHORTCUT_CARPLAY = "com.hkmc.intent.action.DIPO_CUSTOM_SHORTCUT";
    public static final String SHORTCUT_ANDROID_AUTO = "com.hkmc.intent.action.GPM_CUSTOM_SHORTCUT";

    private long cpConnectingUntil;
    private long aaConnectingUntil;
    private long cpConnectedGraceUntil;
    private long aaConnectedGraceUntil;
    private boolean preemptedBtFocus;
    private long lastReclaimAt = -RECLAIM_COOLDOWN_MS;

    public static int parseSource(String source) {
        if ("cp".equals(source)) return SOURCE_CARPLAY;
        if ("aa".equals(source)) return SOURCE_ANDROID_AUTO;
        return SOURCE_NONE;
    }

    /**
     * Records a projection lifecycle event ("connecting", "connected", "aborted", "disconnected").
     * Returns true when Bluetooth Audio must immediately abandon any held AudioFocus listeners.
     */
    public synchronized boolean onProjectionEvent(int source, String state, long nowMs) {
        if (source != SOURCE_CARPLAY && source != SOURCE_ANDROID_AUTO) return false;
        if ("connecting".equals(state)) {
            if (source == SOURCE_CARPLAY) cpConnectingUntil = nowMs + CONNECTING_LEASE_MS;
            else aaConnectingUntil = nowMs + CONNECTING_LEASE_MS;
            return true;
        }
        if ("connected".equals(state)) {
            if (source == SOURCE_CARPLAY) {
                cpConnectingUntil = 0L;
                cpConnectedGraceUntil = nowMs + CONNECTED_GRACE_MS;
            } else {
                aaConnectingUntil = 0L;
                aaConnectedGraceUntil = nowMs + CONNECTED_GRACE_MS;
            }
            return true;
        }
        if ("aborted".equals(state) || "disconnected".equals(state)) {
            if (source == SOURCE_CARPLAY) {
                cpConnectingUntil = 0L;
                cpConnectedGraceUntil = 0L;
            } else {
                aaConnectingUntil = 0L;
                aaConnectedGraceUntil = 0L;
            }
            if ("disconnected".equals(state) && activeOrConnectingSource(false, false, nowMs) == SOURCE_NONE) {
                preemptedBtFocus = false;
            }
            return false;
        }
        return false;
    }

    /** Marks that Bluetooth Audio held live focus when Projection preempted it. */
    public synchronized void noteBtFocusPreempted() {
        preemptedBtFocus = true;
    }

    public synchronized int activeOrConnectingSource(boolean liveCp, boolean liveAa, long nowMs) {
        if (liveCp || nowMs < cpConnectedGraceUntil || nowMs < cpConnectingUntil) return SOURCE_CARPLAY;
        if (liveAa || nowMs < aaConnectedGraceUntil || nowMs < aaConnectingUntil) return SOURCE_ANDROID_AUTO;
        return SOURCE_NONE;
    }

    public synchronized int connectedSource(boolean liveCp, boolean liveAa, long nowMs) {
        if (liveCp || nowMs < cpConnectedGraceUntil) return SOURCE_CARPLAY;
        if (liveAa || nowMs < aaConnectedGraceUntil) return SOURCE_ANDROID_AUTO;
        return SOURCE_NONE;
    }

    /**
     * Should Bluetooth Audio's requestAudioFocus call be denied right now?
     * Voice-call and ring streams are never blocked. Media focus (mode 54 or 3-arg mode 0)
     * is blocked whenever CarPlay or Android Auto is connected or in its startup handshake.
     */
    public synchronized boolean shouldBlockBtAudioFocus(int streamType, int mode, boolean liveCp, boolean liveAa, long nowMs) {
        if (streamType == STREAM_VOICE_CALL || streamType == STREAM_RING) return false;
        if (mode != 0 && mode != MODE_BT_AUDIO) return false;
        return activeOrConnectingSource(liveCp, liveAa, nowMs) != SOURCE_NONE;
    }

    /** Should Bluetooth Audio be prevented from launching its own UI activity right now? */
    public synchronized boolean shouldBlockBtActivityLaunch(String targetPackage, boolean liveCp, boolean liveAa, long nowMs) {
        if (!PKG_BTAUDIO.equals(targetPackage)) return false;
        return activeOrConnectingSource(liveCp, liveAa, nowMs) != SOURCE_NONE;
    }

    public static boolean isCameraForeground(String packageName) {
        if (packageName == null) return false;
        String v = packageName.toLowerCase(Locale.US);
        return v.contains("rearcamera") || v.contains(".camera") || v.contains(".avm") || v.contains("surroundview");
    }

    /**
     * Decides whether Projection should actively reclaim mode/screen from Bluetooth Audio.
     * Non-Bluetooth sources (FM/AM/SiriusXM/USB) are never disturbed; camera screens defer reclaim.
     */
    public synchronized int decideReclaim(boolean liveCp, boolean liveAa, int currentMode, int micomAudioMode,
            boolean btActivityResumed, String topPackage, long nowMs) {
        int src = connectedSource(liveCp, liveAa, nowMs);
        if (src == SOURCE_NONE) return ACTION_NONE;
        boolean btHolding = btActivityResumed
                || PKG_BTAUDIO.equals(topPackage)
                || currentMode == MODE_BT_AUDIO
                || micomAudioMode == MICOM_BT_AUDIO
                || (preemptedBtFocus && currentMode == MODE_NONE);
        if (!btHolding) {
            preemptedBtFocus = false;
            return ACTION_NONE;
        }
        if (isCameraForeground(topPackage)) {
            return ACTION_DEFER_CAMERA;
        }
        if (nowMs - lastReclaimAt < RECLAIM_COOLDOWN_MS) {
            return ACTION_NONE;
        }
        lastReclaimAt = nowMs;
        preemptedBtFocus = false;
        return src == SOURCE_CARPLAY ? ACTION_RECLAIM_CARPLAY : ACTION_RECLAIM_ANDROID_AUTO;
    }

    public static String shortcutForAction(int action) {
        if (action == ACTION_RECLAIM_CARPLAY) return SHORTCUT_CARPLAY;
        if (action == ACTION_RECLAIM_ANDROID_AUTO) return SHORTCUT_ANDROID_AUTO;
        return null;
    }
}
