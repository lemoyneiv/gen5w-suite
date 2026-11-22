#!/usr/bin/env python3
"""
prepare-mods.py - Reproducibly applies Gen5W Suite customizations to clean
working copies of the three patched HMG-API mods (while `adb` and `egmp-aa-ev`
consume unmodified official upstream-signed release archives and `hmgapi-comfort`
builds directly from its standalone `lemoyneiv/hmgapi-comfort` repository):

  1. wireless-carplay-aa (2.2.3 on lemoyneiv/hmgapi-wireless-cp-aa @ 262554c):
     - Built on `feat/tbt-overhaul-and-cleanup` (`262554c`, based on Open-HMG
       `v2.2.0-rc.9`): dynamic native cluster & AR HUD intersection scenes
       (`tbt_standard` default `true`), `NaviBridge` immediate DisplayType 8 idle
       compass & heading replay + non-destructive speed-limit trap-frame countdown
       bar splicing (`updateTBTTrapInfo` / `updateTBTTrapInfoNew`), full Android
       Auto route & EV charging-stop state decoding (`AaNavState`), Issue #24
       CarPlay nav-app switch recovery (`recoverFromDipoStop`), `Wifi.projectionAp`
       (`"wifi": {"projection": true}`), and popup Settings fix across
       `SYSTEM_POPUP`, `LAUNCHER`, and `SETTINGS_APP`.
     - PR #16 picture subset: `Keyframes` CarPlay video decoder freeze IDR recovery
       (`picture`, `picture_periodic`, `picture_period`, NO `Watchdog`).
     - PR #28: opt-in `wifi_direct` part (off by default).
     - PR #56: Projection Focus Arbitrator (`FocusGuard` / `FocusPolicy`), enabled
       by default in Gen5W Suite so Phone Projection wins startup/connect focus
       races against Bluetooth Audio while preserving FM/AM/SiriusXM Radio,
       passenger BT streaming after startup, and reverse/AVM cameras.
  2. aa-dpi (0.3.0):
     - nemesis_conflicts: ["aa.dpi"], defaults (dpi=200, tap_limit=false, video_window=6).
  3. light-vibes (0.7.0):
     - models="any" in NEMESIS.data + Linux Android SDK fallback.
"""

import argparse
import json
import shutil
import stat
import sys
from pathlib import Path


PATCH_DIR = Path(__file__).resolve().parent


def write_json(path: Path, obj: dict) -> None:
    path.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")


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


DIPO_PICTURE_BLOCK = """    static Handler pictureHandler;
    static Keyframes keyframes;
    /** forceKeyFrame()'s answers since the last summary, for the log */
    static final java.util.TreeMap<String, Integer> results = new java.util.TreeMap<String, Integer>();
    /** a summary in HMG-API's log this often during a session */
    static final long SUMMARY_MS = 60000;

    /** setting picture_period ("5", "10", ...: seconds) */
    static long periodMs(String v) {
        try {
            return Math.max(2, Integer.parseInt(v.trim())) * 1000L;
        } catch (NumberFormatException e) {
            return 5000;
        }
    }
    /** DiPO's CarPlayService, from the last callback */
    static volatile Object cpService;

    /**
     * Picture ("picture"): asks the phone for a new key frame when the CarPlay picture
     * stalls (Keyframes says when). forceKeyFrame() runs on the part's own thread, never
     * on the callback's.
     */
    private static void picture(boolean periodic, long period) {
        final ClassLoader cl = app.classLoader;
        // every point it hooks, before it starts anything: no half-hooked picture thread
        for (String id : new String[] { "dipo.carplay.force_key_frame", "dipo.carplay.decode_failure", "dipo.carplay.screen_timeout",
                "dipo.carplay.session_started", "dipo.carplay.session_finalized" }) {
            if (!Points.has(id)) {
                log("CarPlay picture: off on this car (" + id + " not mapped)");
                return;
            }
        }
        Points.method(cl, "dipo.carplay.force_key_frame");   // there before anything is hooked (throws if not)
        HandlerThread t = new HandlerThread("hmgapi-cp-picture");
        t.start();
        pictureHandler = new Handler(t.getLooper());
        keyframes = new Keyframes(periodic, period);
        try {
            mod.hook(cl, "dipo.carplay.session_started", new MethodHook() {
                @Override
                protected void afterHookedMethod(final Param p) {
                    final Object cp = p.thisObject;
                    final int transport = (Integer) p.args[0];
                    pictureHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            cpService = cp;
                            keyframes.sessionStarted(transport);
                            keyframes.summary();
                            results.clear();
                            firstPeriodic = true;
                            pictureHandler.removeCallbacks(TICK);
                            pictureHandler.removeCallbacks(SUMMARY);
                            log("CarPlay picture: session started, " + (transport == 1 ? "wireless" : "wired (transport " + transport + ")")
                                    + (keyframes.ticking() ? ", a key frame every " + keyframes.period / 1000 + " s" : ", key frames on a stall only"));
                            if (keyframes.ticking()) pictureHandler.postDelayed(TICK, keyframes.period);
                            pictureHandler.postDelayed(SUMMARY, SUMMARY_MS);
                        }
                    });
                }
            });
            mod.hook(cl, "dipo.carplay.session_finalized", new MethodHook() {
                @Override
                protected void afterHookedMethod(Param p) {
                    pictureHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            keyframes.sessionEnded();
                            pictureHandler.removeCallbacks(TICK);
                            pictureHandler.removeCallbacks(SUMMARY);
                            logSummary("session ended");
                        }
                    });
                }
            });
            mod.hook(cl, "dipo.carplay.decode_failure", new MethodHook() {
                @Override
                protected void afterHookedMethod(final Param p) {
                    final Object cp = p.thisObject;
                    final int type = (Integer) p.args[0];
                    pictureHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            cpService = cp;
                            int before = keyframes.failures;
                            if (keyframes.decodeFailure(type, now())) forceKeyFrame("decoder failure type " + type + " (" + (before + 1) + " since the last key frame)");
                        }
                    });
                }
            });
            mod.hook(cl, "dipo.carplay.screen_timeout", new MethodHook() {
                @Override
                protected void afterHookedMethod(final Param p) {
                    final Object cp = p.thisObject;
                    pictureHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            cpService = cp;
                            if (keyframes.screenTimeout(now())) forceKeyFrame("screen timeout");
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            t.quit();   // the hooks already set post to a stopped thread, which drops their work
            throw e;
        } catch (Error e) {
            t.quit();
            throw e;
        }
        log("CarPlay picture: key frame on a stall" + (periodic ? ", and every " + keyframes.period / 1000 + " s on wireless" : ""));
    }

    static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!keyframes.ticking()) return;
            long now = now();
            if (keyframes.tick(now)) forceKeyFrame(null);
            pictureHandler.postDelayed(this, keyframes.nextTick(now));
        }
    };

    /** once a minute during a session: what the picture part saw and did */
    static final Runnable SUMMARY = new Runnable() {
        @Override
        public void run() {
            logSummary("last minute");
            pictureHandler.postDelayed(this, SUMMARY_MS);
        }
    };

    /** the summary line, even when nothing happened (a quiet minute is an answer too) */
    static void logSummary(String what) {
        log("CarPlay picture: " + what + ": " + keyframes.summary() + "; answers " + (results.isEmpty() ? "none" : results.toString()));
        results.clear();
    }

    static boolean firstPeriodic;

    static long now() {
        return SystemClock.uptimeMillis();
    }

    /** on the picture thread; why null for the periodic one (logged the first time, then counted in the summaries) */
    static void forceKeyFrame(String why) {
        Object cp = cpService;
        if (cp == null) return;
        int failures = keyframes.failures;
        keyframes.failures = 0;
        try {
            Object r = Points.call(app.classLoader, "dipo.carplay.force_key_frame", cp);
            String k = String.valueOf(r);
            Integer n = results.get(k);
            results.put(k, n == null ? 1 : n + 1);
            if (why != null) log("CarPlay picture: key frame asked for after " + why + ": " + r);
            else if (failures > 0) log("CarPlay picture: periodic key frame, " + failures + " decoder failure(s) since the last: " + r);
            else if (firstPeriodic) log("CarPlay picture: first periodic key frame of the session: " + r);
            if (why == null) firstPeriodic = false;
        } catch (Throwable t) {
            error("CarPlay picture: forceKeyFrame failed", t);
        }
    }

    private static void bridge() {"""


def patch_wireless(repo: Path) -> None:
    print("== Preparing wireless-carplay-aa 2.2.3 (on lemoyneiv/hmgapi-wireless-cp-aa @ 262554c)")

    # Verify Watchdog was never introduced anywhere
    assert not (repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/dipo/Watchdog.java").exists()
    assert not (repo / "wireless-carplay-aa/tools/watchdog").exists()
    assert (repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/NaviBridge.java").exists(), "Expected NaviBridge.java from 262554c"
    assert (repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/AaNavState.java").exists(), "Expected AaNavState.java from 262554c"

    dipo_java = repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/dipo/Dipo.java"
    bridge_java = repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/dipo/Bridge.java"
    dsrc = dipo_java.read_text(encoding="utf-8")
    assert "dipo.carplay.disable_bluetooth" in dsrc or "dipo.carplay.disable_bluetooth" in bridge_java.read_text(encoding="utf-8")
    assert "bridge_watchdog" not in dsrc.lower()

    assert (repo / "wireless-carplay-aa/tools/leafiaaac/test.sh").exists(), "Expected leafiaaac test"
    test_sh_text = (repo / "wireless-carplay-aa/test.sh").read_text(encoding="utf-8")
    assert '"hmgapi@>=1.0,!wireless.projection,!projection.tbt,!enhanced.projection"' in test_sh_text

    # Update mod.json (preserve 262554c system matrix, wifi.projection, trap-frame hooks, CarPlay identity, and conflicts)
    mod_path = repo / "wireless-carplay-aa/mod.json"
    mod = json.loads(mod_path.read_text(encoding="utf-8"))
    mod["version"] = "2.2.3"
    mod["hmgapi"] = ">=1.1.1"
    assert mod.get("wifi") == {"projection": True}, f"Expected wifi.projection in 262554c mod.json, got {mod.get('wifi')}"
    assert "libiap2session.so" in mod["hooks"]["native"], "Expected CarPlay identity recipe in mod.json"
    assert "enhanced.projection" in mod.get("nemesis_conflicts", []), "Expected enhanced.projection in mod.json"
    add_coauthor(mod)

    if not any(p.get("id") == "wifi_direct" for p in mod["parts"]):
        mod["parts"].append({"id": "wifi_direct", "when": "settings.wifi_direct"})

    pts = mod["hooks"]["points"]
    assert pts.get("framework.navigation_cluster.update_tbt_trap_info") == "change"
    assert pts.get("framework.navigation_cluster.update_tbt_trap_info_new") == "change"
    pts["dipo.carplay.decode_failure"] = "observe"
    pts["dipo.carplay.screen_timeout"] = "observe"
    pts["gpm.sensor_source.init"] = "observe"
    pts["gpm.receiver_service.disconnected"] = "observe"
    write_json(mod_path, mod)

    # Update settings.json (keep 262554c tbt_standard=True, add PR #56 projection_focus=True, PR #16 picture, PR #28 wifi_direct)
    settings_path = repo / "wireless-carplay-aa/settings.json"
    sdata = json.loads(settings_path.read_text(encoding="utf-8"))
    new_settings = []
    for item in sdata["settings"]:
        if item["key"] == "bridge_watchdog":
            continue
        new_settings.append(item)
        if item["key"] == "audio":
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

    # Update patches.json (preserve cars mapping, append opt-in PR #28 wifi_direct props)
    patches_path = repo / "wireless-carplay-aa/patches.json"
    pdata = json.loads(patches_path.read_text(encoding="utf-8"))
    assert pdata["cars"].get("NEEV23SE.USA.") == "neev", "Expected NEEV23SE.USA. in patches.json"
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

    # Install reviewed PR #56 FocusPolicy + FocusGuard and PR #16 Keyframes picture recovery
    focus_src_dir = PATCH_DIR / "wireless-focus"
    picture_src_dir = PATCH_DIR / "wireless-picture"
    mod_wireless_dir = repo / "wireless-carplay-aa/src/hmgapi/mods/wireless"
    shutil.copy2(focus_src_dir / "FocusPolicy.java", mod_wireless_dir / "FocusPolicy.java")
    shutil.copy2(focus_src_dir / "FocusGuard.java", mod_wireless_dir / "FocusGuard.java")
    shutil.copy2(picture_src_dir / "Keyframes.java", mod_wireless_dir / "dipo/Keyframes.java")

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
        if ((SYSTEM_POPUP.equals(app.packageName) || LAUNCHER.equals(app.packageName) || SETTINGS_APP.equals(app.packageName))
                && mod.settingBoolean("menu", true)) popupSettings(mod, app);
        if (DIPO.equals(app.packageName) && (mod.settingBoolean("tbt", true) || mod.settingBoolean("bridge", true) || mod.settingBoolean("audio", true))) dipo(mod, app);
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
        if ((SYSTEM_POPUP.equals(app.packageName) || LAUNCHER.equals(app.packageName) || SETTINGS_APP.equals(app.packageName))
                && mod.settingBoolean("menu", true)) popupSettings(mod, app);
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

    # Wire Dipo.java via FocusGuard.dipo(m, a) and Keyframes picture(...)
    replace_exact(
        dipo_java,
        "import hmgapi.hook.Points;\n",
        "import hmgapi.hook.Points;\nimport hmgapi.mods.wireless.FocusGuard;\n",
        "Dipo FocusGuard import",
    )
    replace_exact(
        dipo_java,
        "        if (!tbt && !bridge) return;\n        rgWatch();\n",
        "        if (FocusGuard.active(m)) FocusGuard.dipo(m, a);\n"
        "        if (m.settingBoolean(\"picture\", true)) {\n"
        "            try {\n"
        "                picture(m.settingBoolean(\"picture_periodic\", false), periodMs(m.settingString(\"picture_period\", \"5\")));\n"
        "            } catch (Throwable t) {\n"
        "                error(\"CarPlay picture: off\", t);\n"
        "            }\n"
        "        }\n"
        "        if (!tbt && !bridge) return;\n        rgWatch();\n",
        "Dipo.start FocusGuard.dipo + picture",
    )
    replace_exact(
        dipo_java,
        "    private static void bridge() {",
        DIPO_PICTURE_BLOCK,
        "Dipo.picture methods",
    )

    # Wire Bridge.java (notify FocusGuard during wireless CarPlay SPP handshake, skip post-disconnect onUsbGone,
    # and suppress Bridge SPP/SoftAP while Home Wi-Fi or parked keep-awake owns wlan0)
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
        if (addr == null) return;
        synchronized (Bridge.class) {
            if (sSession) return;
            sAddr = addr;
            if (sCpClient || sCpWireless || !enabled() || sUsb) return;
            sSession = true;
        }
        handler().post(new Runnable() {""",
        """    static void onSppUp(String addr) {
        onSppUp(addr, true);
    }

    static void onSppUp(final String addr, boolean notifyFocus) {
        if (addr == null) return;
        synchronized (Bridge.class) {
            if (sSession) return;
            sAddr = addr;
            if (sCpClient || sCpWireless || !enabled() || sUsb) return;
            sSession = true;
        }
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

    # Install host unit test for FocusPolicy (tools/focus/test.sh) and Keyframes (tools/picture/test.sh)
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

    picture_test_dir = repo / "wireless-carplay-aa/tools/picture/test/hmgapi/mods/wireless/dipo"
    picture_test_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(picture_src_dir / "KeyframesTest.java", picture_test_dir / "KeyframesTest.java")
    picture_test_sh = repo / "wireless-carplay-aa/tools/picture/test.sh"
    picture_test_sh.write_text(
        """#!/bin/sh
# Host test of the Wireless mod's CarPlay picture recovery policy (dipo/Keyframes):
# when a key frame is asked for, and how often at most.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" $(find test -name '*.java') ../../src/hmgapi/mods/wireless/dipo/Keyframes.java
java -cp "$CP" hmgapi.mods.wireless.dipo.KeyframesTest
""",
        encoding="utf-8",
    )
    picture_test_sh.chmod(picture_test_sh.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    # Wire FocusPolicy + FocusGuard stubs & assertions in tools/aa-support
    aa_support_dir = repo / "wireless-carplay-aa/tools/aa-support"
    aa_support_root = aa_support_dir / "stubs"
    if aa_support_root.is_dir():
        replace_exact(
            aa_support_dir / "test.sh",
            "    ../../src/hmgapi/mods/wireless/AaTbtSupport.java \\\n    ../../src/hmgapi/mods/wireless/NaviBridge.java \\",
            "    ../../src/hmgapi/mods/wireless/AaTbtSupport.java \\\n"
            "    ../../src/hmgapi/mods/wireless/FocusPolicy.java \\\n"
            "    ../../src/hmgapi/mods/wireless/NaviBridge.java \\",
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
            aa_support_dir / "test/hmgapi/mods/wireless/AaSupportTest.java",
            """            Car.step = 105;
            testNaviBridge(unknown);""",
            """            Car.step = 105;
            testNaviBridge(unknown);

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
