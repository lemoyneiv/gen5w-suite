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
        return decideReclaim(liveCp, liveAa, currentMode, micomAudioMode, pgsCameraActive, topPackage, false, nowMs);
    }

    /**
     * Evaluates whether a newly connected projection session should reclaim focus
     * once during the startup window — either from boot-raced Bluetooth Audio, or
     * from ModeManager's involuntary FM Radio fallback after Pitch-Black Dark Standby
     * tore down an active Phone Projection session (verified via {@code projection-resume.json}).
     */
    public synchronized int decideReclaim(boolean liveCp, boolean liveAa,
                                          int currentMode, int micomAudioMode,
                                          boolean pgsCameraActive, String topPackage,
                                          boolean standbyProjectionResume,
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
        boolean shouldReclaim = btHolding
                || (standbyProjectionResume && currentMode != MODE_CARPLAY && currentMode != MODE_ANDROID_AUTO);

        reclaimPending = false;
        cameraDeferring = false;
        preemptedBtFocus = false;
        if (!shouldReclaim) {
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

    // ---- Parked Keep-Awake, Home Wi-Fi wlan0 ownership & Post-Standby Projection Resume ----

    public static final long LEASE_FRESHNESS_MS = 30_000L;
    public static final long PROJECTION_RESUME_LEASE_MS = 45_000L;
    public static final int WIFI_AP_STATE_ENABLED = 13;
    public static final int BT_STATE_ON = 12;

    public static String normalizeBtMac(String addr) {
        if (addr == null) return "";
        String s = addr.trim().toUpperCase(java.util.Locale.US);
        if (s.length() != 17 || "00:00:00:00:00:00".equals(s)) return "";
        for (int i = 0; i < 17; i++) {
            char c = s.charAt(i);
            if (i % 3 == 2) {
                if (c != ':') return "";
            } else {
                boolean hex = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
                if (!hex) return "";
            }
        }
        return s;
    }

    public static boolean isStandbyProjectionResumeActiveOnDevice(long nowElapsedMs) {
        String curBoot = readSmallUtf8(new java.io.File("/proc/sys/kernel/random/boot_id")).trim();
        String resumeJson = readSmallUtf8(new java.io.File("/data/hmgapi/public/projection-resume.json"));
        return isStandbyProjectionResumeActive(curBoot, nowElapsedMs, resumeJson);
    }

    public static int extractProjectionResumeModeOnDevice(long nowElapsedMs) {
        String curBoot = readSmallUtf8(new java.io.File("/proc/sys/kernel/random/boot_id")).trim();
        String resumeJson = readSmallUtf8(new java.io.File("/data/hmgapi/public/projection-resume.json"));
        return extractProjectionResumeMode(curBoot, nowElapsedMs, resumeJson);
    }

    public static String extractProjectionResumeBtAddrOnDevice(long nowElapsedMs) {
        String curBoot = readSmallUtf8(new java.io.File("/proc/sys/kernel/random/boot_id")).trim();
        String resumeJson = readSmallUtf8(new java.io.File("/data/hmgapi/public/projection-resume.json"));
        return extractProjectionResumeBtAddr(curBoot, nowElapsedMs, resumeJson);
    }

    public static int extractProjectionResumeMode(
            String curBootId,
            long nowElapsedMs,
            String resumeJson) {
        if (!isStandbyProjectionResumeActive(curBootId, nowElapsedMs, resumeJson)) {
            return MODE_NONE;
        }
        long preMode = extractJsonLong(resumeJson, "preMode", -1L);
        if (preMode == MODE_CARPLAY || preMode == MODE_ANDROID_AUTO) {
            return (int) preMode;
        }
        return MODE_NONE;
    }

    public static String extractProjectionResumeBtAddr(
            String curBootId,
            long nowElapsedMs,
            String resumeJson) {
        if (!isStandbyProjectionResumeActive(curBootId, nowElapsedMs, resumeJson)) {
            return "";
        }
        return normalizeBtMac(extractJsonString(resumeJson, "btAddr"));
    }

    public static boolean isStandbyProjectionResumeActive(
            String curBootId,
            long nowElapsedMs,
            String resumeJson) {
        if (resumeJson == null || resumeJson.isEmpty()) return false;
        long preMode = extractJsonLong(resumeJson, "preMode", -1L);
        if (preMode != MODE_CARPLAY && preMode != MODE_ANDROID_AUTO) {
            return false;
        }
        String boot = extractJsonString(resumeJson, "bootId");
        String cur = curBootId == null ? "" : curBootId.trim();
        if (!cur.isEmpty() && !boot.isEmpty() && !cur.equals(boot)) {
            return false;
        }
        long exp = extractJsonLong(resumeJson, "expiresAtElapsedMs", -1L);
        if (exp > 0L && nowElapsedMs >= exp) {
            return false;
        }
        long resumedAt = extractJsonLong(resumeJson, "resumedAtElapsedMs", -1L);
        if (resumedAt > 0L && nowElapsedMs > 0L && Math.abs(nowElapsedMs - resumedAt) > PROJECTION_RESUME_LEASE_MS) {
            return false;
        }
        return true;
    }

    /**
     * Per-boot RAM-only parked teardown and post-quick-stop wireless reconnect state
     * machine. Never persists phone addresses to Settings.Global or disk so switching
     * phones across trips or drivers is never locked out.
     */
    public static final class ParkSessionPolicy {
        public static final long BONJOUR_COOLDOWN_MS = 8_000L;
        public static final long AA_CONNECT_COOLDOWN_MS = 4_500L;
        public static final int MAX_AA_RETRIES = 2;

        private String lastAaPhone = "";
        private boolean aaUp;
        private boolean cpUp;
        private boolean hadWirelessCpThisBoot;
        private boolean parkedCycle;
        private boolean needsBonjourRestart;
        private long lastBonjourRestartAtMs;
        private long lastAaConnectAtMs;
        private int aaRetryCount;

        public synchronized void noteAaPhone(String addr) {
            String m = normalizeBtMac(addr);
            if (!m.isEmpty()) {
                lastAaPhone = m;
            }
        }

        public synchronized String lastAaPhone() {
            return lastAaPhone;
        }

        public synchronized void onAaConnected(String wirelessExplorerAddr) {
            noteAaPhone(wirelessExplorerAddr);
            aaUp = true;
            parkedCycle = false;
            aaRetryCount = 0;
        }

        public synchronized void onAaDisconnected(String wirelessExplorerAddr) {
            noteAaPhone(wirelessExplorerAddr);
            aaUp = false;
        }

        public synchronized void onCpStarted(int transport) {
            cpUp = true;
            parkedCycle = false;
            needsBonjourRestart = false;
            if (transport == 1) {
                hadWirelessCpThisBoot = true;
            }
        }

        public synchronized void onCpFinalized() {
            cpUp = false;
        }

        /**
         * @param isGpm true in HKMC_GPM (returns true when ByeByeRequest(1) should be sent),
         *              false in HKMC_DiPO (returns true when disconnectCarPlay() should be called)
         */
        public synchronized boolean onParkOrShutdown(boolean isGpm) {
            parkedCycle = true;
            aaRetryCount = 0;
            if (isGpm) {
                return aaUp;
            }
            if (cpUp) {
                needsBonjourRestart = true;
                return true;
            }
            return false;
        }

        public synchronized void onWakeOrUnpark() {
            parkedCycle = true;
            aaRetryCount = 0;
        }

        public synchronized boolean shouldRestartCarPlayBonjour(
                int apState,
                int btState,
                boolean homeOrParkedHold,
                int resumeMode,
                long nowMs) {
            if (cpUp || aaUp || homeOrParkedHold) return false;
            if (apState != WIFI_AP_STATE_ENABLED || btState != BT_STATE_ON) return false;
            boolean needed = needsBonjourRestart
                    || (parkedCycle && hadWirelessCpThisBoot)
                    || resumeMode == MODE_CARPLAY;
            if (!needed) return false;
            if (lastBonjourRestartAtMs > 0L && nowMs - lastBonjourRestartAtMs < BONJOUR_COOLDOWN_MS) {
                return false;
            }
            needsBonjourRestart = false;
            parkedCycle = false;
            lastBonjourRestartAtMs = Math.max(1L, nowMs);
            return true;
        }

        public synchronized String selectAaReconnectTarget(
                int apState,
                int btState,
                boolean homeOrParkedHold,
                int resumeMode,
                String resumeBtAddr,
                String aclConnectedAddr,
                long nowMs) {
            if (aaUp || cpUp || homeOrParkedHold) return "";
            if (apState != WIFI_AP_STATE_ENABLED || btState != BT_STATE_ON) return "";

            String candidate = "";
            if (!lastAaPhone.isEmpty()) {
                candidate = lastAaPhone;
            } else if (resumeMode == MODE_ANDROID_AUTO) {
                candidate = normalizeBtMac(resumeBtAddr);
            }
            if (candidate.isEmpty()) return "";

            if (aclConnectedAddr != null && !aclConnectedAddr.isEmpty()) {
                String acl = normalizeBtMac(aclConnectedAddr);
                if (acl.isEmpty() || !acl.equals(candidate)) {
                    return "";
                }
                if (lastAaConnectAtMs > 0L && nowMs - lastAaConnectAtMs < AA_CONNECT_COOLDOWN_MS) {
                    return "";
                }
                lastAaConnectAtMs = Math.max(1L, nowMs);
                parkedCycle = false;
                aaRetryCount = 0;
                return candidate;
            }

            if (!parkedCycle && resumeMode != MODE_ANDROID_AUTO) {
                return "";
            }
            if (aaRetryCount >= MAX_AA_RETRIES) {
                parkedCycle = false;
                return "";
            }
            if (lastAaConnectAtMs > 0L && nowMs - lastAaConnectAtMs < AA_CONNECT_COOLDOWN_MS) {
                return "";
            }
            aaRetryCount++;
            if (aaRetryCount >= MAX_AA_RETRIES) {
                parkedCycle = false;
            }
            lastAaConnectAtMs = Math.max(1L, nowMs);
            return candidate;
        }
    }

    public static boolean isHomeWifiOrParkedLeaseActiveOnDevice(long nowElapsedMs, String wpaSupplicantProp) {
        String curBoot = readSmallUtf8(new java.io.File("/proc/sys/kernel/random/boot_id")).trim();
        String wifiJson = readSmallUtf8(new java.io.File("/data/hmgapi/public/wifi.json"));
        String openlinkLease = readSmallUtf8(new java.io.File("/data/hmgapi/public/openlink-keepawake.json"));
        String hmgLease = readSmallUtf8(new java.io.File("/data/hmgapi/public/keepawake.json"));
        return shouldSuppressBridgeForHomeWifiOrPark(
                curBoot, nowElapsedMs, wpaSupplicantProp, wifiJson, openlinkLease, hmgLease);
    }

    public static boolean shouldSuppressBridgeForHomeWifiOrPark(
            String curBootId,
            long nowElapsedMs,
            String wpaSupplicantProp,
            String wifiJson,
            String openlinkLeaseJson,
            String hmgLeaseJson) {
        if (isActiveFreshLeaseJson(openlinkLeaseJson, curBootId, nowElapsedMs)) {
            return true;
        }
        if (isActiveFreshLeaseJson(hmgLeaseJson, curBootId, nowElapsedMs)) {
            return true;
        }
        String st = extractJsonString(wifiJson, "state");
        if ("up".equalsIgnoreCase(st)
                || "searching".equalsIgnoreCase(st)
                || "nolease".equalsIgnoreCase(st)) {
            String wpa = wpaSupplicantProp == null ? "" : wpaSupplicantProp.trim();
            if ("running".equalsIgnoreCase(wpa)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isActiveFreshLeaseJson(String json, String curBootId, long nowElapsedMs) {
        if (json == null || json.isEmpty()) return false;
        if (!extractJsonBoolean(json, "active")) return false;
        if (extractJsonBoolean(json, "cutBy12vFloor")) return false;
        String boot = extractJsonString(json, "bootId");
        String cur = curBootId == null ? "" : curBootId.trim();
        if (!cur.isEmpty() && !boot.isEmpty() && !cur.equals(boot)) {
            return false;
        }
        long upd = extractJsonLong(json, "updatedAtElapsedMs", -1L);
        if (upd > 0L && nowElapsedMs > 0L && Math.abs(nowElapsedMs - upd) > LEASE_FRESHNESS_MS) {
            return false;
        }
        if (!extractJsonBoolean(json, "unlimited")) {
            long exp = extractJsonLong(json, "expiresAtElapsedMs", -1L);
            if (exp > 0L && nowElapsedMs >= exp) {
                return false;
            }
        }
        return true;
    }

    private static String extractJsonString(String json, String key) {
        if (json == null || json.isEmpty()) return "";
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return "";
        int colon = json.indexOf(':', k + key.length() + 2);
        if (colon < 0) return "";
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        return json.substring(q1 + 1, q2).trim();
    }

    private static boolean extractJsonBoolean(String json, String key) {
        if (json == null || json.isEmpty()) return false;
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return false;
        int colon = json.indexOf(':', k + key.length() + 2);
        if (colon < 0) return false;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        return json.regionMatches(true, i, "true", 0, 4);
    }

    private static long extractJsonLong(String json, String key, long fallback) {
        if (json == null || json.isEmpty()) return fallback;
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return fallback;
        int colon = json.indexOf(':', k + key.length() + 2);
        if (colon < 0) return fallback;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        if (i < json.length() && json.charAt(i) == '-') i++;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        if (i <= start || (i == start + 1 && json.charAt(start) == '-')) return fallback;
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String readSmallUtf8(java.io.File f) {
        if (f == null || !f.isFile()) return "";
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
