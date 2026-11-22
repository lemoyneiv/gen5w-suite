#!/usr/bin/env python3
"""
prepare-mods.py - Reproducibly applies Gen5W Suite customizations to clean
working copies of the three patched HMG-API mods (while `adb` and `egmp-aa-ev`
consume unmodified official upstream-signed release archives and `hmgapi-comfort`
builds directly from its standalone `lemoyneiv/hmgapi-comfort` repository):

  1. wireless-carplay-aa (2.2.2 on Open-HMG v2.2.0-rc.8 + #94):
     - PR #16 subset: Keyframes IDR screen-freeze recovery, CpTurns highway/
       rotary/stale-arrow fixes (composed with rc.8 #33 standard turns), and
       post-route Compass replay (NO Watchdog).
     - PR #28: opt-in wifi_direct part (off by default).
     - PR #56: Projection Focus Arbitrator (FocusGuard / FocusPolicy), enabled by
       default in Gen5W Suite so Phone Projection wins startup/connect focus races
       against Bluetooth Audio while preserving FM/AM/SiriusXM Radio, passenger BT
       streaming after startup, and reverse/AVM cameras.
     - Upstream v2.2.0-rc.8 + #94 already provides PR #2, #33, #51, #54, #55, #58,
       #84, #86, #94 (NEEV/NEEV23/NEEV23SE 260507+260903, enhanced.projection
       conflict, atomic leafiaaac.c + LPCM bitsPerChannel==0, CarPlay Now Playing &
       AR HUD, CarPlay log switch, "connect a phone" popup Settings fix, and #94
       revert of #69).
  2. aa-dpi (0.3.0):
     - nemesis_conflicts: ["aa.dpi"], defaults (dpi=200, tap_limit=false, video_window=6).
  3. light-vibes (0.7.0):
     - models="any" in NEMESIS.data + Linux Android SDK fallback.
"""

import argparse
import json
import shutil
import stat
import subprocess
import sys
from pathlib import Path


PATCH_DIR = Path(__file__).resolve().parent


def write_json(path: Path, obj: dict) -> None:
    path.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")


def run(cmd, cwd=None) -> None:
    subprocess.run(cmd, cwd=cwd, check=True)


def add_coauthor(mod: dict, author: str = "lemoyneiv") -> None:
    authors = list(mod.get("authors") or [])
    if author not in authors:
        authors.append(author)
    mod["authors"] = authors


def replace_exact(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    assert old in text, f"Expected {label} block not found in {path}"
    assert text.count(old) == 1, f"Ambiguous {label} block in {path}"
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_wireless(repo: Path) -> None:
    print("== Preparing wireless-carplay-aa 2.2.2 (on Open-HMG v2.2.0-rc.8 + #94)")
    patch_file = PATCH_DIR / "wireless-pr16-no-watchdog.patch"
    run(["git", "apply", str(patch_file)], cwd=repo)

    # Verify PR #16 Watchdog was never introduced anywhere
    assert not (repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/dipo/Watchdog.java").exists()
    assert not (repo / "wireless-carplay-aa/tools/watchdog").exists()

    dipo_java = repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/dipo/Dipo.java"
    dsrc = dipo_java.read_text(encoding="utf-8")
    assert "dipo.carplay.disable_bluetooth" in dsrc or "dipo.carplay.disable_bluetooth" in (
        repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/dipo/Bridge.java"
    ).read_text(encoding="utf-8"), "Expected upstream PR #2 hook"
    assert "bridge_watchdog" not in dsrc.lower()

    # Verify upstream v2.2.0-rc.8 + #94 already includes PR #54, #55, #58, #86
    assert (repo / "wireless-carplay-aa/tools/leafiaaac/test.sh").exists(), "Expected upstream PR #55 leafiaaac test"
    test_sh_text = (repo / "wireless-carplay-aa/test.sh").read_text(encoding="utf-8")
    assert '"hmgapi@>=1.0,!wireless.projection,!projection.tbt,!enhanced.projection"' in test_sh_text

    # Update mod.json (preserve upstream v2.2.0-rc.8 system matrix, hotspot, CarPlay identity, popup hook, and conflicts)
    mod_path = repo / "wireless-carplay-aa/mod.json"
    mod = json.loads(mod_path.read_text(encoding="utf-8"))
    mod["version"] = "2.2.2"
    mod["hmgapi"] = ">=1.1.0"
    assert mod.get("wifi") == {"hotspot": True}, f"Expected wifi.hotspot in rc.8 mod.json, got {mod.get('wifi')}"
    assert "libiap2session.so" in mod["hooks"]["native"], "Expected CarPlay identity recipe in rc.8 mod.json"
    assert "enhanced.projection" in mod.get("nemesis_conflicts", []), "Expected enhanced.projection in rc.8 mod.json"
    add_coauthor(mod)

    if not any(p.get("id") == "wifi_direct" for p in mod["parts"]):
        mod["parts"].append({"id": "wifi_direct", "when": "settings.wifi_direct"})

    pts = mod["hooks"]["points"]
    pts["dipo.carplay.decode_failure"] = "observe"
    pts["dipo.carplay.screen_timeout"] = "observe"
    pts["gpm.sensor_source.init"] = "observe"
    pts["gpm.receiver_service.disconnected"] = "observe"
    write_json(mod_path, mod)

    # Update settings.json (PR #16 picture + tbt_no_icon, PR #28 wifi_direct, PR #56 projection_focus=True, NO bridge_watchdog)
    settings_path = repo / "wireless-carplay-aa/settings.json"
    sdata = json.loads(settings_path.read_text(encoding="utf-8"))
    new_settings = []
    for item in sdata["settings"]:
        if item["key"] == "bridge_watchdog":
            continue
        new_settings.append(item)
        if item["key"] == "tbt":
            new_settings.append(
                {
                    "key": "tbt_no_icon",
                    "type": "choice",
                    "default": "straight",
                    "title": "CarPlay turns with no arrow",
                    "apply": "restart",
                    "description": (
                        "A maneuver the cluster has no icon for, after the nearest one is tried "
                        "(the start of a route, say). Clearing the turn or an empty turn shows "
                        "the cluster's spinner and hides the distance."
                    ),
                    "options": [
                        {"value": "straight", "title": "Straight arrow with the distance"},
                        {"value": "clear", "title": "Clear the turn from the cluster"},
                        {"value": "skip", "title": "Leave the last turn up (2.1.0)"},
                        {"value": "no_info", "title": "Send an empty turn (can show a spinner)"},
                    ],
                }
            )
        elif item["key"] == "audio":
            new_settings.extend(
                [
                    {
                        "key": "projection_focus",
                        "type": "toggle",
                        "default": True,
                        "title": "Phone Projection wins startup focus races against Bluetooth Audio",
                        "apply": "restart",
                        "description": (
                            "Prevents Bluetooth Audio from stealing audio, mode, or screen focus while "
                            "Wireless CarPlay or Android Auto is connecting at startup, and reclaims focus "
                            "once if Bluetooth Audio grabbed it earlier during head-unit boot."
                        ),
                    },
                    {
                        "key": "picture",
                        "type": "toggle",
                        "default": True,
                        "title": "CarPlay picture recovers from a freeze",
                        "apply": "restart",
                        "description": (
                            "A hook in the CarPlay app (no files changed). When the video decoder "
                            "reports a failure or the screen stream times out, asks the phone for a "
                            "new full frame, as leaving CarPlay and coming back does, without the flicker."
                        ),
                    },
                    {
                        "key": "picture_periodic",
                        "type": "toggle",
                        "default": False,
                        "title": "On wireless CarPlay, also ask for a full frame regularly",
                        "apply": "restart",
                        "description": (
                            "For a picture that freezes without the decoder reporting it. Caps a "
                            "freeze at the interval below, for one extra full frame each time."
                        ),
                    },
                    {
                        "key": "picture_period",
                        "type": "choice",
                        "default": "5",
                        "title": "Full frame interval",
                        "apply": "restart",
                        "description": "How often the regular full frame is asked for. node-CarPlay uses 5 seconds.",
                        "options": [
                            {"value": "5", "title": "5 seconds"},
                            {"value": "10", "title": "10 seconds"},
                            {"value": "20", "title": "20 seconds"},
                            {"value": "30", "title": "30 seconds"},
                        ],
                    },
                    {
                        "key": "wifi_direct",
                        "type": "toggle",
                        "default": False,
                        "apply": "rebuild",
                        "title": "Wi-Fi Direct (test: Android phones stuck on Connecting)",
                        "description": (
                            "Turns on Wi-Fi Direct in the car's Wi-Fi settings (p2p_disabled=0) and "
                            "lifts its 5 GHz limit (p2p_no_go_freq), as NEMESIS's Enable Wireless "
                            "Features package does. For phones that find the car's hotspot but never "
                            "connect (#18); check the car's ADB TCP switch first. Off: the car's own "
                            "settings, put back exactly."
                        ),
                    },
                ]
            )
    sdata["settings"] = new_settings
    assert all(x["key"] != "bridge_watchdog" for x in sdata["settings"])
    write_json(settings_path, sdata)

    # Update patches.json (preserve upstream rc.5 cars mapping, append opt-in PR #28 wifi_direct props)
    patches_path = repo / "wireless-carplay-aa/patches.json"
    pdata = json.loads(patches_path.read_text(encoding="utf-8"))
    assert pdata["cars"].get("NEEV23SE.USA.") == "neev", "Expected NEEV23SE.USA. in rc.5 patches.json"
    pdata["changes"].extend(
        [
            {
                "type": "props",
                "path": "/system/etc/wifi/wpa_supplicant.conf",
                "stock": "any",
                "set": {"p2p_disabled": "0"},
                "part": "wifi_direct",
            },
            {
                "type": "props",
                "path": "/system/etc/wifi/wpa_supplicant_overlay.conf",
                "stock": "any",
                "remove": ["p2p_no_go_freq"],
                "part": "wifi_direct",
            },
        ]
    )
    write_json(patches_path, pdata)

    # Install reviewed PR #56 FocusPolicy + FocusGuard (with projection_focus default=True for Gen5W Suite)
    focus_src_dir = PATCH_DIR / "wireless-focus"
    mod_wireless_dir = repo / "wireless-carplay-aa/src/hmgapi/mods/wireless"
    shutil.copy2(focus_src_dir / "FocusPolicy.java", mod_wireless_dir / "FocusPolicy.java")
    shutil.copy2(focus_src_dir / "FocusGuard.java", mod_wireless_dir / "FocusGuard.java")

    # Wire Wireless.java (btaudio.ui + GPM hooks + DIPO entry condition)
    wireless_java = mod_wireless_dir / "Wireless.java"
    replace_exact(
        wireless_java,
        """    @Override
    public void appLoaded(final ModContext mod, LoadedApp app) {
        if (NAVIGATION.equals(app.packageName)) navigation(mod, app);
        if (IAP2.equals(app.packageName)) iap2(mod, app);
        if (GPM.equals(app.packageName) && mod.settingBoolean("usb_drives", true)) usbDrives(mod, app);
        if (GPM.equals(app.packageName) && mod.settingBoolean("tbt", true)) gpmTurnByTurn(mod, app);
        if (BTSETTINGS.equals(app.packageName) && mod.settingBoolean("menu", true)) phoneProjectionMenu(mod, app);
        if (SYSTEM_POPUP.equals(app.packageName) && mod.settingBoolean("menu", true)) popupSettings(mod, app);
        if (DIPO.equals(app.packageName) && (mod.settingBoolean("tbt", true) || mod.settingBoolean("bridge", true) || mod.settingBoolean("audio", true) || mod.settingBoolean("picture", true))) dipo(mod, app);
    }""",
        """    @Override
    public void appLoaded(final ModContext mod, LoadedApp app) {
        if (NAVIGATION.equals(app.packageName)) navigation(mod, app);
        if (IAP2.equals(app.packageName)) iap2(mod, app);
        if (FocusPolicy.PKG_BTAUDIO.equals(app.packageName) && mod.settingBoolean("projection_focus", true)) FocusGuard.btAudio(mod, app);
        if (GPM.equals(app.packageName) && (mod.settingBoolean("usb_drives", true) || mod.settingBoolean("projection_focus", true))) usbDrives(mod, app);
        if (GPM.equals(app.packageName) && mod.settingBoolean("projection_focus", true)) FocusGuard.gpm(mod, app);
        if (GPM.equals(app.packageName) && mod.settingBoolean("tbt", true)) gpmTurnByTurn(mod, app);
        if (BTSETTINGS.equals(app.packageName) && mod.settingBoolean("menu", true)) phoneProjectionMenu(mod, app);
        if (SYSTEM_POPUP.equals(app.packageName) && mod.settingBoolean("menu", true)) popupSettings(mod, app);
        if (DIPO.equals(app.packageName) && (mod.settingBoolean("tbt", true) || mod.settingBoolean("bridge", true) || mod.settingBoolean("audio", true) || mod.settingBoolean("picture", true) || mod.settingBoolean("projection_focus", true))) dipo(mod, app);
    }""",
        "Wireless.appLoaded",
    )
    replace_exact(
        wireless_java,
        """    private void usbDrives(final ModContext mod, LoadedApp app) {
        mod.hook(null, "framework.context_impl.start_service", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Intent i = (Intent) p.args[0];
                ComponentName c = i == null ? null : i.getComponent();
                if (c == null || !GAL_SERVICE.equals(c.getClassName()) || i.getIntExtra("startMode", -1) != 1) return;
                UsbDevice d = i.getParcelableExtra("device");
                if (!notPhone(d)) return;
                p.setResult(null);
                mod.log("USB drive or hub (" + d.getDeviceName() + "), not starting wired Android Auto");
            }
        });
        mod.log("USB drive hook in " + app.processName);
    }""",
        """    private void usbDrives(final ModContext mod, final LoadedApp app) {
        mod.hook(null, "framework.context_impl.start_service", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Intent i = (Intent) p.args[0];
                ComponentName c = i == null ? null : i.getComponent();
                if (c == null || !GAL_SERVICE.equals(c.getClassName())) return;
                int startMode = i.getIntExtra("startMode", -1);
                if (startMode == 1 && mod.settingBoolean("usb_drives", true)) {
                    UsbDevice d = i.getParcelableExtra("device");
                    if (notPhone(d)) {
                        p.setResult(null);
                        mod.log("USB drive or hub (" + d.getDeviceName() + "), not starting wired Android Auto");
                        return;
                    }
                }
                if (startMode > 0 && mod.settingBoolean("projection_focus", true)) {
                    FocusGuard.onAaConnecting(mod, app.context);
                }
            }
        });
        if (mod.settingBoolean("usb_drives", true)) mod.log("USB drive hook in " + app.processName);
    }""",
        "Wireless.usbDrives",
    )

    # Wire Dipo.java via FocusGuard.dipo(m, a) (AppPartLoader delegates hmgapi.mods.wireless.* to main loader)
    replace_exact(
        dipo_java,
        "import hmgapi.hook.Points;\n",
        "import hmgapi.hook.Points;\nimport hmgapi.mods.wireless.FocusGuard;\n",
        "Dipo FocusGuard import",
    )
    replace_exact(
        dipo_java,
        "        if (tbt) turnByTurn();\n",
        "        if (FocusGuard.active(m)) FocusGuard.dipo(m, a);\n        if (tbt) turnByTurn();\n",
        "Dipo.start FocusGuard.dipo",
    )

    # Wire Bridge.java (notify FocusGuard during wireless CarPlay SPP handshake, skip post-disconnect onUsbGone,
    # and suppress Bridge SPP/SoftAP while Home Wi-Fi or parked keep-awake owns wlan0)
    bridge_java = repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/dipo/Bridge.java"
    replace_exact(
        bridge_java,
        "import hmgapi.hook.Points;\n",
        "import hmgapi.hook.Points;\nimport hmgapi.mods.wireless.FocusGuard;\nimport hmgapi.mods.wireless.FocusPolicy;\n",
        "Bridge FocusGuard+FocusPolicy import",
    )
    replace_exact(
        bridge_java,
        """    static boolean enabled() {
        return !"0".equals(prop("persist.ev6.cpwl.enable", "1"));
    }""",
        """    static boolean enabled() {
        if ("0".equals(prop("persist.ev6.cpwl.enable", "1"))) return false;
        return !FocusPolicy.isHomeWifiOrParkedLeaseActiveOnDevice(
                android.os.SystemClock.elapsedRealtime(),
                prop("init.svc.wpa_supplicant", ""));
    }""",
        "Bridge.enabled HomeWiFi/parked guard",
    )
    replace_exact(
        bridge_java,
        """    static void ensureHotspot(Context c) {
        try {
            Object wifi = c.getSystemService(Context.WIFI_SERVICE);""",
        """    static void ensureHotspot(Context c) {
        if (!enabled()) {
            log("skipping hotspot: Home Wi-Fi or parked keep-awake owns wlan0");
            return;
        }
        try {
            Object wifi = c.getSystemService(Context.WIFI_SERVICE);""",
        "Bridge.ensureHotspot HomeWiFi/parked guard",
    )
    replace_exact(
        bridge_java,
        """    static void scheduleSppConnect(final String addr) {
        if (addr == null || !enabled() || sUsb || sSession || sCpWireless) return;
        handler().postDelayed(new Runnable() {""",
        """    static void scheduleSppConnect(final String addr) {
        if (addr == null || !enabled() || sUsb || sSession || sCpWireless) return;
        FocusGuard.onCpConnecting(Dipo.mod, context);
        handler().postDelayed(new Runnable() {""",
        "Bridge.scheduleSppConnect",
    )
    replace_exact(
        bridge_java,
        """    static void onSppUp(final String addr) {
        if (addr == null || sSession) return;
        sAddr = addr;
        if (sCpClient || sCpWireless || !enabled() || sUsb) return;
        sSession = true;
        handler().post(new Runnable() {""",
        """    static void onSppUp(String addr) {
        onSppUp(addr, true);
    }

    static void onSppUp(final String addr, boolean notifyFocus) {
        if (addr == null || sSession) return;
        sAddr = addr;
        if (sCpClient || sCpWireless || !enabled() || sUsb) return;
        sSession = true;
        if (notifyFocus) FocusGuard.onCpConnecting(Dipo.mod, context);
        handler().post(new Runnable() {""",
        "Bridge.onSppUp",
    )
    replace_exact(
        bridge_java,
        "if (m != null && !sUsb && !sSession && addr.equals(sAddr) && m.IbtcmIsSppConnectedDevice(addr)) onSppUp(addr);",
        "if (m != null && !sUsb && !sSession && addr.equals(sAddr) && m.IbtcmIsSppConnectedDevice(addr)) onSppUp(addr, false);",
        "Bridge.onUsbGone",
    )
    replace_exact(
        bridge_java,
        """        sAddr = null;
        if (sSession) {
            sSession = false;
            sendDetach();
        }""",
        """        sAddr = null;
        FocusGuard.onCpAborted(Dipo.mod, context);
        if (sSession) {
            sSession = false;
            sendDetach();
        }""",
        "Bridge.onSppDown",
    )

    # Install host unit test for FocusPolicy (tools/focus/test.sh)
    focus_test_dir = repo / "wireless-carplay-aa/tools/focus/test/hmgapi/mods/wireless"
    focus_test_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(focus_src_dir / "FocusPolicyTest.java", focus_test_dir / "FocusPolicyTest.java")
    focus_test_sh = repo / "wireless-carplay-aa/tools/focus/test.sh"
    focus_test_sh.write_text(
        """#!/bin/sh
# Host test of FocusPolicy: startup/connect focus arbitration, mid-session driver
# selection and passenger Bluetooth streaming freedom, radio preservation, and
# PGS + explicit camera package deferral.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" \\
    $(find test -name '*.java') \\
    ../../src/hmgapi/mods/wireless/FocusPolicy.java
java -cp "$CP" hmgapi.mods.wireless.FocusPolicyTest
""",
        encoding="utf-8",
    )
    focus_test_sh.chmod(focus_test_sh.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    # Wire FocusPolicy + FocusGuard + PR #16 Compass replay stubs in tools/aa-support
    aa_support_dir = repo / "wireless-carplay-aa/tools/aa-support"
    aa_support_root = aa_support_dir / "stubs"
    if aa_support_root.is_dir():
        replace_exact(
            aa_support_dir / "test.sh",
            "    ../../src/hmgapi/mods/wireless/AaTbtSupport.java \\\n    ../../src/hmgapi/mods/wireless/Wireless.java",
            "    ../../src/hmgapi/mods/wireless/AaTbtSupport.java \\\n"
            "    ../../src/hmgapi/mods/wireless/FocusPolicy.java \\\n"
            "    ../../src/hmgapi/mods/wireless/Wireless.java",
            "aa-support/test.sh FocusPolicy",
        )
        (aa_support_root / "hmgapi/mods/wireless/FocusGuard.java").write_text(
            """package hmgapi.mods.wireless;

import android.content.Context;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;

public final class FocusGuard {
    public static int btAudioCalls;
    public static int gpmCalls;
    public static int dipoCalls;
    public static int aaConnectingCalls;

    public static void reset() {
        btAudioCalls = 0;
        gpmCalls = 0;
        dipoCalls = 0;
        aaConnectingCalls = 0;
    }

    public static boolean active(ModContext mod) {
        return mod != null && mod.settingBoolean("projection_focus", true);
    }

    public static void btAudio(ModContext mod, LoadedApp app) {
        btAudioCalls++;
    }

    public static void gpm(ModContext mod, LoadedApp app) {
        gpmCalls++;
    }

    public static void dipo(ModContext mod, LoadedApp app) {
        dipoCalls++;
    }

    public static void onAaConnecting(ModContext mod, Context context) {
        aaConnectingCalls++;
    }
}
""",
            encoding="utf-8",
        )
        replace_exact(
            aa_support_root / "hmgapi/hook/MethodHook.java",
            "public Object thisObject;",
            "public java.lang.reflect.Member method;\n        public Object thisObject;",
            "MethodHook.method stub",
        )
        (aa_support_root / "android/os/Looper.java").write_text(
            """package android.os;

public final class Looper {
    public static Looper getMainLooper() { return new Looper(); }
}
""",
            encoding="utf-8",
        )
        replace_exact(
            aa_support_root / "android/os/Handler.java",
            "public boolean post(Runnable runnable) { pending.add(runnable); return true; }",
            "public boolean post(Runnable runnable) { pending.add(runnable); return true; }\n"
            "    public boolean postDelayed(Runnable runnable, long delayMillis) { return post(runnable); }",
            "Handler.postDelayed stub",
        )
        replace_exact(
            aa_support_dir / "test/hmgapi/mods/wireless/AaSupportTest.java",
            """            wireless.appLoaded(stock, new LoadedApp(Wireless.GPM));
            check(stock.natives.isEmpty() && stock.hooks.size() == 1, "native GPM TBT is preserved at step 999");
            Car.step = 105;""",
            """            wireless.appLoaded(stock, new LoadedApp(Wireless.GPM));
            check(stock.natives.isEmpty() && stock.hooks.size() == 1, "native GPM TBT is preserved at step 999");
            Car.step = 105;

            FocusGuard.reset();
            ModContext defaultFocus = enabled();
            wireless.appLoaded(defaultFocus, new LoadedApp(FocusPolicy.PKG_BTAUDIO));
            wireless.appLoaded(defaultFocus, new LoadedApp(Wireless.GPM));
            check(FocusGuard.btAudioCalls == 1 && FocusGuard.gpmCalls == 1,
                    "projection_focus is on by default in Gen5W Suite for btaudio and GPM");
            FocusGuard.reset();
            ModContext focusOff = new ModContext();
            focusOff.settings.put("tbt", false);
            focusOff.settings.put("usb_drives", false);
            focusOff.settings.put("bridge", false);
            focusOff.settings.put("audio", false);
            focusOff.settings.put("picture", false);
            focusOff.settings.put("projection_focus", false);
            wireless.appLoaded(focusOff, new LoadedApp(FocusPolicy.PKG_BTAUDIO));
            wireless.appLoaded(focusOff, new LoadedApp(Wireless.GPM));
            wireless.appLoaded(focusOff, new LoadedApp(Wireless.DIPO));
            check(FocusGuard.btAudioCalls == 0 && FocusGuard.gpmCalls == 0 && focusOff.cpStarts == 0,
                    "projection_focus=false disables FocusGuard and leaves DiPO off when all DiPO parts are off");""",
            "AaSupportTest FocusGuard assertions",
        )


def _adapt_engine_test_for_standalone_wireless(test_path: Path) -> None:
    s = test_path.read_text(encoding="utf-8")
    s = s.replace(
        'trap \'rm -rf "$CP" "$T"\' EXIT',
        'trap \'rm -rf "$CP" "$T"\' EXIT\n'
        'python3 "$HMGAPI/tools/maplib.py" expand "$T/expanded-maps"',
    )
    s = s.replace(
        'cp -r "$HMGAPI/maps" "$R/data/hmgapi/maps"',
        'cp -r "$T/expanded-maps" "$R/data/hmgapi/maps"',
    )
    s = s.replace(
        'cp -r "$HMGAPI/mods/wireless-carplay-aa" "$R/data/hmgapi/mods/wireless-carplay-aa"',
        'cp -r "${WIRELESS_MOD:-$HMGAPI/mods/wireless-carplay-aa}" "$R/data/hmgapi/mods/wireless-carplay-aa"\n'
        '    mkdir -p "$R/system/onebin/USA" "$R/system/etc/permissions" "$R/sys/bus/sdio/devices/mmc1:0001:1"\n'
        '    printf \'ro.build.product=%s\\nro.board.platform=tcc803x\\nro.build.version.release=4.4.2\\nro.mobis.onebindir=USA\\n\' "$1" > "$R/props.txt"\n'
        '    echo "-/system/etc/permissions/com.hkmc.software.wifihotspot.xml" > "$R/system/onebin/USA/featurexml.cfg"',
    )
    s = s.replace(
        'for cj in "$HMGAPI"/maps/*/*/*/*/car.json; do',
        'for cj in "$T/expanded-maps"/*/*/*/*/car.json; do\n    grep -q "no stock /system/app/HKMC_GPM.odex" "$cj" && continue',
    )
    s = s.replace(
        'for m in "$HMGAPI"/maps/*/*/*/*; do',
        'for c in "$T/expanded-maps"/*/*/*/*/car.json; do m=$(dirname "$c")',
    )
    s = s.replace('"$HMGAPI"/maps/*/*/*/*/car.json', '"$T/expanded-maps"/*/*/*/*/car.json')
    s = s.replace('$HMGAPI/maps/$1/car.json', '$T/expanded-maps/$1/car.json')
    s = s.replace('${m#$HMGAPI/maps/}', '${m#$T/expanded-maps/}')
    s = s.replace(
        'wireless="$HMGAPI/mods/wireless-carplay-aa/recipes/aa-tbt-$suffix.recipe"',
        'wireless="${WIRELESS_MOD:-$HMGAPI/mods/wireless-carplay-aa}/recipes/aa-tbt-$suffix.recipe"',
    )
    s = s.replace(
        'HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=$P',
        'HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=$P HMGAPI_ONEBIN=USA',
    )
    s = s.replace(
        '"hyundai/ceev/usa/260903 CEEV.USA.0000.V016.010.260903"',
        '"hyundai/ceev/can/260206 CEEV.CAN.0000.V015.011.260206"',
    )
    s = s.replace(
        'gone = [p for p in want if p not in have or p in missing]',
        'gone = [p for p in want if (p not in have or p in missing) and not missing.get(p, "").startswith("no stock ")]',
    )
    test_path.write_text(s, encoding="utf-8")


def patch_aa_dpi(repo: Path) -> None:
    print("== Preparing aa-dpi 0.3.0 (dpi=200, tap_limit=false, video_window=6)")
    mod_path = repo / "hmgapi-mod/mod.json"
    mod = json.loads(mod_path.read_text(encoding="utf-8"))
    assert mod["version"] == "0.3.0"
    add_coauthor(mod)
    mod["nemesis_conflicts"] = ["aa.dpi"]
    write_json(mod_path, mod)

    settings_path = repo / "hmgapi-mod/settings.json"
    sdata = json.loads(settings_path.read_text(encoding="utf-8"))
    found_tap = False
    for item in sdata["settings"]:
        if item["key"] == "tap_limit":
            item["default"] = False
            found_tap = True
    assert found_tap, "Expected tap_limit setting in aa-dpi settings.json"
    write_json(settings_path, sdata)

    defaults = {s["key"]: s["default"] for s in sdata["settings"]}
    assert defaults["dpi"] == 200, f"Expected dpi default 200, got {defaults['dpi']}"
    assert defaults["video_window"] == 6, f"Expected video_window default 6, got {defaults['video_window']}"
    assert defaults["tap_limit"] is False, f"Expected tap_limit default False, got {defaults['tap_limit']}"

    replace_exact(
        repo / "hmgapi-mod/hooks/dev/aadpi/hook/DpiHooks.java",
        'boolean limit = mod.settingBoolean("tap_limit", true);',
        'boolean limit = mod.settingBoolean("tap_limit", false);',
        "DpiHooks tap_limit fallback",
    )

    _adapt_engine_test_for_standalone_wireless(repo / "hmgapi-mod/test/engine-test.sh")


def patch_light_vibes(repo: Path) -> None:
    if not (repo / "build.sh").exists():
        return
    print("== Preparing light-vibes 0.7.0")
    mod_path = repo / "mod/mod.json"
    mod = json.loads(mod_path.read_text(encoding="utf-8"))
    assert mod["version"] == "0.7.0", f"Expected light-vibes 0.7.0, got {mod.get('version')}"
    add_coauthor(mod)
    mod["repo"] = "https://github.com/lemoyneiv/light-vibes"
    write_json(mod_path, mod)

    replace_exact(
        repo / "build.sh",
        "ANDROID_JAR=$SDK/platforms/android-19/android.jar",
        'if [ -f "$SDK/platforms/android-19/android.jar" ]; then ANDROID_JAR=$SDK/platforms/android-19/android.jar\n'
        'else ANDROID_JAR=$(ls "$SDK"/platforms/android-*/android.jar | sort -V | tail -1); fi',
        "light-vibes ANDROID_JAR fallback",
    )

    # Keep models="any" in NEMESIS.data (matching HMG-API's mknemesismod.sh) so standalone
    # NEMESIS USB install works on US Hyundai Ioniq 5 units where /system/onebin is "USA"
    replace_exact(
        repo / "tools/sign.py",
        'TESTED_MODELS = "cvev_us,sg2ev_us,neev_us,neev23_us,neev23se_us,ceev_us"',
        'TESTED_MODELS = "any"',
        "light-vibes TESTED_MODELS",
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--work-dir", required=True, help="Directory containing working copies of upstream mod repos")
    args = ap.parse_args()
    w = Path(args.work_dir).resolve()

    patch_wireless(w / "hmgapi-wireless-cp-aa")
    patch_aa_dpi(w / "hmg-aa-dpi")
    patch_light_vibes(w / "light-vibes")
    print("All patched mods prepared cleanly.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
