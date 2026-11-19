package hmgapi.mods.wireless;

/**
 * Pure Java startup focus policy between Bluetooth Audio
 * ({@code com.daudio.av.app.btaudio.ui}, ModeType 54, MICOM audio mode 14) and
 * Phone Projection (CarPlay ModeType 115, Android Auto ModeType 116).
 *
 * Scope is strictly bounded to the projection startup / connect handoff:
 *  - While wireless projection is shaking hands ("connecting", up to 15 s) and
 *    during a short settling window right after session start ("connected", up
 *    to 8 s), Bluetooth Audio media focus requests and self-UI launches are
 *    declined so an automatic A2DP/AVRCP resume at boot cannot steal focus.
 *  - Once projection reaches the foreground, after the one-shot startup reclaim
 *    fires, or once the 8 s startup window expires, Bluetooth Audio is never
 *    blocked or overridden: opening Bluetooth Audio from the media menu or
 *    streaming from a passenger's phone works normally throughout the session.
 *  - HFP voice-call and ring streams are never blocked.
 *  - Startup reclaim never hijacks non-Bluetooth sources (FM/AM/SiriusXM/USB)
 *    and defers while any reverse, driving rear-view, or surround-view camera
 *    is active.
 */
public final class FocusPolicy {
    public static final int SOURCE_NONE = 0;
    public static final int SOURCE_CARPLAY = 1;
    public static final int SOURCE_ANDROID_AUTO = 2;

    public static final int ACTION_NONE = 0;
    public static final int ACTION_RECLAIM_CARPLAY = 1;
    public static final int ACTION_RECLAIM_ANDROID_AUTO = 2;
    public static final int ACTION_DEFER_CAMERA = 3;

    /** Android AudioManager.STREAM_VOICE_CALL (0) and STREAM_RING (2). */
    public static final int STREAM_VOICE_CALL = 0;
    public static final int STREAM_RING = 2;

    /** Gen5W ModeManager ModeType constants. */
    public static final int MODE_NONE = 0;
    public static final int MODE_BT_AUDIO = 54;
    public static final int MODE_CARPLAY = 115;
    public static final int MODE_ANDROID_AUTO = 116;

    /** Gen5W MicomAudioManager audio mode for Bluetooth Audio. */
    public static final int MICOM_BT_AUDIO = 14;

    /** Bounded lease while wireless SPP/GAL startup handshake is in flight. */
    public static final long CONNECTING_LEASE_MS = 15_000L;
    /** Short post-connect startup window after CarPlay/AA reports connected. */
    public static final long CONNECT_WINDOW_MS = 8_000L;
    /** Hard ceiling on camera-deferred startup reclaim (e.g. long reverse maneuver). */
    public static final long MAX_CAMERA_DEFER_MS = 60_000L;

    public static final String PKG_BTAUDIO = "com.daudio.av.app.btaudio.ui";
    public static final String SHORTCUT_CARPLAY = "com.hkmc.intent.action.DIPO_CUSTOM_SHORTCUT";
    public static final String SHORTCUT_ANDROID_AUTO = "com.hkmc.intent.action.GPM_CUSTOM_SHORTCUT";

    /**
     * Explicit camera and surround-view package names on Gen5W Hyundai/Kia units,
     * paired with PGS reverse / rear-view / front-view status from HMG-API hook points.
     */
    private static final String[] CAMERA_PACKAGES = {
        "com.hkmc.app.rearcamera",
        "com.daudio.app.rearcamera",
        "com.hkmc.app.avm",
        "com.daudio.app.avm",
        "com.hkmc.avm.ui",
        "com.lge.app.avm"
    };

    private int activeSource = SOURCE_NONE;
    private long connectingUntil;
    private long connectStartedAt;
    private long connectWindowUntil;
    private boolean reclaimPending;
    private boolean cameraDeferring;
    private boolean sawProjectionForeground;
    private boolean preemptedBtFocus;
    private boolean cpConnected;
    private boolean aaConnected;

    public static int parseSource(String s) {
        if ("cp".equals(s)) return SOURCE_CARPLAY;
        if ("aa".equals(s)) return SOURCE_ANDROID_AUTO;
        return SOURCE_NONE;
    }

    public static String shortcutForAction(int action) {
        if (action == ACTION_RECLAIM_CARPLAY) return SHORTCUT_CARPLAY;
        if (action == ACTION_RECLAIM_ANDROID_AUTO) return SHORTCUT_ANDROID_AUTO;
        return null;
    }

    public static boolean isCameraActive(boolean pgsCameraActive, String topPackage) {
        if (pgsCameraActive) return true;
        if (topPackage == null) return false;
        for (String pkg : CAMERA_PACKAGES) {
            if (pkg.equals(topPackage)) return true;
        }
        return false;
    }

    /**
     * Records a projection lifecycle signal ("connecting", "connected",
     * "aborted", "disconnected").
     *
     * @return true when Bluetooth Audio should immediately yield any media
     *         AudioFocus listeners it grabbed earlier during boot/connect
     */
    public synchronized boolean onProjectionEvent(int source, String state, long nowMs) {
        if ((source != SOURCE_CARPLAY && source != SOURCE_ANDROID_AUTO) || state == null) {
            return false;
        }
        if ("connecting".equals(state)) {
            if (isSourceConnected(source)) return false;
            activeSource = source;
            connectingUntil = nowMs + CONNECTING_LEASE_MS;
            sawProjectionForeground = false;
            return true;
        }
        if ("connected".equals(state)) {
            if (isSourceConnected(source)) return false;
            setSourceConnected(source, true);
            activeSource = source;
            connectingUntil = 0L;
            connectStartedAt = nowMs;
            connectWindowUntil = nowMs + CONNECT_WINDOW_MS;
            reclaimPending = true;
            cameraDeferring = false;
            sawProjectionForeground = false;
            return true;
        }
        if ("aborted".equals(state)) {
            if (activeSource == source && !isSourceConnected(source)) {
                clearStartupWindow();
                activeSource = otherConnectedSource();
            }
            return false;
        }
        if ("disconnected".equals(state)) {
            setSourceConnected(source, false);
            if (activeSource == source) {
                clearStartupWindow();
                activeSource = otherConnectedSource();
            }
            return false;
        }
        return false;
    }

    public synchronized void noteBtFocusPreempted() {
        preemptedBtFocus = true;
    }

    /** True only while a wireless handshake or initial post-connect window is active. */
    public synchronized boolean inStartupWindow(long nowMs) {
        return activeSource != SOURCE_NONE && (nowMs < connectingUntil || nowMs < connectWindowUntil);
    }

    /** Updates foreground tracking when ModeManager reports the current mode. */
    public synchronized void observeMode(int currentMode) {
        if ((activeSource == SOURCE_CARPLAY && currentMode == MODE_CARPLAY)
                || (activeSource == SOURCE_ANDROID_AUTO && currentMode == MODE_ANDROID_AUTO)) {
            sawProjectionForeground = true;
            reclaimPending = false;
            cameraDeferring = false;
        }
    }

    /**
     * Called when Bluetooth Audio's own Activity resumes. Self-launches from
     * inside HKMC_BtAudio are already blocked during the startup window, so an
     * Activity resume after projection reached the foreground (or after startup
     * reclaim settled) means the driver explicitly chose Bluetooth Audio. Ends
     * the startup window immediately and never schedules a reclaim.
     */
    public synchronized void onBtScreenOpened(int currentMode, long nowMs) {
        observeMode(currentMode);
        if (sawProjectionForeground || (!reclaimPending && connectWindowUntil > 0L && nowMs >= connectStartedAt)) {
            clearStartupWindow();
        }
    }

    /**
     * Declines Bluetooth Audio media focus requests strictly during the bounded
     * startup/connect window. Mid-session requests (driver switching to BT Audio
     * or a passenger phone streaming over Bluetooth) are never blocked.
     */
    public synchronized boolean shouldBlockBtAudioFocus(int streamType, int mode, long nowMs) {
        if (streamType == STREAM_VOICE_CALL || streamType == STREAM_RING) return false;
        if (mode != 0 && mode != MODE_BT_AUDIO) return false;
        return inStartupWindow(nowMs);
    }

    /** Suppresses HKMC_BtAudio self-launching its UI strictly during startup/connect. */
    public synchronized boolean shouldBlockBtActivityLaunch(String targetPackage, long nowMs) {
        return PKG_BTAUDIO.equals(targetPackage) && inStartupWindow(nowMs);
    }

    /**
     * Evaluates whether a newly connected projection session should reclaim focus
     * from Bluetooth Audio once during the startup window.
     */
    public synchronized int decideReclaim(boolean liveCp, boolean liveAa,
                                          int currentMode, int micomAudioMode,
                                          boolean pgsCameraActive, String topPackage,
                                          long nowMs) {
        observeMode(currentMode);
        long deadline = cameraDeferring ? connectStartedAt + MAX_CAMERA_DEFER_MS : connectWindowUntil;
        if (!reclaimPending || activeSource == SOURCE_NONE || nowMs >= deadline) {
            reclaimPending = false;
            cameraDeferring = false;
            return ACTION_NONE;
        }
        boolean live = (activeSource == SOURCE_CARPLAY && liveCp)
                || (activeSource == SOURCE_ANDROID_AUTO && liveAa);
        if (!live) return ACTION_NONE;

        if (isCameraActive(pgsCameraActive, topPackage)) {
            cameraDeferring = true;
            return ACTION_DEFER_CAMERA;
        }

        boolean btHolding = currentMode == MODE_BT_AUDIO
                || micomAudioMode == MICOM_BT_AUDIO
                || PKG_BTAUDIO.equals(topPackage)
                || (preemptedBtFocus && currentMode == MODE_NONE);

        reclaimPending = false;
        cameraDeferring = false;
        preemptedBtFocus = false;
        if (!btHolding) {
            return ACTION_NONE;
        }
        return activeSource == SOURCE_CARPLAY ? ACTION_RECLAIM_CARPLAY : ACTION_RECLAIM_ANDROID_AUTO;
    }

    private boolean isSourceConnected(int source) {
        return source == SOURCE_CARPLAY ? cpConnected : source == SOURCE_ANDROID_AUTO && aaConnected;
    }

    private void setSourceConnected(int source, boolean connected) {
        if (source == SOURCE_CARPLAY) cpConnected = connected;
        else if (source == SOURCE_ANDROID_AUTO) aaConnected = connected;
    }

    private int otherConnectedSource() {
        if (cpConnected) return SOURCE_CARPLAY;
        if (aaConnected) return SOURCE_ANDROID_AUTO;
        return SOURCE_NONE;
    }

    private void clearStartupWindow() {
        connectingUntil = 0L;
        connectStartedAt = 0L;
        connectWindowUntil = 0L;
        reclaimPending = false;
        cameraDeferring = false;
        sawProjectionForeground = false;
        preemptedBtFocus = false;
    }
}
