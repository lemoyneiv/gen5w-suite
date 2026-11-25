#!/usr/bin/env python3
"""
prepare_eq_engine.py - Prepares hardened HMG-API `eq-engine` (0.7.7) from
`alexkozler/Gen5W-EQ-Engine` @ `2feb050` for inclusion in `Gen5W-Suite`:

1. Native Gen5W Home Screen launcher tile (`icons/icon*.png` via `mkicon.sh`) +
   ★ Custom button (`provides.star_key`) + HMG-API `mod.json` (`id: "eq-engine"`).
2. Early `hmgapid` `"start": "boot"` self-bootstrap (< 15 ms fast-path on normal
   boots; atomic library/config sync + standalone `eqengine` preset restore on
   first boot after install/update).
3. Silent parked wake dormancy (`is_silent_parked_wake` via `/dev/.openlink-bt`
   and `hmgapid vehicle power doors.closed`): zero `EqService`, `act.sh keep`,
   `logcat`, or `Hold.java` `AudioTrack` wake during locked-cabin dark wakes.
4. Cold-boot AA/CarPlay runway coordination (`el = 0..12 s`): defers `EqService`
   and `act.sh keep` (`IDLE_AFTER=2`) until AA/CarPlay TCP is established, OR
   `pcm2p == RUNNING`, OR `12 s` post-`boot_completed`.
5. Ephemeral `tmpfs` `/dev/eqengine` for high-frequency IPC and scratch files
   (`cpw/live.bin`, `cpw/status.<pid>`, `share/cp_eq.bin`, `af.txt`, `hold/`,
   `r48_hal*.txt`, `resampler.log`, `resampler.pid`) + `30 s` cap on
   `resampler.log` `logcat` capture.
6. HMG-API lifecycle & master-switch cleanliness (`act.sh on`/`off` via
   `hmgapid service-restart/stop` + `SIGTERM`/`SIGINT` unmount traps in
   `boot.sh` and `act.sh keep_loop`) + 100% `check-scripts.sh` compliance.
"""

import hashlib
import json
import os
import shutil
import stat
import subprocess
from pathlib import Path
from PIL import Image, ImageDraw


def replace_exact(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    assert old in text, f"Expected {label} block not found in {path}"
    assert text.count(old) == 1, f"Ambiguous {label} block in {path}"
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def draw_eq_glyph(out_path: Path) -> None:
    """Draws a crisp 5-fader mixer equalizer glyph on a transparent RGBA canvas for mkicon.sh."""
    size = 512
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    fader_pos = [0.28, 0.56, 0.70, 0.42, 0.22]
    top, bottom = 56, 456
    left, right = 68, 444
    xs = [left + (right - left) * i / (len(fader_pos) - 1) for i in range(len(fader_pos))]

    track_w = 24
    cap_w, cap_h = 64, 34
    gap = 12
    white = (255, 255, 255, 255)

    for x, frac in zip(xs, fader_pos):
        yc = top + (bottom - top) * frac
        # Upper track segment (above fader cap)
        u_end = yc - cap_h / 2 - gap
        if u_end - top >= track_w:
            d.rounded_rectangle(
                [x - track_w / 2, top, x + track_w / 2, u_end],
                radius=track_w / 2,
                fill=white,
            )
        # Lower track segment (below fader cap)
        l_start = yc + cap_h / 2 + gap
        if bottom - l_start >= track_w:
            d.rounded_rectangle(
                [x - track_w / 2, l_start, x + track_w / 2, bottom],
                radius=track_w / 2,
                fill=white,
            )
        # Solid rounded fader cap
        d.rounded_rectangle(
            [x - cap_w / 2, yc - cap_h / 2, x + cap_w / 2, yc + cap_h / 2],
            radius=10,
            fill=white,
        )

    img.save(out_path)


def reconstruct_stock_audio_effects(repo: Path) -> None:
    """Reconstructs stock /system/etc/audio_effects.conf (md5 4d98c1b38bd1d9eaf929d9b616c0d118) for tests/boot."""
    ours = (repo / "pkg/conf/audio_effects.conf").read_bytes().splitlines(True)
    stock = b"".join(ours[:8] + ours[12:69] + ours[74:])
    digest = hashlib.md5(stock).hexdigest()
    assert digest == "4d98c1b38bd1d9eaf929d9b616c0d118", f"Unexpected stock audio_effects.conf md5: {digest}"
    car_dir = repo / "car-files"
    car_dir.mkdir(parents=True, exist_ok=True)
    (car_dir / "audio_effects.conf").write_bytes(stock)


def patch_native_and_app(repo: Path) -> None:
    # 1. native/cpwatch.c: move live.bin, status.<pid>, cpw_<pid>.log & cp_eq.bin to tmpfs /dev/eqengine
    cpwatch_c = repo / "native/cpwatch.c"
    replace_exact(
        cpwatch_c,
        '#ifndef CPW_DIR\n#define CPW_DIR "/data/eqengine/cpw"\n#endif',
        '#ifndef CPW_DIR\n#define CPW_DIR "/dev/eqengine/cpw"\n#endif',
        "cpwatch.c CPW_DIR tmpfs",
    )
    replace_exact(
        cpwatch_c,
        '#ifndef CPE_CTL\n#define CPE_CTL "/data/eqengine/share/cp_eq.bin"\n#endif',
        '#ifndef CPE_CTL\n#define CPE_CTL "/dev/eqengine/share/cp_eq.bin"\n#endif',
        "cpwatch.c CPE_CTL tmpfs",
    )

    # 2. app/src/com/eqengine/app/CarPlayEq.java: matching tmpfs paths
    cpeq_java = repo / "app/src/com/eqengine/app/CarPlayEq.java"
    replace_exact(
        cpeq_java,
        '    static final String CTL = "/data/eqengine/share/cp_eq.bin";\n'
        '    static final String LIVE = "/data/eqengine/cpw/live.bin";',
        '    static final String CTL = "/dev/eqengine/share/cp_eq.bin";\n'
        '    static final String LIVE = "/dev/eqengine/cpw/live.bin";',
        "CarPlayEq.java tmpfs paths",
    )

    # 3. app/src/com/eqengine/app/MainActivity.java: read /dev/eqengine/cpw & /dev/eqengine/resampler.log
    main_java = repo / "app/src/com/eqengine/app/MainActivity.java"
    replace_exact(
        main_java,
        '    private static final String CPW = "/data/eqengine/cpw";',
        '    private static final String CPW = "/dev/eqengine/cpw";',
        "MainActivity.java CPW tmpfs",
    )
    replace_exact(
        main_java,
        '        for (String name : new String[] {"resampler.log.1", "resampler.log", "resampler_now.log"}) {\n'
        '            BufferedReader r = null;\n'
        '            try {\n'
        '                r = new BufferedReader(new FileReader(STATE + "/" + name));',
        '        for (String path : new String[] {\n'
        '                "/dev/eqengine/resampler.log",\n'
        '                STATE + "/resampler.log.1",\n'
        '                STATE + "/resampler.log",\n'
        '                STATE + "/resampler_now.log"}) {\n'
        '            BufferedReader r = null;\n'
        '            try {\n'
        '                r = new BufferedReader(new FileReader(path));',
        "MainActivity.java convState tmpfs resampler.log",
    )

    # 4. app/src/com/eqengine/app/Root.java: support /system/bin/nemesis fallback
    root_java = repo / "app/src/com/eqengine/app/Root.java"
    replace_exact(
        root_java,
        '        try {\n'
        '            p = new ProcessBuilder(NEMESIS, "root", "-c", command).redirectErrorStream(true).start();',
        '        try {\n'
        '            String nem = new File(NEMESIS).canExecute() ? NEMESIS : "/system/bin/nemesis";\n'
        '            p = new ProcessBuilder(nem, "root", "-c", command).redirectErrorStream(true).start();',
        "Root.java nemesis binary fallback",
    )

    # 5. app/AndroidManifest.xml: add app icon + DEFAULT & LAUNCHER categories
    manifest = repo / "app/AndroidManifest.xml"
    replace_exact(
        manifest,
        '    <application\n'
        '        android:label="@string/app_name"\n'
        '        android:theme="@android:style/Theme.Holo.NoActionBar"\n'
        '        android:allowBackup="false">',
        '    <application\n'
        '        android:label="@string/app_name"\n'
        '        android:icon="@drawable/ic_app"\n'
        '        android:theme="@android:style/Theme.Holo.NoActionBar"\n'
        '        android:allowBackup="false">',
        "AndroidManifest.xml application icon",
    )
    replace_exact(
        manifest,
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN" />\n'
        '            </intent-filter>',
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN" />\n'
        '                <category android:name="android.intent.category.DEFAULT" />\n'
        '                <category android:name="android.intent.category.LAUNCHER" />\n'
        '            </intent-filter>',
        "AndroidManifest.xml MainActivity categories",
    )

    drawable_dir = repo / "app/res/drawable-xhdpi"
    drawable_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["python3", str(repo / "tools/icon/make_favicon.py"), str(drawable_dir / "ic_app.png"), "live"],
        check=True,
    )


def patch_act_sh(repo: Path) -> None:
    act = repo / "pkg/bin/act.sh"

    # 1. Configure RAM tmpfs /dev/eqengine for AF_F, HOLD_D, HAL_TMP, CPW_D, SHARE_D
    replace_exact(
        act,
        'MOUNT=mount\n'
        '[ -z "$EQE_TEST_ROOT" ] && [ -x /system/bin/mount ] && MOUNT=/system/bin/mount\n'
        '# the audio server scan that the hold and the buffer check read (af_scan). The car report scans\n'
        '# into a file of its own: a report made during a hold could otherwise rewrite the keeper\'s file\n'
        '# between its two reads, and the hold would pass with nothing held (after 0.7.6).\n'
        'AF_F=$S/af.txt',
        'MOUNT=mount\n'
        '[ -z "$EQE_TEST_ROOT" ] && [ -x /system/bin/mount ] && MOUNT=/system/bin/mount\n'
        'RAM=/dev/eqengine\n'
        'if [ -n "$EQE_TEST_ROOT" ]; then\n'
        '    RAM=${EQE_RAM_DIR:-$S}\n'
        'else\n'
        '    mkdir -p $RAM $RAM/cpw $RAM/share 2>/dev/null\n'
        '    chmod 755 $RAM 2>/dev/null\n'
        'fi\n'
        '# the audio server scan that the hold and the buffer check read (af_scan). The car report scans\n'
        '# into a file of its own: a report made during a hold could otherwise rewrite the keeper\'s file\n'
        '# between its two reads, and the hold would pass with nothing held (after 0.7.6).\n'
        'AF_F=$RAM/af.txt',
        "act.sh RAM + AF_F tmpfs",
    )

    replace_exact(
        act,
        'HOLD_D=$S/hold',
        'HOLD_D=$RAM/hold',
        "act.sh HOLD_D tmpfs",
    )

    # 2. ScriptCheck compliance in watch_on (avoid $(...) followed by ; inside "...")
    replace_exact(
        act,
        '    [ "$(md5of $WATCH)" = "$_w" ] || { echo "the driver watch failed its check; not starting it"; return 1; }\n'
        '    [ "$(md5of $HAL_T)" = "$HAL_MD5" ] || {\n'
        '        echo "the factory audio driver is not the one this was built for (md5 $(md5of $HAL_T)); not starting the driver watch"\n'
        '        return 1\n'
        '    }',
        '    _watch_have=$(md5of $WATCH)\n'
        '    [ "$_watch_have" = "$_w" ] || { echo "the driver watch failed its check; not starting it"; return 1; }\n'
        '    _hal_have=$(md5of $HAL_T)\n'
        '    [ "$_hal_have" = "$HAL_MD5" ] || {\n'
        '        echo "the factory audio driver is not the one this was built for (md5 $_hal_have); not starting the driver watch"\n'
        '        return 1\n'
        '    }',
        "act.sh watch_on md5 check",
    )
    replace_exact(
        act,
        '    [ "$(md5of $HAL_T)" = "$_w" ] || { unmount_at $HAL_T; echo "the driver watch did not check out after mounting; removed it"; return 1; }',
        '    _hal_mounted=$(md5of $HAL_T)\n'
        '    [ "$_hal_mounted" = "$_w" ] || { unmount_at $HAL_T; echo "the driver watch did not check out after mounting; removed it"; return 1; }',
        "act.sh watch_on post-mount md5",
    )

    # 3. Move r48_hal*.txt scratch files to $RAM
    replace_exact(
        act,
        'hal_from() {\n'
        '    hal_rate_lines > $S/r48_hal.txt\n'
        '    while read -r _d _t _rest; do\n'
        '        _c=${_t%%.*}\n'
        '        _h=${_c%%:*}; _c=${_c#*:}\n'
        '        _m=${_c%%:*}; _c=${_c#*:}\n'
        '        [ "1$_h$_m$_c" -ge "1$1" ] 2>/dev/null || continue\n'
        '        echo "${_rest#*): }"\n'
        '    done < $S/r48_hal.txt\n'
        '    rm -f $S/r48_hal.txt\n'
        '}\n'
        '\n'
        '# the newest driver line about its rate since the time $1\n'
        'hal_new() {\n'
        '    _last=""\n'
        '    hal_from "$1" > $S/r48_haln.txt\n'
        '    while IFS= read -r _l; do _last=$_l; done < $S/r48_haln.txt\n'
        '    rm -f $S/r48_haln.txt\n'
        '    [ -n "$_last" ] && echo "$_last"\n'
        '}\n'
        '\n'
        '# all driver lines about its rate since the time $1, joined by " | "\n'
        'hal_since() {\n'
        '    _all=""\n'
        '    hal_from "$1" > $S/r48_haln.txt\n'
        '    while IFS= read -r _l; do _all="${_all:+$_all | }$_l"; done < $S/r48_haln.txt\n'
        '    rm -f $S/r48_haln.txt\n'
        '    echo "$_all"\n'
        '}',
        'hal_from() {\n'
        '    hal_rate_lines > $RAM/r48_hal.txt\n'
        '    while read -r _d _t _rest; do\n'
        '        _c=${_t%%.*}\n'
        '        _h=${_c%%:*}; _c=${_c#*:}\n'
        '        _m=${_c%%:*}; _c=${_c#*:}\n'
        '        [ "1$_h$_m$_c" -ge "1$1" ] 2>/dev/null || continue\n'
        '        echo "${_rest#*): }"\n'
        '    done < $RAM/r48_hal.txt\n'
        '    rm -f $RAM/r48_hal.txt\n'
        '}\n'
        '\n'
        '# the newest driver line about its rate since the time $1\n'
        'hal_new() {\n'
        '    _last=""\n'
        '    hal_from "$1" > $RAM/r48_haln.txt\n'
        '    while IFS= read -r _l; do _last=$_l; done < $RAM/r48_haln.txt\n'
        '    rm -f $RAM/r48_haln.txt\n'
        '    [ -n "$_last" ] && echo "$_last"\n'
        '}\n'
        '\n'
        '# all driver lines about its rate since the time $1, joined by " | "\n'
        'hal_since() {\n'
        '    _all=""\n'
        '    hal_from "$1" > $RAM/r48_haln.txt\n'
        '    while IFS= read -r _l; do _all="${_all:+$_all | }$_l"; done < $RAM/r48_haln.txt\n'
        '    rm -f $RAM/r48_haln.txt\n'
        '    echo "$_all"\n'
        '}',
        "act.sh hal_from tmpfs",
    )

    # 4. Add TERM/INT trap to keep_loop so hmgapid/disable releases Hold.java cleanly
    replace_exact(
        act,
        'keep_loop() {\n'
        '    echo $$ > $KEEP_PID\n'
        '    chmod 644 $KEEP_PID',
        'keep_loop() {\n'
        '    trap \'hold_end; rm -f $KEEP_PID; exit 0\' TERM INT\n'
        '    echo $$ > $KEEP_PID\n'
        '    chmod 644 $KEEP_PID',
        "act.sh keep_loop TERM trap",
    )

    # 5. Point CPW_D and SHARE_D to RAM tmpfs /dev/eqengine on the head unit
    replace_exact(
        act,
        'CPW_D=$D/cpw',
        'CPW_D=$RAM/cpw\n'
        '[ -n "$EQE_TEST_ROOT" ] && [ -z "$EQE_RAM_DIR" ] && CPW_D=$D/cpw\n'
        'SHARE_D=$RAM/share\n'
        '[ -n "$EQE_TEST_ROOT" ] && [ -z "$EQE_RAM_DIR" ] && SHARE_D=$D/share',
        "act.sh CPW_D and SHARE_D tmpfs",
    )

    # 6. ScriptCheck compliance in cpw_takeoff
    replace_exact(
        act,
        '            _tn="$2; taken off; the CarPlay app was not restarted ($(cp_conn $1)); restart the car (parked) to bring CarPlay back"',
        '            _cp_cnt=$(cp_conn $1)\n'
        '            _tn="$2; taken off; the CarPlay app was not restarted ($_cp_cnt); restart the car (parked) to bring CarPlay back"',
        "act.sh cpw_takeoff cp_conn",
    )

    # 7. cpe_share uses $SHARE_D (with app UID lookup fallback)
    replace_exact(
        act,
        'cpe_share() {\n'
        '    mkdir -p $D/share\n'
        '    _so=$(owner_of $D/settings)\n'
        '    [ -n "$_so" ] && chown $_so:$_so $D/share 2>/dev/null\n'
        '    chmod 755 $D/share\n'
        '}',
        'cpe_share() {\n'
        '    mkdir -p $SHARE_D\n'
        '    _so=""\n'
        '    [ -d $SYS/data/data/com.eqengine.app ] && _so=$(owner_of $SYS/data/data/com.eqengine.app)\n'
        '    [ -z "$_so" ] && _so=$(owner_of $D/settings)\n'
        '    [ -n "$_so" ] && chown $_so:$_so $SHARE_D 2>/dev/null\n'
        '    chmod 755 $SHARE_D\n'
        '}',
        "act.sh cpe_share tmpfs",
    )

    # 8. ScriptCheck compliance for CR (no printf)
    replace_exact(
        act,
        'CR=$(print -n \'\\r\' 2>/dev/null)\n'
        '[ ${#CR} = 1 ] || CR=$(printf \'\\r\' 2>/dev/null)',
        'CR=$(print -n \'\\r\' 2>/dev/null)\n'
        '[ ${#CR} = 1 ] || CR=$(echo -e \'\\r\' 2>/dev/null)\n'
        '[ ${#CR} = 1 ] || CR=$(echo \'\\r\')',
        "act.sh CR without printf",
    )

    # 9. Include tmpfs resampler.log & HMG-API boot log in USB All-logs save
    replace_exact(
        act,
        '    cat /data/nemesis/logs/eqengine/boot.log > boot_step.log 2>&1',
        '    [ -f $RAM/resampler.log ] && save_cp $RAM/resampler.log state_resampler.log.txt\n'
        '    if [ -f /data/hmgapi/logs/eq-engine/boot.log ]; then\n'
        '        cat /data/hmgapi/logs/eq-engine/boot.log > boot_step.log 2>&1\n'
        '    else\n'
        '        cat /data/nemesis/logs/eqengine/boot.log > boot_step.log 2>&1\n'
        '    fi',
        "act.sh save tmpfs resampler.log and hmgapi boot.log",
    )

    # 10. HMG-API lifecycle in act.sh off and on
    replace_exact(
        act,
        'off)\n'
        '    echo "turned off in the app" > $D/state/disabled\n'
        '    chmod 644 $S/disabled\n'
        '    setprop af.resampler.quality 0\n'
        '    # the boot step notices within 5 s; make sure the overlay is gone either way\n'
        '    sleep 6',
        'off)\n'
        '    echo "turned off in the app" > $D/state/disabled\n'
        '    chmod 644 $S/disabled\n'
        '    setprop af.resampler.quality 0\n'
        '    # the boot step notices within 5 s; make sure the overlay is gone either way\n'
        '    sleep 6\n'
        '    if [ -f $KEEP_PID ]; then\n'
        '        read _okp < $KEEP_PID\n'
        '        if [ -n "$_okp" ] && [ -f $PROC/$_okp/cmdline ]; then\n'
        '            read _okc < $PROC/$_okp/cmdline\n'
        '            case "$_okc" in *act.sh*) kill $_okp 2>/dev/null ;; esac\n'
        '        fi\n'
        '        rm -f $KEEP_PID\n'
        '    fi\n'
        '    hold_end',
        "act.sh off cleanup",
    )

    replace_exact(
        act,
        'on)\n'
        '    rm -f $S/disabled $S/strikes $S/lastboot $S/expected_restart\n'
        '    # stop first: a leftover instance would make "start" do nothing\n'
        '    $NEM service stop eqengine/boot > /dev/null 2>&1\n'
        '    $NEM service start eqengine/boot',
        'on)\n'
        '    rm -f $S/disabled $S/strikes $S/lastboot $S/expected_restart\n'
        '    HMG_CTL=${EQE_HMGAPID:-}\n'
        '    [ -z "$EQE_TEST_ROOT" ] && [ -z "$HMG_CTL" ] && [ -x /system/bin/hmgapid ] && HMG_CTL=/system/bin/hmgapid\n'
        '    if [ -n "$HMG_CTL" ]; then\n'
        '        $HMG_CTL service-stop eq-engine boot > /dev/null 2>&1\n'
        '        $HMG_CTL service-restart eq-engine boot\n'
        '    else\n'
        '        $NEM service stop eqengine/boot > /dev/null 2>&1\n'
        '        $NEM service start eqengine/boot\n'
        '    fi',
        "act.sh on via hmgapid",
    )


def patch_boot_sh(repo: Path) -> None:
    boot = repo / "pkg/bin/boot.sh"

    # 1. Configure RAM tmpfs, MOD_DIR self-bootstrap, HMGAPID, RUNWAY_WAIT, and CAP_MAX
    replace_exact(
        boot,
        'MOUNT=mount\n'
        '[ -z "$EQE_TEST_ROOT" ] && [ -x /system/bin/mount ] && MOUNT=/system/bin/mount',
        'MOUNT=mount\n'
        '[ -z "$EQE_TEST_ROOT" ] && [ -x /system/bin/mount ] && MOUNT=/system/bin/mount\n'
        'RAM=/dev/eqengine\n'
        'RUNWAY_WAIT=${EQE_RUNWAY_WAIT:-12}\n'
        'CAP_MAX=${EQE_CAP_MAX:-45}\n'
        'HMGAPID_BIN=${EQE_HMGAPID:-}\n'
        'if [ -n "$EQE_TEST_ROOT" ]; then\n'
        '    RAM=${EQE_RAM_DIR:-$S}\n'
        '    [ -z "$EQE_RUNWAY_WAIT" ] && RUNWAY_WAIT=0\n'
        '    [ -z "$EQE_CAP_MAX" ] && CAP_MAX=0\n'
        'else\n'
        '    [ -z "$HMGAPID_BIN" ] && [ -x /system/bin/hmgapid ] && HMGAPID_BIN=/system/bin/hmgapid\n'
        '    MOD_DIR=${HMGAPI_MOD_DIR:-/data/hmgapi/mods/eq-engine}\n'
        '    if [ -d "$MOD_DIR/lib" ]; then\n'
        '        _inst_st=""\n'
        '        [ -f $S/mod_stamp ] && read -r _inst_st < $S/mod_stamp\n'
        '        _pkg_st=""\n'
        '        [ -f "$MOD_DIR/lib/mod_stamp" ] && read -r _pkg_st < "$MOD_DIR/lib/mod_stamp"\n'
        '        if [ "$_inst_st" != "$_pkg_st" ] || [ ! -f "$OURS" ] || [ ! -f "$LIB" ] || [ ! -x "$D/bin/act.sh" ]; then\n'
        '            mkdir -p $D/lib $D/conf $S $D/bin $SET\n'
        '            for _bf in act.sh alsactl_dump eqe_lab; do\n'
        '                [ -f "$MOD_DIR/bin/$_bf" ] || continue\n'
        '                cp "$MOD_DIR/bin/$_bf" "$D/bin/$_bf.new" && mv "$D/bin/$_bf.new" "$D/bin/$_bf"\n'
        '                chmod 755 "$D/bin/$_bf"\n'
        '            done\n'
        '            for _lf in libeqengine.so libaudio-resampler.so eqe_drvwatch.so eqe_cpwatch.so; do\n'
        '                [ -f "$MOD_DIR/lib/$_lf" ] || continue\n'
        '                cp "$MOD_DIR/lib/$_lf" "$D/lib/$_lf.new" && mv "$D/lib/$_lf.new" "$D/lib/$_lf"\n'
        '                chmod 644 "$D/lib/$_lf"\n'
        '            done\n'
        '            for _cf in audio_effects.conf libaudio-resampler.md5 eqe_drvwatch.md5 eqe_cpwatch.md5; do\n'
        '                [ -f "$MOD_DIR/conf/$_cf" ] || continue\n'
        '                cp "$MOD_DIR/conf/$_cf" "$D/conf/$_cf.new" && mv "$D/conf/$_cf.new" "$D/conf/$_cf"\n'
        '                chmod 644 "$D/conf/$_cf"\n'
        '            done\n'
        '            if [ -d /data/hmgapi/eqengine-migrate ]; then\n'
        '                for _mf in /data/hmgapi/eqengine-migrate/*; do\n'
        '                    [ -f "$_mf" ] || continue\n'
        '                    [ -f "$SET/${_mf##*/}" ] || cp "$_mf" "$SET/${_mf##*/}"\n'
        '                done\n'
        '                rm -rf /data/hmgapi/eqengine-migrate\n'
        '            fi\n'
        '            chmod 755 $D $D/lib $D/conf $S $D/bin\n'
        '            [ -z "$_inst_st" ] && echo installed > $S/fresh_install\n'
        '            echo "${_pkg_st:-installed}" > $S/mod_stamp\n'
        '            chmod 644 $S/mod_stamp\n'
        '            BOOT_SYNCED_MOD=1\n'
        '        fi\n'
        '    fi\n'
        'fi\n'
        'RES_LOG=$RAM/resampler.log\n'
        'RES_PID=$RAM/resampler.pid\n'
        'HOLD_D=$RAM/hold',
        "boot.sh early self-bootstrap and RAM tmpfs setup",
    )

    # 2. Pass EQE_IDLE_AFTER=2 when starting keeper from boot.sh, and add stop_keeper_and_hold + sync_app_owner + silent-wake & runway helpers
    replace_exact(
        boot,
        'keep_start() {\n'
        '    $KEEP_CMD < /dev/null > /dev/null 2>&1 &\n'
        '    echo $! > $S/keep.pid\n'
        '    chmod 644 $S/keep.pid\n'
        '}',
        'keep_start() {\n'
        '    EQE_IDLE_AFTER=${EQE_IDLE_AFTER:-2} $KEEP_CMD < /dev/null > /dev/null 2>&1 &\n'
        '    echo $! > $S/keep.pid\n'
        '    chmod 644 $S/keep.pid\n'
        '}\n'
        '\n'
        'stop_keeper_and_hold() {\n'
        '    if [ -f $S/keep.pid ]; then\n'
        '        read _skp < $S/keep.pid\n'
        '        if [ -n "$_skp" ] && [ -f $PROC/$_skp/cmdline ]; then\n'
        '            read _skc < $PROC/$_skp/cmdline\n'
        '            case "$_skc" in\n'
        '                ""|*act.sh*) kill $_skp 2>/dev/null ;;\n'
        '            esac\n'
        '        fi\n'
        '        rm -f $S/keep.pid\n'
        '    fi\n'
        '    if [ -f $HOLD_D/pid ]; then\n'
        '        echo 1 > $HOLD_D/release 2>/dev/null\n'
        '        read _shp < $HOLD_D/pid\n'
        '        if [ -n "$_shp" ] && [ -f $PROC/$_shp/cmdline ]; then\n'
        '            read _shc < $PROC/$_shp/cmdline\n'
        '            case "$_shc" in\n'
        '                *app_process*|*Hold*) kill $_shp 2>/dev/null ;;\n'
        '            esac\n'
        '        fi\n'
        '        rm -rf $HOLD_D\n'
        '    fi\n'
        '}\n'
        '\n'
        'APP_OWNED=0\n'
        'sync_app_owner() {\n'
        '    [ $APP_OWNED = 1 ] && return 0\n'
        '    _ad=${EQE_TEST_ROOT:-}/data/data/com.eqengine.app\n'
        '    [ -d "$_ad" ] || return 0\n'
        '    set -- $(ls -ld "$_ad" 2>/dev/null)\n'
        '    _ao=$2\n'
        '    case "$_ao" in\n'
        '        ""|*[!0-9]*) ;;\n'
        '        *) _ao=$3 ;;\n'
        '    esac\n'
        '    [ -n "$_ao" ] || return 0\n'
        '    mkdir -p $SET $RAM/share 2>/dev/null\n'
        '    chown $_ao:$_ao $SET $RAM/share 2>/dev/null\n'
        '    for _sf in $SET/*; do\n'
        '        [ -f "$_sf" ] && chown $_ao:$_ao "$_sf" 2>/dev/null && chmod 600 "$_sf" 2>/dev/null\n'
        '    done\n'
        '    chmod 700 $SET 2>/dev/null\n'
        '    chmod 755 $RAM/share 2>/dev/null\n'
        '    APP_OWNED=1\n'
        '}\n'
        '\n'
        'pcm_running() {\n'
        '    _pf=${EQE_TEST_ROOT:-}/proc/asound/card0/pcm2p/sub0/status\n'
        '    [ -f "$_pf" ] || return 1\n'
        '    _pl=""\n'
        '    read -r _pl < "$_pf"\n'
        '    case "$_pl" in\n'
        '        *RUNNING*) return 0 ;;\n'
        '    esac\n'
        '    return 1\n'
        '}\n'
        '\n'
        'proj_connected() {\n'
        '    for _tf in $PROC/net/tcp $PROC/net/tcp6; do\n'
        '        [ -f "$_tf" ] || continue\n'
        '        while read -r _s0 _loc _rem _st _rest; do\n'
        '            [ "$_st" = 01 ] || continue\n'
        '            case "$_loc" in\n'
        '                012BA8C0:*|0131A8C0:*|*012BA8C0:*|*0131A8C0:*) return 0 ;;\n'
        '            esac\n'
        '        done < "$_tf"\n'
        '    done\n'
        '    return 1\n'
        '}\n'
        '\n'
        'is_silent_parked_wake() {\n'
        '    pcm_running && return 1\n'
        '    [ -f ${EQE_TEST_ROOT:-}/dev/.openlink-bt ] && return 0\n'
        '    [ -f ${EQE_TEST_ROOT:-}/dev/.hmgapi-proj-faststart ] && return 1\n'
        '    [ -n "$HMGAPID_BIN" ] || return 1\n'
        '    _vo=$($HMGAPID_BIN vehicle power doors.closed 2>/dev/null) || return 1\n'
        '    _vp=""; _vdc=""\n'
        '    while read -r _vk _vv _vr; do\n'
        '        case "$_vk" in\n'
        '            power) _vp=$_vv ;;\n'
        '            doors.closed) _vdc=$_vv ;;\n'
        '        esac\n'
        '    done <<EOF_VEH\n'
        '$_vo\n'
        'EOF_VEH\n'
        '    case "$_vp" in\n'
        '        delay_mode|adm|preparing_off|off)\n'
        '            [ "$_vdc" = 1 ] && return 0 ;;\n'
        '    esac\n'
        '    return 1\n'
        '}',
        "boot.sh helpers for keep, silent wake, and runway",
    )

    # 3. Update resampler.log capture to use $RES_LOG/$RES_PID on tmpfs + 30s CAP_MAX auto-stop
    replace_exact(
        boot,
        'stop_capture() {\n'
        '    if [ -f $S/resampler.pid ]; then\n'
        '        read _p < $S/resampler.pid\n'
        '        if [ -n "$_p" ] && [ -f $PROC/$_p/cmdline ]; then\n'
        '            read _c < $PROC/$_p/cmdline\n'
        '            case "$_c" in\n'
        '                *logcat*) kill $_p 2>/dev/null ;;\n'
        '            esac\n'
        '        fi\n'
        '        rm -f $S/resampler.pid\n'
        '    fi\n'
        '}\n'
        '\n'
        'start_capture() {\n'
        '    # The audio server logs "forcing AudioResampler quality to N" when it first converts.\n'
        '    # Android 4.4\'s logcat stops reading options at the first filter (tag:level), so every option\n'
        '    # comes first and the filters last; without rotation (a few lines per audio server start, and\n'
        '    # a new start begins a new file).\n'
        '    stop_capture\n'
        '    logcat -v time -f $S/resampler.log -s AudioResampler:D AudioResamplerSinc:E < /dev/null > /dev/null 2>&1 &\n'
        '    echo $! > $S/resampler.pid\n'
        '}\n'
        '\n'
        '# (after 0.7.6) The capture is a logcat that reads every line Android logs (about 460 a second on\n'
        '# this unit), so it no longer runs the whole drive. An audio server logs its converter line once,\n'
        '# when it first converts (the "could not open" line, if any, right after it): the capture stops\n'
        '# one round after it holds the running audio server\'s line, and starts again for the next audio\n'
        '# server (logcat first writes what its buffer still holds, about the last minute, so a line logged\n'
        '# in between is kept). An audio server that never converts keeps it running, as before.\n'
        'CAP_DONE=""\n'
        'CAP_SEEN=""\n'
        'cap_has() {\n'
        '    [ -f $S/resampler.log ] || return 1\n'
        '    while IFS= read -r _cl; do\n'
        '        case "$_cl" in\n'
        '            *"forcing AudioResampler quality to "*) ;;\n'
        '            *) continue ;;\n'
        '        esac\n'
        '        _cq=${_cl#*"AudioResampler("}\n'
        '        _cq=${_cq%%")"*}\n'
        '        _cq=${_cq##* }\n'
        '        [ "$_cq" = "$1" ] && return 0\n'
        '    done < $S/resampler.log\n'
        '    return 1\n'
        '}\n'
        'cap_check() {\n'
        '    [ -n "$MSPID" ] || return 0\n'
        '    [ "$CAP_DONE" = "$MSPID" ] && return 0\n'
        '    if [ ! -f $S/resampler.pid ]; then\n'
        '        start_capture\n'
        '        CAP_SEEN=""\n'
        '        return 0\n'
        '    fi\n'
        '    cap_has $MSPID || return 0\n'
        '    if [ "$CAP_SEEN" = "$MSPID" ]; then\n'
        '        stop_capture\n'
        '        CAP_DONE=$MSPID\n'
        '    else\n'
        '        CAP_SEEN=$MSPID\n'
        '        # logcat makes its file root only; the app shows this line\n'
        '        chmod 644 $S/resampler.log\n'
        '    fi\n'
        '}',
        'CAP_AT=0\n'
        'stop_capture() {\n'
        '    if [ -f $RES_PID ]; then\n'
        '        read _p < $RES_PID\n'
        '        if [ -n "$_p" ] && [ -f $PROC/$_p/cmdline ]; then\n'
        '            read _c < $PROC/$_p/cmdline\n'
        '            case "$_c" in\n'
        '                *logcat*) kill $_p 2>/dev/null ;;\n'
        '            esac\n'
        '        fi\n'
        '        rm -f $RES_PID\n'
        '    fi\n'
        '}\n'
        '\n'
        'start_capture() {\n'
        '    stop_capture\n'
        '    mkdir -p $RAM 2>/dev/null\n'
        '    CAP_AT=$(up)\n'
        '    logcat -v time -f $RES_LOG -s AudioResampler:D AudioResamplerSinc:E < /dev/null > /dev/null 2>&1 &\n'
        '    echo $! > $RES_PID\n'
        '}\n'
        '\n'
        'CAP_DONE=""\n'
        'CAP_SEEN=""\n'
        'cap_has() {\n'
        '    [ -f $RES_LOG ] || return 1\n'
        '    while IFS= read -r _cl; do\n'
        '        case "$_cl" in\n'
        '            *"forcing AudioResampler quality to "*) ;;\n'
        '            *) continue ;;\n'
        '        esac\n'
        '        _cq=${_cl#*"AudioResampler("}\n'
        '        _cq=${_cq%%")"*}\n'
        '        _cq=${_cq##* }\n'
        '        [ "$_cq" = "$1" ] && return 0\n'
        '    done < $RES_LOG\n'
        '    return 1\n'
        '}\n'
        'cap_check() {\n'
        '    [ -n "$MSPID" ] || return 0\n'
        '    [ "$CAP_DONE" = "$MSPID" ] && return 0\n'
        '    if [ ! -f $RES_PID ]; then\n'
        '        start_capture\n'
        '        CAP_SEEN=""\n'
        '        return 0\n'
        '    fi\n'
        '    if cap_has $MSPID; then\n'
        '        if [ "$CAP_SEEN" = "$MSPID" ]; then\n'
        '            stop_capture\n'
        '            CAP_DONE=$MSPID\n'
        '        else\n'
        '            CAP_SEEN=$MSPID\n'
        '            chmod 644 $RES_LOG\n'
        '        fi\n'
        '        return 0\n'
        '    fi\n'
        '    if [ $CAP_MAX -gt 0 ] && [ $CAP_AT -gt 0 ] && [ $((NOW - CAP_AT)) -ge $CAP_MAX ]; then\n'
        '        [ -f $RES_LOG ] && chmod 644 $RES_LOG\n'
        '        stop_capture\n'
        '        CAP_DONE=$MSPID\n'
        '    fi\n'
        '}',
        "boot.sh tmpfs resampler.log + 30s cap",
    )

    # 4. Add unmount of CarPlay layer + on_term SIGTERM/SIGINT trap (cleanup only when mod is disabled/removed)
    replace_exact(
        boot,
        'unmount_all() {\n'
        '    lab_off\n'
        '    _i=0\n'
        '    while mounted && ours $TARGET $OURS && [ $_i -lt 5 ]; do\n'
        '        umount $TARGET\n'
        '        _i=$((_i + 1))\n'
        '        mounted && sleep 1\n'
        '    done\n'
        '    mounted && ours $TARGET $OURS && return 1\n'
        '    return 0\n'
        '}',
        'unmount_cpw() {\n'
        '    _cpt=${EQE_TEST_ROOT:-}/system/lib/libCarPlayAndroid.so\n'
        '    _cpf=$D/lib/eqe_cpwatch.so\n'
        '    _ci=0\n'
        '    while [ $_ci -lt 4 ]; do\n'
        '        _cm=0\n'
        '        while read _cd _cmnt _cr; do\n'
        '            [ "$_cmnt" = "$_cpt" ] && _cm=1\n'
        '        done < $PROC/mounts\n'
        '        [ $_cm = 1 ] && ours "$_cpt" "$_cpf" || break\n'
        '        umount "$_cpt" 2>/dev/null || { [ -x $D/bin/eqe_lab ] && $D/bin/eqe_lab umount "$_cpt" > /dev/null 2>&1; }\n'
        '        _ci=$((_ci + 1))\n'
        '    done\n'
        '}\n'
        '\n'
        'unmount_all() {\n'
        '    lab_off\n'
        '    _i=0\n'
        '    while mounted && ours $TARGET $OURS && [ $_i -lt 5 ]; do\n'
        '        umount $TARGET\n'
        '        _i=$((_i + 1))\n'
        '        mounted && sleep 1\n'
        '    done\n'
        '    mounted && ours $TARGET $OURS && return 1\n'
        '    return 0\n'
        '}\n'
        '\n'
        'mod_is_disabled() {\n'
        '    [ -f $S/disabled ] && return 0\n'
        '    if [ -n "$EQE_TEST_ROOT" ]; then\n'
        '        [ -f "$EQE_TEST_ROOT/mod_disabled" ] && return 0\n'
        '        return 1\n'
        '    fi\n'
        '    _md=${MOD_DIR:-/data/hmgapi/mods/eq-engine}\n'
        '    [ -f "$_md/.disabled" ] && return 0\n'
        '    [ ! -d "$_md" ] && return 0\n'
        '    return 1\n'
        '}\n'
        '\n'
        'on_term() {\n'
        '    trap - TERM INT\n'
        '    if mod_is_disabled; then\n'
        '        reset_conv\n'
        '        stop_keeper_and_hold\n'
        '        unmount_cpw\n'
        '        if unmount_all; then OVERLAY=0; fi\n'
        '        STATE=off\n'
        '        REASON="stopped by HMG-API"\n'
        '        [ $GOOD = 0 ] && set_lastboot off\n'
        '        event "stopped on signal; overlays and converter reset"\n'
        '        write_status\n'
        '    fi\n'
        '    exit 0\n'
        '}\n'
        'trap \'on_term\' TERM INT',
        "boot.sh unmount_cpw and on_term trap",
    )

    # 5. Clean tmpfs files on new boot + prepare /dev/eqengine + default cpwatch 1 on first mod bootstrap
    replace_exact(
        boot,
        '    rm -f $S/resampler.log $S/resampler.log.1 $S/resampler_now.log $S/last_restart $S/expected_restart $S/restart_as.log\n'
        '    rm -rf $S/restart.lock $S/cpwatch.lock\n'
        '    # Lab tests and the 48 kHz switch are in memory: a new start has neither\n'
        '    rm -f $S/lab_active $S/lab_ended $S/rate48 $S/rate48_sent $S/output $S/keep.pid $S/af.txt $S/af_report.txt $S/report_af.txt\n'
        '    # a car report\'s own files, left only when it was cut short\n'
        '    rm -f $S/report_log.txt $S/report_dump.txt\n'
        '    # Hold.java (48 kHz switches) does not outlive a car start\n'
        '    rm -rf $S/hold',
        '    rm -f $RES_LOG $RES_PID $S/resampler.log $S/resampler.log.1 $S/resampler_now.log $S/last_restart $S/expected_restart $S/restart_as.log\n'
        '    rm -rf $S/restart.lock $S/cpwatch.lock\n'
        '    # Lab tests and the 48 kHz switch are in memory: a new start has neither\n'
        '    rm -f $S/lab_active $S/lab_ended $S/rate48 $S/rate48_sent $S/output $S/keep.pid $RAM/af.txt $S/af.txt $S/af_report.txt $S/report_af.txt\n'
        '    # a car report\'s own files, left only when it was cut short\n'
        '    rm -f $S/report_log.txt $S/report_dump.txt\n'
        '    # Hold.java (48 kHz switches) does not outlive a car start\n'
        '    rm -rf $HOLD_D $S/hold',
        "boot.sh new-boot tmpfs cleanup",
    )

    # 6. Defer start_capture on silent wake + prepare default cpw_want after first bootstrap + silent-wake & cold-boot runway loop gating
    replace_exact(
        boot,
        'conv_quality\n'
        'setprop af.resampler.quality $CONV\n'
        'start_capture',
        'mkdir -p $RAM $RAM/cpw $RAM/share 2>/dev/null\n'
        'chmod 755 $RAM $RAM/cpw $RAM/share 2>/dev/null\n'
        'sync_app_owner\n'
        'if [ "${BOOT_SYNCED_MOD:-0}" = 1 ] && [ ! -f $S/cpw_want ] && [ -x $D/bin/act.sh ]; then\n'
        '    _cpeq=1\n'
        '    if [ -f $SET/current.txt ]; then\n'
        '        while IFS=\'=\' read -r _ck _cv; do\n'
        '            [ "$_ck" = cp_eq ] && [ "$_cv" = 0 ] && _cpeq=0\n'
        '        done < $SET/current.txt\n'
        '    fi\n'
        '    [ $_cpeq = 1 ] && EQE_CPW_NOTIFY=1 sh $D/bin/act.sh cpwatch 1 > $S/cpw_install.txt 2>&1 < /dev/null\n'
        '    [ -f $S/cpw_install.txt ] && chmod 644 $S/cpw_install.txt\n'
        'fi\n'
        'conv_quality\n'
        'setprop af.resampler.quality $CONV\n'
        'if ! is_silent_parked_wake; then\n'
        '    start_capture\n'
        'fi',
        "boot.sh post-mount tmpfs, cpwatch init, and silent-wake capture guard",
    )

    replace_exact(
        boot,
        'LASTR=0\n'
        'LASTWD=0\n'
        'LASTCH=0\n'
        'LAB_GRACE=0\n'
        'CR48_AT=0\n'
        'while :; do',
        'LASTR=0\n'
        'LASTWD=0\n'
        'LASTCH=0\n'
        'LAB_GRACE=0\n'
        'CR48_AT=0\n'
        'BC_AT=""\n'
        'SILENT_DORMANT=0\n'
        'RUNWAY_NOTED=0\n'
        'while :; do',
        "boot.sh loop state variables",
    )

    replace_exact(
        boot,
        '    # Android\'s zygote, to tell a framework restart from an audio server crash (looked up again\n'
        '    # only while not known)\n'
        '    [ -z "$ZYPID" ] && ZYPID=$(zygote_pid)\n'
        '    cap_check',
        '    # Android\'s zygote, to tell a framework restart from an audio server crash (looked up again\n'
        '    # only while not known)\n'
        '    [ -z "$ZYPID" ] && ZYPID=$(zygote_pid)\n'
        '    if is_silent_parked_wake; then\n'
        '        if [ $SILENT_DORMANT = 0 ]; then\n'
        '            SILENT_DORMANT=1\n'
        '            stop_capture\n'
        '            stop_keeper_and_hold\n'
        '            event "silent parked wake detected: keeping EqService, logcat, and 48 kHz Hold dormant"\n'
        '        fi\n'
        '    else\n'
        '        if [ $SILENT_DORMANT = 1 ]; then\n'
        '            SILENT_DORMANT=0\n'
        '            event "cabin active: resuming EQ Engine audio monitoring"\n'
        '        fi\n'
        '        cap_check\n'
        '    fi',
        "boot.sh silent parked wake dormancy in main loop",
    )

    replace_exact(
        boot,
        '    if [ $GOOD = 0 ] || [ $((NOW - LASTCH)) -ge 60 ]; then\n'
        '        LASTCH=$NOW\n'
        '        [ -f $S/resampler.log ] && chmod 644 $S/resampler.log\n'
        '        [ -f $S/resampler.log.1 ] && chmod 644 $S/resampler.log.1\n'
        '    fi\n'
        '    write_status\n'
        '    # Start the EQ service only after this start\'s status is written, so it never reads the\n'
        '    # previous run\'s status (for example "off" just after being turned back on).\n'
        '    if [ $SVC_STARTED = 0 ] && [ "$(getprop sys.boot_completed)" = 1 ]; then\n'
        '        SVC_STARTED=1\n'
        '        LASTWD=$NOW\n'
        '        event "Android has started; starting the EQ service: $(flat $(am startservice -n $SVC 2>&1))"\n'
        '        # (after 0.7.6) the boot step runs again when EQ Engine is turned back on: a keeper still\n'
        '        # running from before carries on (it follows the setting), so no second one cancels its\n'
        '        # track hold in the middle of a rate change\n'
        '        if keep_alive; then\n'
        '            event "48 kHz output keeper still running (pid $_kp): kept"\n'
        '        else\n'
        '            keep_start\n'
        '            event "48 kHz output keeper started"\n'
        '        fi\n'
        '        write_status\n'
        '    elif [ $SVC_STARTED = 1 ] && [ $((NOW - LASTWD)) -ge 60 ]; then\n'
        '        LASTWD=$NOW\n'
        '        if [ -z "$(app_running)" ]; then\n'
        '            event "the EQ service was not running; starting it again: $(flat $(am startservice -n $SVC 2>&1))"\n'
        '        fi\n'
        '        if ! keep_alive; then\n'
        '            keep_start\n'
        '            event "the 48 kHz output keeper was not running; started it again"\n'
        '        fi\n'
        '    fi',
        '    if [ $GOOD = 0 ] || [ $((NOW - LASTCH)) -ge 60 ]; then\n'
        '        LASTCH=$NOW\n'
        '        [ -f $RES_LOG ] && chmod 644 $RES_LOG\n'
        '        [ -f $S/resampler.log ] && chmod 644 $S/resampler.log\n'
        '        [ -f $S/resampler.log.1 ] && chmod 644 $S/resampler.log.1\n'
        '    fi\n'
        '    write_status\n'
        '    if [ "$(getprop sys.boot_completed)" = 1 ]; then\n'
        '        [ -z "$BC_AT" ] && BC_AT=$NOW\n'
        '        sync_app_owner\n'
        '    fi\n'
        '    # Start the EQ service only after status is written, when NOT in a silent parked wake,\n'
        '    # and after the cold-boot AA/CarPlay connection runway (AA/CP TCP up, audio RUNNING, or +12 s).\n'
        '    if [ $SILENT_DORMANT = 0 ] && [ -n "$BC_AT" ]; then\n'
        '        _runway_ready=0\n'
        '        if [ $RUNWAY_WAIT -le 0 ] || [ $BC_AT -ge 60 ] || [ $((NOW - BC_AT)) -ge $RUNWAY_WAIT ] || pcm_running || proj_connected; then\n'
        '            _runway_ready=1\n'
        '        elif [ $RUNWAY_NOTED = 0 ]; then\n'
        '            RUNWAY_NOTED=1\n'
        '            event "deferring EqService and 48 kHz keeper for up to ${RUNWAY_WAIT} s cold-boot AA/CarPlay runway"\n'
        '        fi\n'
        '        if [ $SVC_STARTED = 0 ] && [ $_runway_ready = 1 ]; then\n'
        '            SVC_STARTED=1\n'
        '            LASTWD=$NOW\n'
        '            _am_out=$(flat $(am startservice -n $SVC 2>&1))\n'
        '            event "Android has started; starting the EQ service: $_am_out"\n'
        '            if keep_alive; then\n'
        '                event "48 kHz output keeper still running (pid $_kp): kept"\n'
        '            else\n'
        '                keep_start\n'
        '                event "48 kHz output keeper started"\n'
        '            fi\n'
        '            write_status\n'
        '        elif [ $SVC_STARTED = 1 ] && [ $((NOW - LASTWD)) -ge 60 ]; then\n'
        '            LASTWD=$NOW\n'
        '            if [ -z "$(app_running)" ]; then\n'
        '                _am_re=$(flat $(am startservice -n $SVC 2>&1))\n'
        '                event "the EQ service was not running; starting it again: $_am_re"\n'
        '            fi\n'
        '            if ! keep_alive; then\n'
        '                keep_start\n'
        '                event "the 48 kHz output keeper was not running; started it again"\n'
        '            fi\n'
        '        fi\n'
        '    fi',
        "boot.sh silent-wake + 12s cold-boot AA/CarPlay runway service start",
    )


def create_hmgapi_mod_scaffold(repo: Path, hmgapi_dir: Path) -> None:
    mod_dir = repo / "eq-engine"
    if mod_dir.exists():
        shutil.rmtree(mod_dir)
    (mod_dir / "icons").mkdir(parents=True, exist_ok=True)

    # Generate 99x99 Gen5W Home Screen launcher icons via HMG-API's mkicon.sh
    glyph_path = repo / "eq_glyph.png"
    draw_eq_glyph(glyph_path)
    subprocess.run(
        ["sh", str(hmgapi_dir / "tools/mkicon.sh"), str(glyph_path), str(mod_dir / "icons"), "60"],
        check=True,
    )
    glyph_path.unlink(missing_ok=True)

    mod_json = {
        "id": "eq-engine",
        "name": "EQ Engine",
        "version": "0.7.7",
        "hmgapi": ">=1.1.1",
        "authors": ["alexkozler", "lemoyneiv"],
        "license": "GPL-2.0-only",
        "repo": "https://github.com/alexkozler/Gen5W-EQ-Engine",
        "description": (
            "System-wide 10-band parametric and 5-band graphic equalizer, CarPlay TinyALSA audio EQ, "
            "very-high-quality sinc resampler tables, and lossless 48 kHz I2S output switcher for Gen5W head units."
        ),
        "live": True,
        "nemesis_conflicts": ["eqengine"],
        "apps": [
            {
                "apk": "app/EQEngine.apk",
                "package": "com.eqengine.app",
            }
        ],
        "services": [
            {
                "name": "boot",
                "script": "bin/boot.sh",
                "user": "root",
                "start": "boot",
                "restart": True,
            }
        ],
        "usb": ["EQENGINE", "EQENGINE_CURVES"],
        "vehicle": {
            "read": ["power", "doors.closed"],
        },
        "provides": {
            "launcher": [
                {
                    "title": "EQ Engine",
                    "package": "com.eqengine.app",
                    "class": ".MainActivity",
                    "icon": "icons/icon.png",
                    "icon_night": "icons/icon_night.png",
                    "icon_disabled": "icons/icon_disabled.png",
                    "icon_disabled_night": "icons/icon_disabled_night.png",
                }
            ],
            "star_key": [
                {
                    "id": "open",
                    "title": "EQ Engine",
                    "activity": {
                        "package": "com.eqengine.app",
                        "class": ".MainActivity",
                    },
                }
            ],
        },
    }
    (mod_dir / "mod.json").write_text(json.dumps(mod_json, indent=2) + "\n", encoding="utf-8")

    open_sh = mod_dir / "open.sh"
    open_sh.write_text(
        "#!/system/bin/sh\n"
        "am start -a android.intent.action.MAIN -n com.eqengine.app/.MainActivity >/dev/null 2>&1\n",
        encoding="utf-8",
    )
    open_sh.chmod(0o755)

    # Write build-hmgapi.sh to compile native binaries + APK with standard SDK/NDK and run all tests
    build_sh = repo / "build-hmgapi.sh"
    build_sh.write_text(
        """#!/bin/sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"
ANDROID_HOME=${ANDROID_HOME:?set ANDROID_HOME}
HMGAPI_SDK=${HMGAPI_SDK:?set HMGAPI_SDK}
HMGAPI_KEY=${HMGAPI_KEY:?set HMGAPI_KEY}
KEY_DIR=$(dirname "$HMGAPI_KEY")

# 1. Locate Android NDK r25c+ and SDK build-tools
if [ -z "${NDK:-}" ]; then
    if [ -d "$ANDROID_HOME/ndk" ]; then
        NDK=$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | sort -V | tail -1)
    elif [ -d "$HOME/ndk/android-ndk-r25c" ]; then
        NDK="$HOME/ndk/android-ndk-r25c"
    fi
fi
[ -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi19-clang" ] || {
    echo "build-hmgapi.sh: Android NDK armv7a-linux-androideabi19-clang not found under NDK=$NDK" >&2
    exit 1
}
export NDK

BT=$(ls -d "$ANDROID_HOME/build-tools"/* 2>/dev/null | sort -V | tail -1)
if [ -f "$ANDROID_HOME/platforms/android-19/android.jar" ]; then
    ANDROID_JAR="$ANDROID_HOME/platforms/android-19/android.jar"
else
    ANDROID_JAR=$(ls "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)
fi

echo "== 1. Building EQ Engine ARMv7 native libraries & helpers =="
sh "$HERE/native/build.sh"

echo "== 2. Building & signing EQEngine.apk (API 19) =="
W="$HERE/build-hmg"
rm -rf "$W"
mkdir -p "$W/classes" "$W/dex" "$W/res" "$HERE/eq-engine/app" "$HERE/eq-engine/lib" "$HERE/eq-engine/conf" "$HERE/eq-engine/bin"

"$BT/aapt2" compile --dir "$HERE/app/res" -o "$W/res/res.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$HERE/app/AndroidManifest.xml" \\
    --min-sdk-version 19 --target-sdk-version 19 --no-auto-version \\
    --java "$W/gen" -o "$W/base.apk" "$W/res/res.zip"
javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR" -d "$W/classes" \\
    $(find "$HERE/app/src" "$W/gen" -name '*.java' | sort)
"$BT/d8" --release --min-api 19 --lib "$ANDROID_JAR" --output "$W/dex" \\
    $(find "$W/classes" -name '*.class' | sort)
cp "$W/base.apk" "$W/unsigned.apk"
(cd "$W/dex" && zip -q -X "$W/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$W/unsigned.apk" "$W/aligned.apk"

APP_KEY=${EQ_APP_KEY:-$KEY_DIR/eq-engine-app.p12}
if [ ! -f "$APP_KEY" ]; then
    umask 077
    keytool -genkeypair -keystore "$APP_KEY" -storetype pkcs12 \\
        -storepass example -keypass example -alias app \\
        -keyalg RSA -keysize 2048 -validity 15000 \\
        -dname "CN=EQ Engine Gen5W" >/dev/null 2>&1
    umask 022
fi
"$BT/apksigner" sign --ks "$APP_KEY" --ks-pass pass:example --min-sdk-version 9 \\
    --v2-signing-enabled false --v3-signing-enabled false \\
    --out "$HERE/eq-engine/app/EQEngine.apk" "$W/aligned.apk" 2>/dev/null

# Stage native libraries, md5 manifests, audio_effects.conf, and hardened scripts into eq-engine/
cp "$HERE/native/out/libeqengine.so" \\
   "$HERE/native/out/libaudio-resampler.so" \\
   "$HERE/native/out/eqe_drvwatch.so" \\
   "$HERE/native/out/eqe_cpwatch.so" \\
   "$HERE/eq-engine/lib/"
for so in libaudio-resampler eqe_drvwatch eqe_cpwatch; do
    set -- $(md5sum "$HERE/native/out/${so}.so")
    echo "$1" > "$HERE/eq-engine/conf/${so}.md5"
done
cp "$HERE/pkg/conf/audio_effects.conf" "$HERE/eq-engine/conf/"
cp "$HERE/pkg/bin/boot.sh" "$HERE/pkg/bin/act.sh" \\
   "$HERE/native/out/alsactl_dump" "$HERE/native/out/eqe_lab" \\
   "$HERE/eq-engine/bin/"
chmod 755 "$HERE/eq-engine/bin/"* "$HERE/eq-engine/open.sh"

# Deterministic mod_stamp across staged payload so boot.sh fast-paths normal boots in <15ms
STAMP=$(cd "$HERE/eq-engine" && sha256sum lib/*.so conf/* bin/* | sha256sum | cut -d' ' -f1)
echo "0.7.7-$STAMP" > "$HERE/eq-engine/lib/mod_stamp"

echo "== 3. Running EQ Engine ASan/UBSan C filter, VHQ resampler, CarPlay watch & shell suites =="
export ASAN_OPTIONS=detect_leaks=0
sh "$HERE/vhq/host/run.sh"
gcc -std=gnu11 -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer \\
    -I"$HERE/tests" "$HERE/tests/host_test.c" -o "$W/host_test" -ldl -lpthread -lm
"$W/host_test"
sh "$HERE/native/cpwtest/run.sh"
EQE_CAR="$HERE/car-files" bash "$HERE/tests/boot/run.sh" dash
sh "$HERE/test-hmgapi.sh"

echo "== 4. Checking HMG-API points, head-unit shell commands & signing eq-engine 0.7.7 =="
python3 "$HMGAPI_SDK/bin/check-points.py" "$HERE/eq-engine"
sh "$HMGAPI_SDK/bin/check-scripts.sh" "$HERE/eq-engine"
PKG_OUT=$(OUT="$HERE/out" sh "$HMGAPI_SDK/bin/mkmodpkg.sh" "$HERE/eq-engine" "$HMGAPI_KEY")
echo "$PKG_OUT"
SIGNED_ZIP=$(echo "$PKG_OUT" | tail -1)
OUT="$HERE/out" sh "$HMGAPI_SDK/bin/mknemesismod.sh" "$SIGNED_ZIP"

mkdir -p "$HERE/dist"
cp "$SIGNED_ZIP" "$HERE/dist/eq-engine-v0.7.7.hmgapi.zip"
cp "$HERE/out/dist/eq-engine-0.7.7-nemesis-package.zip" "$HERE/dist/eq-engine-v0.7.7-nemesis-package.zip"
python3 - "$HERE/eq-engine/open.sh" "$HERE/dist/eq-engine-v0.7.7-nemesis-package.zip" <<'PY'
import sys, zipfile
open_bytes = open(sys.argv[1], "rb").read()
pkg = sys.argv[2]
with zipfile.ZipFile(pkg, "r") as z:
    items = [(i, z.read(i)) for i in z.infolist()]
with zipfile.ZipFile(pkg, "w") as z:
    for info, data in items:
        z.writestr(info, open_bytes if info.filename == "bin/open.sh" else data)
PY
""",
        encoding="utf-8",
    )
    build_sh.chmod(0o755)

    # Write test-hmgapi.sh verifying all 6 suite hardening behaviors + HMG-API Engine plan/apply/remove
    test_sh = repo / "test-hmgapi.sh"
    test_sh.write_text(
        """#!/bin/sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
M="$HERE/eq-engine"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
R="$T/root"
. "${HMGAPI_SDK:?set HMGAPI_SDK}/test/lib.sh"

mkdir -p "$R/system" "$R/data/hmgapi/settings"
printf 'ro.board.platform=tcc803x\\n' > "$R/props.txt"

# 1. Verify icons, APK contents, tmpfs paths in compiled DEX & ELF
for ic in icon.png icon_night.png icon_disabled.png icon_disabled_night.png; do
    check "eq-engine launcher tile $ic exists" yes "$(test -f "$M/icons/$ic" && echo yes || echo no)"
done
DEX_STRINGS=$(unzip -p "$M/app/EQEngine.apk" classes.dex | strings)
has "EQEngine.apk uses tmpfs /dev/eqengine/cpw/live.bin" "/dev/eqengine/cpw/live.bin" "$DEX_STRINGS"
has "EQEngine.apk uses tmpfs /dev/eqengine/share/cp_eq.bin" "/dev/eqengine/share/cp_eq.bin" "$DEX_STRINGS"
has "EQEngine.apk reads tmpfs /dev/eqengine/resampler.log" "/dev/eqengine/resampler.log" "$DEX_STRINGS"
CPW_STRINGS=$(strings "$M/lib/eqe_cpwatch.so")
has "eqe_cpwatch.so uses tmpfs /dev/eqengine/cpw" "/dev/eqengine/cpw" "$CPW_STRINGS"
has "eqe_cpwatch.so uses tmpfs /dev/eqengine/share/cp_eq.bin" "/dev/eqengine/share/cp_eq.bin" "$CPW_STRINGS"

# 2. HMG-API Engine plan / apply / remove verification
install_mod "$M"
eng plan
check "eq-engine plan succeeds" 0 "$rc"
has "plan lists EQEngine.apk" "com.eqengine.app (app/EQEngine.apk)" "$out"
has "plan lists boot service at boot" "service  eq-engine/boot as root, at boot" "$out"
check "plan has zero problems" no "$(echo "$out" | grep -q '^problem ' && echo yes || echo no)"

eng apply
check "eq-engine apply succeeds" 0 "$rc"
has "eq-engine in public state.json" '"eq-engine"' "$(cat "$R/data/hmgapi/public/state.json")"
has "EQENGINE USB folder registered" "eq-engine EQENGINE" "$(cat "$R/data/hmgapi/state/usb.txt")"
has "EQENGINE_CURVES USB folder registered" "eq-engine EQENGINE_CURVES" "$(cat "$R/data/hmgapi/state/usb.txt")"
has "vehicle readings power & doors.closed registered" "power" "$(cat "$R/data/hmgapi/state/vehicle.txt")"
has "vehicle reading doors.closed registered" "doors.closed" "$(cat "$R/data/hmgapi/state/vehicle.txt")"
has "boot service scheduled in services.txt" "eq-engine boot root boot yes script bin/boot.sh" "$(cat "$R/data/hmgapi/state/services.txt")"

# 3. Verify silent parked wake dormancy, cold-boot 12s runway deferral, 30s logcat cap, hmgapid act.sh on, and SIGTERM unmount
SROOT="$T/sim"
mkdir -p "$SROOT/system/etc" "$SROOT/vendor/etc" "$SROOT/data/eqengine/lib" "$SROOT/data/eqengine/conf" \\
         "$SROOT/data/eqengine/bin" "$SROOT/dev" "$SROOT/proc/sys/kernel/random" "$SROOT/proc/net" "$T/stubs"
cp "$HERE/car-files/audio_effects.conf" "$SROOT/system/etc/audio_effects.conf"
cp "$M/conf/audio_effects.conf" "$SROOT/data/eqengine/conf/audio_effects.conf"
cp "$M/lib/libeqengine.so" "$SROOT/data/eqengine/lib/libeqengine.so"
cp "$M/bin/act.sh" "$SROOT/data/eqengine/bin/act.sh"
echo "5.00 2.00" > "$SROOT/proc/uptime"
echo "boot-suite-1" > "$SROOT/proc/sys/kernel/random/boot_id"
echo "/dev/block/system /system ext4 ro 0 0" > "$SROOT/proc/mounts"
mkdir -p "$SROOT/proc/200"
echo "200 (mediaserver) S 1 200 0 0 -1 4194560 100 0 0 0 10 5 0 0 20 0 9 0 280 30000000 2000 4294967295" > "$SROOT/proc/200/stat"
echo "b6f00000-b6f10000 r-xp 00000000 b3:19 11 /data/eqengine/lib/libeqengine.so" > "$SROOT/proc/200/maps"
echo "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode" > "$SROOT/proc/net/tcp"

cat > "$T/stubs/md5" <<'EOF'
#!/bin/sh
md5sum "$1"
EOF
cat > "$T/stubs/mount" <<EOF
#!/bin/sh
cp "\\$4" "\\$4.orig" && cp "\\$3" "\\$4" && echo "none \\$4 none rw 0 0" >> "$SROOT/proc/mounts"
EOF
cat > "$T/stubs/umount" <<EOF
#!/bin/sh
[ -f "\\$1.orig" ] && cp "\\$1.orig" "\\$1" && rm -f "\\$1.orig"
grep -v " \\$1 " "$SROOT/proc/mounts" > "$SROOT/proc/mounts.tmp" || true
mv "$SROOT/proc/mounts.tmp" "$SROOT/proc/mounts"
EOF
cat > "$T/stubs/ps" <<EOF
#!/bin/sh
echo "USER PID PPID VSIZE RSS WCHAN PC S NAME"
echo "media 200 1 33148 9784 ffffffff b6ea0870 S /system/bin/mediaserver"
EOF
cat > "$T/stubs/getprop" <<EOF
#!/bin/sh
[ "\\$1" = "sys.boot_completed" ] && echo 1
exit 0
EOF
cat > "$T/stubs/setprop" <<EOF
#!/bin/sh
echo "\\$1=\\$2" >> "$T/setprop.log"
EOF
cat > "$T/stubs/am" <<EOF
#!/bin/sh
read u _ < "$SROOT/proc/uptime"
echo "\\${u%%.*} \\$*" >> "$T/am.log"
echo "Starting service: Intent { cmp=\\$3 }"
EOF
cat > "$T/stubs/logcat" <<EOF
#!/bin/sh
echo "\\$*" >> "$T/logcat.log"
mkdir -p "$SROOT/proc/\\$\\$"
printf 'logcat\\000' > "$SROOT/proc/\\$\\$/cmdline"
exec /bin/sleep 60
EOF
cat > "$T/stubs/keeper" <<EOF
#!/bin/sh
read u _ < "$SROOT/proc/uptime"
echo "\\${u%%.*} keeper" >> "$T/keeper.log"
mkdir -p "$SROOT/proc/\\$\\$"
printf 'sh\\000act.sh\\000keep\\000' > "$SROOT/proc/\\$\\$/cmdline"
EOF
cat > "$T/stubs/hmgapid" <<EOF
#!/bin/sh
echo "hmgapid \\$*" >> "$T/hmgapid.log"
if [ "\\$1" = "vehicle" ]; then
    cat "$T/veh.txt" 2>/dev/null
fi
EOF
cat > "$T/stubs/sleep" <<EOF
#!/bin/sh
read u r < "$SROOT/proc/uptime"; u=\\${u%%.*}; echo "\\$((u + \\$1)).00 2.00" > "$SROOT/proc/uptime"
/bin/sleep 0.02
EOF
chmod +x "$T/stubs/"*

# 3a. Silent parked wake (power=delay_mode, doors.closed=1): overlay mounted, but ZERO EqService, ZERO keeper, ZERO logcat!
printf 'power delay_mode\\ndoors.closed 1\\n' > "$T/veh.txt"
PATH="$T/stubs:/usr/bin:/bin" EQE_TEST_ROOT="$SROOT" EQE_PROC="$SROOT/proc" \\
    EQE_RAM_DIR="$SROOT/dev/eqengine" EQE_HMGAPID="$T/stubs/hmgapid" \\
    EQE_RUNWAY_WAIT=12 EQE_CAP_MAX=6 EQE_GOOD_AFTER=10 EQE_KEEP_CMD="$T/stubs/keeper" \\
    sh "$M/bin/boot.sh" >/dev/null 2>&1 &
BPID=$!

i=0
while [ $i -lt 50 ] && ! grep -q "silent parked wake detected" "$SROOT/data/eqengine/state/events.log" 2>/dev/null; do
    /bin/sleep 0.02
    i=$((i + 1))
done
has "silent parked wake logged dormancy" "silent parked wake detected" "$(cat "$SROOT/data/eqengine/state/events.log")"
check "silent parked wake did NOT start EqService" "no" "$(test -f "$T/am.log" && echo yes || echo no)"
check "silent parked wake did NOT start 48 kHz keeper" "no" "$(test -f "$T/keeper.log" && echo yes || echo no)"
check "silent parked wake did NOT start logcat" "no" "$(test -f "$T/logcat.log" && echo yes || echo no)"

# 3b. Transition to power=on at uptime ~7s -> holds 12s cold-boot runway until AA/CP connects or +12s, then starts EqService & keeper!
echo "6.00 2.00" > "$SROOT/proc/uptime"
printf 'power on\\ndoors.closed 1\\n' > "$T/veh.txt"
i=0
while [ $i -lt 80 ] && ! grep -q "Android has started; starting the EQ service" "$SROOT/data/eqengine/state/events.log" 2>/dev/null; do
    /bin/sleep 0.03
    i=$((i + 1))
done
has "cold-boot runway deferral logged" "deferring EqService and 48 kHz keeper" "$(cat "$SROOT/data/eqengine/state/events.log")"
has "EqService started after runway window" "startservice -n com.eqengine.app/.EqService" "$(cat "$T/am.log")"
has "48 kHz keeper started after runway window" "keeper" "$(cat "$T/keeper.log")"

# Wait for 6s CAP_MAX auto-stop of resampler.log logcat capture
i=0
while [ $i -lt 60 ] && [ -f "$SROOT/dev/eqengine/resampler.pid" ]; do
    /bin/sleep 0.03
    i=$((i + 1))
done
check "resampler.log logcat capture auto-stopped at CAP_MAX" "no" "$(test -f "$SROOT/dev/eqengine/resampler.pid" && echo yes || echo no)"

# 3c. Service restart SIGTERM (mod still enabled) keeps overlay mounted; mod-disable SIGTERM unmounts!
kill -TERM "$BPID"
wait "$BPID" 2>/dev/null || true
check "SIGTERM while enabled keeps audio_effects.conf overlay in place" \\
    "69613834029b0e891c927e5843077c0d" \\
    "$(md5sum "$SROOT/system/etc/audio_effects.conf" | cut -d' ' -f1)"

PATH="$T/stubs:/usr/bin:/bin" EQE_TEST_ROOT="$SROOT" EQE_PROC="$SROOT/proc" \\
    EQE_RAM_DIR="$SROOT/dev/eqengine" EQE_HMGAPID="$T/stubs/hmgapid" \\
    EQE_RUNWAY_WAIT=0 EQE_CAP_MAX=6 EQE_GOOD_AFTER=10 EQE_KEEP_CMD="$T/stubs/keeper" \\
    sh "$M/bin/boot.sh" >/dev/null 2>&1 &
BPID=$!
/bin/sleep 0.1
: > "$SROOT/mod_disabled"
kill -TERM "$BPID"
wait "$BPID" 2>/dev/null || true
check "SIGTERM on mod disable unmounted audio_effects.conf back to stock" \\
    "4d98c1b38bd1d9eaf929d9b616c0d118" \\
    "$(md5sum "$SROOT/system/etc/audio_effects.conf" | cut -d' ' -f1)"
has "SIGTERM trap reset af.resampler.quality=0" "af.resampler.quality=0" "$(cat "$T/setprop.log")"
has "SIGTERM trap wrote state=off" "state=off" "$(cat "$SROOT/data/eqengine/state/status")"

# 3d. Verify act.sh on invokes hmgapid service-restart eq-engine boot
echo "state=applied" > "$SROOT/data/eqengine/state/status"
PATH="$T/stubs:/usr/bin:/bin" EQE_TEST_ROOT="$SROOT" EQE_HMGAPID="$T/stubs/hmgapid" \\
    sh "$M/bin/act.sh" on >/dev/null
has "act.sh on restarted eq-engine boot via hmgapid" "hmgapid service-restart eq-engine boot" "$(cat "$T/hmgapid.log")"

eng remove eq-engine
check "eq-engine remove succeeds" 0 "$rc"
exit "$fail"
""",
        encoding="utf-8",
    )
    test_sh.chmod(0o755)


def patch_boot_test_speedup(repo: Path) -> None:
    run_sh = repo / "tests/boot/run.sh"
    replace_exact(
        run_sh,
        "/bin/sleep 0.02",
        ":",
        "tests/boot/run.sh zero-fork stub sleep",
    )
    replace_exact(
        run_sh,
        "    i=0; while kill -0 $BP 2>/dev/null && [ $i -lt $(( ${1:-300} * 4 )) ]; do /bin/sleep 0.05; i=$((i+1)); done",
        "    i=0; while kill -0 $BP 2>/dev/null && [ ! -f $T/stop ] && [ $i -lt $(( ${1:-300} * 4 )) ]; do /bin/sleep 0.01; i=$((i+1)); done",
        "tests/boot/run.sh fast runboot loop",
    )
    replace_exact(
        run_sh,
        "runboot() {  # runs until the script exits or ~$1 simulated seconds pass",
        "runboot() {  # runs until the script exits or ~$1 simulated seconds pass\n    rm -f $T/stop",
        "tests/boot/run.sh clear stop before runboot",
    )
    text = run_sh.read_text(encoding="utf-8")
    assert "/bin/sleep 0.05" in text
    text = text.replace("/bin/sleep 0.05", "/bin/sleep 0.01")
    text += "\n[ \"$fail\" = 0 ]\n"
    run_sh.write_text(text, encoding="utf-8")


def prepare_eq_engine(repo: Path, hmgapi_dir: Path) -> None:
    print("== Preparing eq-engine 0.7.7 (on alexkozler/Gen5W-EQ-Engine @ 2feb050)")
    reconstruct_stock_audio_effects(repo)
    patch_native_and_app(repo)
    patch_act_sh(repo)
    patch_boot_sh(repo)
    patch_boot_test_speedup(repo)
    create_hmgapi_mod_scaffold(repo, hmgapi_dir)
