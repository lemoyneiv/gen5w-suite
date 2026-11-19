package hmgapi.mods.wireless;

/**
 * Host unit tests for FocusPolicy covering startup race arbitration and all
 * mid-session non-interference guarantees:
 *  1. Idle: Bluetooth Audio focus and UI work normally when no projection is starting.
 *  2. Wireless handshake ("connecting"): declines BT Audio media focus & self-launches,
 *     never blocks HFP voice-call or ring streams, and expires after 15 s (or immediately on "aborted").
 *  3. Cold-boot race: BT Audio grabbed Mode 54 / MICOM 14 before projection connected ->
 *     projection reclaims once via DIPO_CUSTOM_SHORTCUT / GPM_CUSTOM_SHORTCUT.
 *  4. Driver picks Bluetooth Audio during an active session (Review #1):
 *     - Once projection has reached foreground (115/116), after the one-shot startup
 *       reclaim, or after the 8 s startup window, opening BT Audio never schedules a
 *       reclaim and immediately ends any remaining startup window so audio plays right away.
 *  5. Passenger phone streams over Bluetooth Audio during an active session (Review #2):
 *     - After the 8 s startup window, requestAudioFocus is never blocked while CarPlay/AA stays up.
 *  6. Camera deferral (Smaller #2): PGS camera flags (reverse / DRVM / front view) and
 *     explicit Gen5W camera packages defer startup reclaim (even past 8 s during a long
 *     backup maneuver) and reclaim once the camera closes; arbitrary ".camera" substrings
 *     do not falsely match unrelated apps.
 *  7. Non-BT sources (FM/AM/SiriusXM/USB) are never hijacked at startup.
 */
public final class FocusPolicyTest {
    private static int passed;

    private static void check(String name, boolean cond) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
    }

    public static void main(String[] args) {
        // 1. Idle: BT Audio focus & UI allowed when no projection is connecting
        FocusPolicy p = new FocusPolicy();
        check("idle allows 4-arg mode 54", !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, 1000L));
        check("idle allows 3-arg mode 0", !p.shouldBlockBtAudioFocus(3, 0, 1000L));
        check("idle allows MusicActivity", !p.shouldBlockBtActivityLaunch(FocusPolicy.PKG_BTAUDIO, 1000L));
        check("idle does not reclaim",
                p.decideReclaim(false, false, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, false, FocusPolicy.PKG_BTAUDIO, 1000L)
                        == FocusPolicy.ACTION_NONE);

        // 2. Wireless CarPlay handshake ("connecting"): yields held BT focus, blocks startup retries, expires after 15 s
        p = new FocusPolicy();
        check("connecting tells BT to yield", p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "connecting", 2000L));
        check("handshake blocks mode 54", p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, 5000L));
        check("handshake blocks 3-arg mode 0", p.shouldBlockBtAudioFocus(3, 0, 5000L));
        check("handshake blocks BT self-launch", p.shouldBlockBtActivityLaunch(FocusPolicy.PKG_BTAUDIO, 5000L));
        check("handshake allows external Settings launch", !p.shouldBlockBtActivityLaunch("com.android.btsettings", 5000L));
        check("handshake never blocks HFP voice call", !p.shouldBlockBtAudioFocus(FocusPolicy.STREAM_VOICE_CALL, FocusPolicy.MODE_BT_AUDIO, 5000L));
        check("handshake never blocks ring stream", !p.shouldBlockBtAudioFocus(FocusPolicy.STREAM_RING, 0, 5000L));
        check("handshake expires after 15 s if CarPlay disabled on phone",
                !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, 2000L + FocusPolicy.CONNECTING_LEASE_MS));

        // 3. Aborted SPP handshake releases immediately without waiting 15 s
        p = new FocusPolicy();
        p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "connecting", 2000L);
        check("aborted does not yank again", !p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "aborted", 4000L));
        check("aborted immediately unblocks BT Audio", !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, 4001L));

        // 4. Cold-boot race + PGS / explicit camera deferral (even across >8 s reverse maneuver) + one-shot reclaim
        p = new FocusPolicy();
        p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "connecting", 2500L);
        p.noteBtFocusPreempted();
        check("connected tells BT to yield", p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "connected", 7000L));
        check("PGS reverse camera defers CarPlay reclaim",
                p.decideReclaim(true, false, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, true, "com.daudio.app.launcher", 7400L)
                        == FocusPolicy.ACTION_DEFER_CAMERA);
        check("explicit rearcamera package defers CarPlay reclaim",
                p.decideReclaim(true, false, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, false, "com.hkmc.app.rearcamera", 10_000L)
                        == FocusPolicy.ACTION_DEFER_CAMERA);
        check("explicit AVM package extends defer past initial 8 s window while reversing",
                p.decideReclaim(true, false, FocusPolicy.MODE_NONE, -1, false, "com.daudio.app.avm", 15_500L)
                        == FocusPolicy.ACTION_DEFER_CAMERA);
        check("unrelated third-party .camera substring is not treated as car camera",
                !FocusPolicy.isCameraActive(false, "com.example.camera.helper"));
        int cpAct = p.decideReclaim(true, false, FocusPolicy.MODE_NONE, -1, false, "com.daudio.app.launcher", 16_500L);
        check("after camera closes, CarPlay reclaims preempted empty mode once", cpAct == FocusPolicy.ACTION_RECLAIM_CARPLAY);
        check("CarPlay shortcut matches DIPO_CUSTOM_SHORTCUT",
                FocusPolicy.SHORTCUT_CARPLAY.equals(FocusPolicy.shortcutForAction(cpAct)));
        check("second check does NOT reclaim again (one-shot per connect)",
                p.decideReclaim(true, false, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, false, FocusPolicy.PKG_BTAUDIO, 18_000L)
                        == FocusPolicy.ACTION_NONE);

        // 5. Review #1: Driver picks Bluetooth Audio from media menu during CarPlay / AA session
        p = new FocusPolicy();
        p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "connected", 5000L);
        // CarPlay reached foreground at t=5400 ms (only 400 ms after connect!)
        check("no reclaim needed when CarPlay is already in foreground (mode 115)",
                p.decideReclaim(true, false, FocusPolicy.MODE_CARPLAY, -1, false, "com.daudio.app.dipo", 5400L)
                        == FocusPolicy.ACTION_NONE);
        // Driver opens Bluetooth Audio from media menu at t=7000 ms (just 2 s after connect, inside 8 s window!)
        p.onBtScreenOpened(FocusPolicy.MODE_BT_AUDIO, 7000L);
        check("driver opening BT Audio screen immediately unblocks BT Audio focus even within 8 s of connect",
                !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, 7050L));
        check("driver-chosen BT Audio screen is NEVER switched back to CarPlay",
                p.decideReclaim(true, false, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, false, FocusPolicy.PKG_BTAUDIO, 7200L)
                        == FocusPolicy.ACTION_NONE);

        // 6. Review #2: Passenger's phone streams over Bluetooth Audio during an active AA session
        p = new FocusPolicy();
        p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connected", 8000L);
        int aaAct = p.decideReclaim(false, true, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, false, "com.daudio.app.launcher", 8400L);
        check("Android Auto reclaims boot ModeType 54 once", aaAct == FocusPolicy.ACTION_RECLAIM_ANDROID_AUTO);
        check("Android Auto shortcut matches GPM_CUSTOM_SHORTCUT",
                FocusPolicy.SHORTCUT_ANDROID_AUTO.equals(FocusPolicy.shortcutForAction(aaAct)));
        check("startup window still guards immediate post-connect AVRCP bounce at +2 s",
                p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, 10_000L));
        check("after 8 s startup window, passenger phone Bluetooth streaming is allowed while AA stays connected",
                !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, 8000L + FocusPolicy.CONNECT_WINDOW_MS));
        check("duplicate connecting signal while AA is already up does NOT re-block passenger BT Audio",
                !p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connecting", 30_000L)
                        && !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, 30_500L));

        // 7. FM/AM/SiriusXM Radio preservation: never hijack Radio even if BT focus was preempted earlier
        p = new FocusPolicy();
        p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connecting", 2500L);
        p.noteBtFocusPreempted();
        p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connected", 8000L);
        check("FM Radio (mode 11, micom 1) is NOT hijacked by Android Auto",
                p.decideReclaim(false, true, 11, 1, false, "com.daudio.app.launcher", 8400L) == FocusPolicy.ACTION_NONE);

        // 8. Disconnect resets state so a later reconnect gets a fresh startup handoff
        p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "disconnected", 40_000L);
        check("reconnect after disconnect gets a fresh startup window",
                p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connected", 50_000L)
                        && p.decideReclaim(false, true, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, false, FocusPolicy.PKG_BTAUDIO, 50_400L)
                                == FocusPolicy.ACTION_RECLAIM_ANDROID_AUTO);

        System.out.println("ok    FocusPolicy: " + passed + " checks (no car access)");
    }
}
