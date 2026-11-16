package hmgapi.mods.wireless;

/**
 * Host unit tests for FocusPolicy covering all startup race windows and edge cases:
 *  1. Bluetooth Audio works normally when no projection is active or connecting.
 *  2. Wireless CarPlay & Android Auto startup handshake ("connecting") blocks BT Audio focus & UI,
 *     and releases automatically after 15 s if CarPlay/AA is disabled on the phone (or immediately on "aborted").
 *  3. Active CarPlay or Android Auto session blocks BT Audio focus (mode 54 and 3-arg mode 0)
 *     while never blocking HFP voice-call / ring streams.
 *  4. Early boot race where BT Audio grabbed ModeType 54 / MICOM 14 before Projection connected:
 *     Projection reclaims focus via DIPO_CUSTOM_SHORTCUT / GPM_CUSTOM_SHORTCUT.
 *  5. FM / AM / SiriusXM Radio is NEVER hijacked when Projection connects if BT Audio was not holding focus.
 *  6. Reverse / surround-view camera defers shortcut broadcast until camera closes, then reclaims cleanly.
 *  7. Disconnect immediately restores normal Bluetooth Audio operation, and crashed-service grace lease self-heals.
 */
public final class FocusPolicyTest {
    private static int passed;

    private static void check(String name, boolean cond) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
    }

    public static void main(String[] args) {
        // 1. Idle: BT Audio focus & UI allowed when no projection is active or connecting
        FocusPolicy p = new FocusPolicy();
        check("idle allows 4-arg mode 54", !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, false, false, 1000L));
        check("idle allows 3-arg mode 0", !p.shouldBlockBtAudioFocus(3, 0, false, false, 1000L));
        check("idle allows MusicActivity", !p.shouldBlockBtActivityLaunch(FocusPolicy.PKG_BTAUDIO, false, false, 1000L));
        check("idle does not reclaim", p.decideReclaim(false, false, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, true, FocusPolicy.PKG_BTAUDIO, 1000L) == FocusPolicy.ACTION_NONE);

        // 2. Wireless CarPlay handshake ("connecting"): yields held BT focus, blocks retries, expires cleanly if no CP
        p = new FocusPolicy();
        check("connecting tells BT to yield", p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "connecting", 2000L));
        check("handshake blocks mode 54", p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, false, false, 5000L));
        check("handshake blocks 3-arg mode 0", p.shouldBlockBtAudioFocus(3, 0, false, false, 5000L));
        check("handshake blocks BT UI", p.shouldBlockBtActivityLaunch(FocusPolicy.PKG_BTAUDIO, false, false, 5000L));
        check("handshake allows external Settings launch", !p.shouldBlockBtActivityLaunch("com.android.btsettings", false, false, 5000L));
        check("handshake never blocks HFP voice call", !p.shouldBlockBtAudioFocus(FocusPolicy.STREAM_VOICE_CALL, FocusPolicy.MODE_BT_AUDIO, true, true, 5000L));
        check("handshake never blocks ring stream", !p.shouldBlockBtAudioFocus(FocusPolicy.STREAM_RING, 0, true, true, 5000L));
        check("handshake expires after 15 s if CarPlay disabled on phone",
                !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, false, false, 2000L + FocusPolicy.CONNECTING_LEASE_MS));

        // 3. Aborted SPP handshake releases immediately without waiting 15 s
        p = new FocusPolicy();
        p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "connecting", 2000L);
        check("aborted does not yank again", !p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "aborted", 4000L));
        check("aborted immediately unblocks BT Audio", !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, false, false, 4001L));

        // 4. Cold-boot race: BT Audio grabbed ModeType 54 at t=1.5s, CarPlay connects at t=7s while backing up
        p = new FocusPolicy();
        p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "connecting", 2500L);
        p.noteBtFocusPreempted();
        check("connected tells BT to yield", p.onProjectionEvent(FocusPolicy.SOURCE_CARPLAY, "connected", 7000L));
        check("camera foreground defers CarPlay reclaim",
                p.decideReclaim(true, false, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, false, "com.hkmc.app.rearcamera", 7450L)
                        == FocusPolicy.ACTION_DEFER_CAMERA);
        check("surroundview camera also defers",
                p.decideReclaim(true, false, FocusPolicy.MODE_NONE, -1, false, "com.hkmc.avm.ui", 8450L)
                        == FocusPolicy.ACTION_DEFER_CAMERA);
        int cpAct = p.decideReclaim(true, false, FocusPolicy.MODE_NONE, -1, false, "com.daudio.app.launcher", 9450L);
        check("after camera closes, CarPlay reclaims empty preempted mode", cpAct == FocusPolicy.ACTION_RECLAIM_CARPLAY);
        check("CarPlay shortcut matches DIPO_CUSTOM_SHORTCUT",
                FocusPolicy.SHORTCUT_CARPLAY.equals(FocusPolicy.shortcutForAction(cpAct)));
        check("cooldown prevents duplicate shortcut 200ms later",
                p.decideReclaim(true, false, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, true, FocusPolicy.PKG_BTAUDIO, 9650L)
                        == FocusPolicy.ACTION_NONE);
        check("retry after 1600ms (>1500ms cooldown) succeeds if still stuck on mode 54",
                p.decideReclaim(true, false, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, false, "com.daudio.app.launcher", 11050L)
                        == FocusPolicy.ACTION_RECLAIM_CARPLAY);

        // 5. FM/SXM Radio preservation: even if BT was preempted at t=2.5s, if driver switched to FM Radio (mode=11), do NOT hijack Radio!
        p = new FocusPolicy();
        p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connecting", 2500L);
        p.noteBtFocusPreempted();
        p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connected", 8000L);
        check("FM Radio (mode 11, micom 1) is NOT hijacked by Android Auto",
                p.decideReclaim(false, true, 11, 1, false, "com.daudio.app.launcher", 8450L) == FocusPolicy.ACTION_NONE);

        // 6. Android Auto reclaims when ModeManager is stuck on Bluetooth Audio (54 / micom 14)
        p = new FocusPolicy();
        p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connected", 8000L);
        int aaAct = p.decideReclaim(false, true, FocusPolicy.MODE_BT_AUDIO, FocusPolicy.MICOM_BT_AUDIO, false, "com.daudio.app.launcher", 8450L);
        check("Android Auto reclaims from ModeType 54", aaAct == FocusPolicy.ACTION_RECLAIM_ANDROID_AUTO);
        check("Android Auto shortcut matches GPM_CUSTOM_SHORTCUT",
                FocusPolicy.SHORTCUT_ANDROID_AUTO.equals(FocusPolicy.shortcutForAction(aaAct)));

        // 7. Disconnect immediately restores Bluetooth Audio, and crashed-service grace lease self-heals
        check("live AA blocks BT Audio", p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, false, true, 60_000L));
        p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "disconnected", 61_000L);
        check("disconnect immediately unblocks BT Audio", !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, false, false, 61_001L));

        // Self-healing if GPM crashed without sending "disconnected"
        p.onProjectionEvent(FocusPolicy.SOURCE_ANDROID_AUTO, "connected", 70_000L);
        check("grace lease bridges initial connect before GpmManager flips",
                p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, false, false, 70_500L));
        check("after grace lease expires, dead GpmManager (liveAa=false) self-heals and unblocks BT Audio",
                !p.shouldBlockBtAudioFocus(3, FocusPolicy.MODE_BT_AUDIO, false, false, 70_000L + FocusPolicy.CONNECTED_GRACE_MS));

        System.out.println("ok FocusPolicyTest (" + passed + " assertions passed)");
    }
}
