#!/usr/bin/env python3
"""
prepare-mods.py - Reproducibly applies Gen5W Suite customizations to clean
working copies of the four upstream HMG-API mods:

  1. wireless-carplay-aa (2.2.1):
     - PR #16 subset: Keyframes IDR screen-freeze recovery, CpTurns highway/
       rotary/stale-arrow fixes, post-route Compass replay (NO Watchdog).
     - PR #2: dipo.carplay.disable_bluetooth hook to keep BT alive on wireless CP.
     - PR #28: opt-in wifi_direct part (off by default).
     - leafiaaac.c hardening: atomic thread-safe codec loading + LPCM
       bitsPerChannel==0 / bytesPerFrame==0 normalization + host unit tests.
     - US Ioniq 5 support across NEEV, NEEV23, NEEV23SE on 260507 and 260903,
       plus enhanced.projection in nemesis_conflicts.
  2. adb (1.0.1):
     - Safe default usb=false so USB drives and wired CarPlay/Android Auto keep
       working immediately after install.
  3. aa-dpi (0.3.0):
     - nemesis_conflicts: ["aa.dpi"], defaults (dpi=200, tap_limit=false, video_window=6).
  4. egmp-aa-ev (0.7.0):
     - nemesis_conflicts: ["aa.ev.model"].
"""

import argparse
import json
import os
import stat
import subprocess
import sys
from pathlib import Path


PATCH_DIR = Path(__file__).resolve().parent


def write_json(path: Path, obj: dict) -> None:
    path.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")


def run(cmd, cwd=None) -> None:
    subprocess.run(cmd, cwd=cwd, check=True)


def patch_wireless(repo: Path) -> None:
    print("== Preparing wireless-carplay-aa 2.2.1")
    patch_file = PATCH_DIR / "wireless-pr16-no-watchdog.patch"
    run(["git", "apply", str(patch_file)], cwd=repo)

    # Verify Watchdog was not introduced anywhere
    assert not (repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/dipo/Watchdog.java").exists()
    assert not (repo / "wireless-carplay-aa/tools/watchdog").exists()

    # Apply PR #2 hook (dipo.carplay.disable_bluetooth) in Dipo.java
    dipo_java = repo / "wireless-carplay-aa/src/hmgapi/mods/wireless/dipo/Dipo.java"
    src = dipo_java.read_text(encoding="utf-8")
    anchor = (
        "        // DiPO's own wireless CarPlay (Hyundai's) stays off: "
        "the feature file is there for the other apps\n"
    )
    pr2_hook = """        // the CarPlay library's disableBluetooth: DiPO disconnects the phone's whole Bluetooth link, as for
        // wired CarPlay. On a wireless session some phones then end it after about a minute (#5), so the
        // link stays up while wireless CarPlay runs (persist.ev6.cpwl.keepbt=0: DiPO's way). A car whose
        // map has no such point yet (made from a Car Report older than HMG-API 1.1.0) goes without: the
        // hooks after this one must still go in.
        if (Points.has("dipo.carplay.disable_bluetooth")) {
            mod.hook(cl, "dipo.carplay.disable_bluetooth", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (!Bridge.sCpWireless || "0".equals(Bridge.prop("persist.ev6.cpwl.keepbt", "1"))) return;
                    log("DiPO: Bluetooth kept up for wireless CarPlay (disableBluetooth skipped)");
                    p.setResult(null);
                }
            });
        } else {
            log("DiPO: this car's map has no dipo.carplay.disable_bluetooth: Bluetooth isn't kept up for wireless CarPlay");
        }
"""
    assert anchor in src, "Expected Dipo.java anchor not found"
    assert "dipo.carplay.disable_bluetooth" not in src
    assert "watchdog" not in src.lower()
    dipo_java.write_text(src.replace(anchor, pr2_hook + anchor, 1), encoding="utf-8")

    # Update mod.json
    mod_path = repo / "wireless-carplay-aa/mod.json"
    mod = json.loads(mod_path.read_text(encoding="utf-8"))
    mod["version"] = "2.2.1"
    mod["hmgapi"] = ">=1.1.0"
    mod["nemesis_conflicts"] = [
        "wireless.projection",
        "projection.tbt",
        "enhanced.projection",
    ]
    mod["system"] = [
        {
            "model": ["CVEV", "NEEV", "NEEV23", "NEEV23SE"],
            "region": "USA",
            "firmware": "260507",
            "features": ["hmgapi.wifi"],
        },
        {
            "model": ["NEEV", "NEEV23", "NEEV23SE"],
            "region": "USA",
            "firmware": "260903",
            "features": ["hmgapi.wifi"],
        },
        {
            "model": ["CVEV", "NEEV"],
            "region": "CAN",
            "firmware": "260206",
            "features": ["hmgapi.wifi"],
        },
    ]
    if not any(p.get("id") == "wifi_direct" for p in mod["parts"]):
        mod["parts"].append({"id": "wifi_direct", "when": "settings.wifi_direct"})

    pts = mod["hooks"]["points"]
    pts["dipo.carplay.decode_failure"] = "observe"
    pts["dipo.carplay.screen_timeout"] = "observe"
    pts["dipo.carplay.disable_bluetooth"] = "replace"
    write_json(mod_path, mod)

    # Update settings.json (PR #16 picture + tbt_no_icon, PR #28 wifi_direct, NO bridge_watchdog)
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

    # Update patches.json (NEEV23SE.USA., CEEV.USA., + PR #28 wifi_direct props)
    patches_path = repo / "wireless-carplay-aa/patches.json"
    pdata = json.loads(patches_path.read_text(encoding="utf-8"))
    pdata["cars"]["NEEV23SE.USA."] = "neev"
    pdata["cars"]["CEEV.USA."] = "neev"
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

    # Append 260903 stock odex hashes to patches/neev/stock
    stock_path = repo / "wireless-carplay-aa/patches/neev/stock"
    stock_text = stock_path.read_text(encoding="utf-8")
    extra_stock = [
        "HKMC_DiPO.odex 92ff2fce2914aa3194c94aaf44f208be",
        "HKMC_GPM.odex 4978a2e09ce8473551236abd45a49112",
        "Iap2ServerApp.odex 46f392ece018bb218d180035b28e658b",
        "HKMC_BtSettings.odex 661dd038eec9b9f7568bcfa2183e5036",
        "HKMC_BtSettings.odex 2bf9327137ccabfbbd5a2664cbbfb7bd",
    ]
    for line in extra_stock:
        if line not in stock_text:
            stock_text += line + "\n"
    stock_path.write_text(stock_text, encoding="utf-8")

    # Harden leafiaaac.c: atomic load_fdk/load_opus + LPCM bitsPerChannel==0 normalization
    leafia_c = repo / "wireless-carplay-aa/native/leafiaaac/leafiaaac.c"
    csrc = leafia_c.read_text(encoding="utf-8")

    old_loaders = """static int load_fdk(void)
{
    static const char *libs[] = { "libstagefright_soft_aacdec.so", "libbt-codec_aac_dec.so" };
    unsigned i;
    if (F.tried) return F.ok;
    F.tried = 1;
    for (i = 0; i < 2 && !F.ok; i++) {
        void *h = dlopen(libs[i], 0);
        if (!h) continue;
        F.open = (fOpen)dlsym(h, "aacDecoder_Open");
        F.configRaw = (fConfigRaw)dlsym(h, "aacDecoder_ConfigRaw");
        F.fill = (fFill)dlsym(h, "aacDecoder_Fill");
        F.decode = (fDecode)dlsym(h, "aacDecoder_DecodeFrame");
        F.info = (fInfo)dlsym(h, "aacDecoder_GetStreamInfo");
        F.setParam = (fSetParam)dlsym(h, "aacDecoder_SetParam");
        F.close = (fClose)dlsym(h, "aacDecoder_Close");
        F.ok = F.open && F.configRaw && F.fill && F.decode && F.info && F.close;
        LOGI("FDK from %s: %s", libs[i], F.ok ? "ok" : "missing symbols");
    }
    return F.ok;
}

static int load_opus(void)
{
    void *h;
    if (O.tried) return O.ok;
    O.tried = 1;
    h = dlopen("libopus.so", 0);
    if (!h) { LOGW("libopus.so not found"); return 0; }
    O.decCreate = (oDecCreate)dlsym(h, "opus_decoder_create");
    O.decode = (oDecode)dlsym(h, "opus_decode");
    O.decDestroy = (oDestroy)dlsym(h, "opus_decoder_destroy");
    O.decCtl = (oCtl)dlsym(h, "opus_decoder_ctl");
    O.encCreate = (oEncCreate)dlsym(h, "opus_encoder_create");
    O.encode = (oEncode)dlsym(h, "opus_encode");
    O.encDestroy = (oDestroy)dlsym(h, "opus_encoder_destroy");
    O.encCtl = (oCtl)dlsym(h, "opus_encoder_ctl");
    O.ok = O.decCreate && O.decode && O.decDestroy && O.encCreate && O.encode && O.encDestroy;
    LOGI("libopus: %s", O.ok ? "ok" : "missing symbols");
    return O.ok;
}"""

    new_loaders = """/* 0: untried, 1: loading, 2: ready (atomic CAS so concurrent streams never read half-loaded pointers) */
static volatile int fdk_state;
static volatile int opus_state;

static int load_fdk(void)
{
    static const char *libs[] = { "libstagefright_soft_aacdec.so", "libbt-codec_aac_dec.so" };
    unsigned i;
    if (__sync_fetch_and_add(&fdk_state, 0) == 2) return F.ok;
    if (__sync_val_compare_and_swap(&fdk_state, 0, 1) == 0) {
        F.tried = 1;
        for (i = 0; i < 2 && !F.ok; i++) {
            void *h = dlopen(libs[i], 0);
            if (!h) continue;
            F.open = (fOpen)dlsym(h, "aacDecoder_Open");
            F.configRaw = (fConfigRaw)dlsym(h, "aacDecoder_ConfigRaw");
            F.fill = (fFill)dlsym(h, "aacDecoder_Fill");
            F.decode = (fDecode)dlsym(h, "aacDecoder_DecodeFrame");
            F.info = (fInfo)dlsym(h, "aacDecoder_GetStreamInfo");
            F.setParam = (fSetParam)dlsym(h, "aacDecoder_SetParam");
            F.close = (fClose)dlsym(h, "aacDecoder_Close");
            F.ok = F.open && F.configRaw && F.fill && F.decode && F.info && F.close;
            LOGI("FDK from %s: %s", libs[i], F.ok ? "ok" : "missing symbols");
        }
        __sync_synchronize();
        fdk_state = 2;
        return F.ok;
    }
    while (__sync_fetch_and_add(&fdk_state, 0) != 2) {
    }
    return F.ok;
}

static int load_opus(void)
{
    void *h;
    if (__sync_fetch_and_add(&opus_state, 0) == 2) return O.ok;
    if (__sync_val_compare_and_swap(&opus_state, 0, 1) == 0) {
        O.tried = 1;
        h = dlopen("libopus.so", 0);
        if (!h) {
            LOGW("libopus.so not found");
            __sync_synchronize();
            opus_state = 2;
            return 0;
        }
        O.decCreate = (oDecCreate)dlsym(h, "opus_decoder_create");
        O.decode = (oDecode)dlsym(h, "opus_decode");
        O.decDestroy = (oDestroy)dlsym(h, "opus_decoder_destroy");
        O.decCtl = (oCtl)dlsym(h, "opus_decoder_ctl");
        O.encCreate = (oEncCreate)dlsym(h, "opus_encoder_create");
        O.encode = (oEncode)dlsym(h, "opus_encode");
        O.encDestroy = (oDestroy)dlsym(h, "opus_encoder_destroy");
        O.encCtl = (oCtl)dlsym(h, "opus_encoder_ctl");
        O.ok = O.decCreate && O.decode && O.decDestroy && O.encCreate && O.encode && O.encDestroy;
        LOGI("libopus: %s", O.ok ? "ok" : "missing symbols");
        __sync_synchronize();
        opus_state = 2;
        return O.ok;
    }
    while (__sync_fetch_and_add(&opus_state, 0) != 2) {
    }
    return O.ok;
}"""

    old_conv = """static Conv *conv_alloc(const ASBD *in, const ASBD *out, int kind)
{
    Conv *c = (Conv *)calloc(1, sizeof(Conv));
    if (!c) return 0;
    c->pcm = (s16 *)malloc(MAX_FRAMES * MAX_CH * sizeof(s16));
    if (!c->pcm) { free(c); return 0; }
    c->magic = CONV_MAGIC;
    c->kind = kind;
    c->in = *in;
    c->out = *out;
    return c;
}"""

    new_conv = """static int lpcm_16_ok(const ASBD *a)
{
    u32 bpf = a->channelsPerFrame * 2u;
    if (a->formatID != FMT_LPCM) return 0;
    if (a->bitsPerChannel != 16u && a->bitsPerChannel != 0u) return 0;
    if (a->bytesPerFrame != 0u && a->bytesPerFrame != bpf) return 0;
    return 1;
}

static Conv *conv_alloc(const ASBD *in, const ASBD *out, int kind)
{
    Conv *c = (Conv *)calloc(1, sizeof(Conv));
    if (!c) return 0;
    c->pcm = (s16 *)malloc(MAX_FRAMES * MAX_CH * sizeof(s16));
    if (!c->pcm) { free(c); return 0; }
    c->magic = CONV_MAGIC;
    c->kind = kind;
    c->in = *in;
    c->out = *out;
    if (c->in.formatID == FMT_LPCM) {
        c->in.bitsPerChannel = 16;
        c->in.bytesPerFrame = c->in.channelsPerFrame * 2;
    }
    if (c->out.formatID == FMT_LPCM) {
        c->out.bitsPerChannel = 16;
        c->out.bytesPerFrame = c->out.channelsPerFrame * 2;
    }
    return c;
}"""

    old_new = """    if (in->formatID == FMT_AAC && out->formatID == FMT_LPCM && out->bitsPerChannel == 16)
        return new_aac_dec(in, out, outConv);
    if (in->formatID == FMT_OPUS && out->formatID == FMT_LPCM && out->bitsPerChannel == 16)
        return new_opus_dec(in, out, outConv);
    if (in->formatID == FMT_LPCM && out->formatID == FMT_OPUS && in->bitsPerChannel == 16)
        return new_opus_enc(in, out, outConv);"""

    new_new = """    if (in->formatID == FMT_AAC && lpcm_16_ok(out))
        return new_aac_dec(in, out, outConv);
    if (in->formatID == FMT_OPUS && lpcm_16_ok(out))
        return new_opus_dec(in, out, outConv);
    if (lpcm_16_ok(in) && out->formatID == FMT_OPUS)
        return new_opus_enc(in, out, outConv);"""

    assert old_loaders in csrc, "Expected load_fdk/load_opus block not found"
    assert old_conv in csrc, "Expected conv_alloc block not found"
    assert old_new in csrc, "Expected LeafiaAudioConverterNew block not found"
    csrc = csrc.replace(old_loaders, new_loaders, 1).replace(old_conv, new_conv, 1).replace(old_new, new_new, 1)
    leafia_c.write_text(csrc, encoding="utf-8")

    # Recompile files/libleafiaaac.so and verify only stock Android symbols are imported
    so_out = repo / "wireless-carplay-aa/files/libleafiaaac.so"
    run(["sh", str(repo / "wireless-carplay-aa/native/leafiaaac/build.sh"), str(so_out)])
    nm_out = subprocess.check_output(["llvm-nm", "-u", str(so_out)], text=True)
    undef = {line.strip().split()[-1] for line in nm_out.splitlines() if line.strip()}
    allowed_undef = {
        "calloc",
        "free",
        "malloc",
        "memcpy",
        "memset",
        "dlopen",
        "dlsym",
        "__android_log_print",
    }
    extra_undef = undef - allowed_undef
    assert not extra_undef, f"Unexpected undefined symbols in libleafiaaac.so: {extra_undef}"

    # Add host unit test for leafiaaac.c (LPCM bitsPerChannel==0 + multi-threaded init)
    leafia_test_dir = repo / "wireless-carplay-aa/tools/leafiaaac"
    leafia_test_dir.mkdir(parents=True, exist_ok=True)
    (leafia_test_dir / "leafiaaac_test.c").write_text(
        """#define HOST 1
#include <assert.h>
#include <pthread.h>
#include <unistd.h>
#include "../../native/leafiaaac/leafiaaac.c"

static CStreamInfoHead fake_info = { 44100, 1024, 2 };
static fdk_handle fake_fdk_open(s32 fmt, u32 layers) { (void)fmt; (void)layers; return (void *)0x1234; }
static s32 fake_fdk_cfg(fdk_handle h, u8 *c[], const u32 l[]) { (void)h; (void)c; return l[0] == 2 ? 0 : -1; }
static s32 fake_fdk_fill(fdk_handle h, u8 *b[], const u32 s[], u32 *v) { (void)h; (void)b; *v = 0; return s[0] ? 0 : -1; }
static s32 fake_fdk_dec(fdk_handle h, s16 *pcm, s32 n, u32 f) {
    (void)h; (void)f;
    if (n < 2048) return -1;
    for (int i = 0; i < 2048; i++) pcm[i] = (s16)(i & 0x7fff);
    return 0;
}
static CStreamInfoHead *fake_fdk_info(fdk_handle h) { (void)h; return &fake_info; }
static s32 fake_fdk_param(fdk_handle h, s32 p, s32 v) { (void)h; (void)p; (void)v; return 0; }
static void fake_fdk_close(fdk_handle h) { (void)h; }

static void *fake_opus_dec_create(s32 fs, s32 ch, s32 *err) { (void)fs; (void)ch; *err = 0; return (void *)0x2345; }
static s32 fake_opus_decode(void *st, const u8 *d, s32 len, s16 *pcm, s32 fs, s32 fec) {
    (void)st; (void)d; (void)len; (void)fs; (void)fec;
    for (int i = 0; i < 320; i++) pcm[i] = 42;
    return 320;
}
static void fake_opus_destroy(void *st) { (void)st; }
static void *fake_opus_enc_create(s32 fs, s32 ch, s32 app, s32 *err) { (void)fs; (void)ch; (void)app; *err = 0; return (void *)0x3456; }
static s32 fake_opus_encode(void *st, const s16 *pcm, s32 fs, u8 *data, s32 maxb) {
    (void)st; (void)pcm; (void)fs;
    if (maxb < 8) return -1;
    memset(data, 0x5a, 8);
    return 8;
}
static s32 fake_opus_ctl(void *st, s32 req, ...) { (void)st; (void)req; return 0; }

void *dlopen(const char *name, int flags) {
    (void)flags;
    usleep(5000); /* intentionally widen race window for concurrent load_fdk/load_opus test */
    if (strcmp(name, "libstagefright_soft_aacdec.so") == 0) return (void *)1;
    if (strcmp(name, "libopus.so") == 0) return (void *)2;
    return 0;
}

void *dlsym(void *h, const char *sym) {
    usleep(1000);
    if (h == (void *)1) {
        if (strcmp(sym, "aacDecoder_Open") == 0) return (void *)fake_fdk_open;
        if (strcmp(sym, "aacDecoder_ConfigRaw") == 0) return (void *)fake_fdk_cfg;
        if (strcmp(sym, "aacDecoder_Fill") == 0) return (void *)fake_fdk_fill;
        if (strcmp(sym, "aacDecoder_DecodeFrame") == 0) return (void *)fake_fdk_dec;
        if (strcmp(sym, "aacDecoder_GetStreamInfo") == 0) return (void *)fake_fdk_info;
        if (strcmp(sym, "aacDecoder_SetParam") == 0) return (void *)fake_fdk_param;
        if (strcmp(sym, "aacDecoder_Close") == 0) return (void *)fake_fdk_close;
    }
    if (h == (void *)2) {
        if (strcmp(sym, "opus_decoder_create") == 0) return (void *)fake_opus_dec_create;
        if (strcmp(sym, "opus_decode") == 0) return (void *)fake_opus_decode;
        if (strcmp(sym, "opus_decoder_destroy") == 0) return (void *)fake_opus_destroy;
        if (strcmp(sym, "opus_decoder_ctl") == 0) return (void *)fake_opus_ctl;
        if (strcmp(sym, "opus_encoder_create") == 0) return (void *)fake_opus_enc_create;
        if (strcmp(sym, "opus_encode") == 0) return (void *)fake_opus_encode;
        if (strcmp(sym, "opus_encoder_destroy") == 0) return (void *)fake_opus_destroy;
        if (strcmp(sym, "opus_encoder_ctl") == 0) return (void *)fake_opus_ctl;
    }
    return 0;
}

static void *worker(void *arg) {
    int idx = *(int *)arg;
    void *conv = 0;
    if (idx & 1) {
        ASBD in = { 44100, FMT_AAC, 0, 0, 1024, 0, 2, 0, 0 };
        ASBD out = { 44100, FMT_LPCM, 0, 0, 1, 0, 2, 0, 0 }; /* bitsPerChannel == 0 */
        assert(LeafiaAudioConverterNew(&in, &out, &conv) == 0);
        assert(conv != 0);
        Conv *c = (Conv *)conv;
        assert(c->out.bitsPerChannel == 16);
        assert(c->out.bytesPerFrame == 4);
        assert(LeafiaAudioConverterDispose(conv) == 0);
    } else {
        ASBD in = { 16000, FMT_LPCM, 0, 0, 1, 0, 1, 0, 0 }; /* bitsPerChannel == 0 */
        ASBD out = { 16000, FMT_OPUS, 0, 0, 320, 0, 1, 0, 0 };
        assert(LeafiaAudioConverterNew(&in, &out, &conv) == 0);
        assert(conv != 0);
        Conv *c = (Conv *)conv;
        assert(c->in.bitsPerChannel == 16);
        assert(c->in.bytesPerFrame == 2);
        assert(LeafiaAudioConverterDispose(conv) == 0);
    }
    return 0;
}

int main(void) {
    pthread_t th[8];
    int ids[8];
    for (int i = 0; i < 8; i++) {
        ids[i] = i;
        assert(pthread_create(&th[i], 0, worker, &ids[i]) == 0);
    }
    for (int i = 0; i < 8; i++) {
        assert(pthread_join(th[i], 0) == 0);
    }

    /* Standard 16-bit ASBD still works */
    void *conv = 0;
    ASBD in16 = { 16000, FMT_OPUS, 0, 0, 320, 0, 1, 0, 0 };
    ASBD out16 = { 16000, FMT_LPCM, 0, 2, 1, 2, 1, 16, 0 };
    assert(LeafiaAudioConverterNew(&in16, &out16, &conv) == 0);
    assert(LeafiaAudioConverterDispose(conv) == 0);

    /* Non-16-bit LPCM (e.g., 32-bit or 4 bytesPerFrame mono) is still rejected */
    ASBD out32 = { 16000, FMT_LPCM, 0, 4, 1, 4, 1, 32, 0 };
    assert(LeafiaAudioConverterNew(&in16, &out32, &conv) == kUnsupportedErr);
    ASBD outBadBpf = { 16000, FMT_LPCM, 0, 4, 1, 4, 1, 0, 0 };
    assert(LeafiaAudioConverterNew(&in16, &outBadBpf, &conv) == kUnsupportedErr);

    puts("ok leafiaaac_test (concurrent load + LPCM bitsPerChannel==0)");
    return 0;
}
""",
        encoding="utf-8",
    )
    leafia_test_sh = leafia_test_dir / "test.sh"
    leafia_test_sh.write_text(
        """#!/bin/sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
${CC:-clang} -O2 -Wall -Wextra -Werror "$HERE/leafiaaac_test.c" -lpthread -o "$T/leafiaaac_test"
"$T/leafiaaac_test"
""",
        encoding="utf-8",
    )
    leafia_test_sh.chmod(leafia_test_sh.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    # Update wireless-carplay-aa/test.sh expected NEMESIS depends string
    test_sh = repo / "wireless-carplay-aa/test.sh"
    ttext = test_sh.read_text(encoding="utf-8")
    old_dep = '"hmgapi@>=1.0,!wireless.projection,!projection.tbt"'
    new_dep = '"hmgapi@>=1.0,!wireless.projection,!projection.tbt,!enhanced.projection"'
    assert old_dep in ttext, "Expected depends assertion not found in wireless-carplay-aa/test.sh"
    test_sh.write_text(ttext.replace(old_dep, new_dep, 1), encoding="utf-8")


def patch_adb(repo: Path) -> None:
    print("== Preparing adb 1.0.1 (usb default false)")
    mod_path = repo / "adb/mod.json"
    mod = json.loads(mod_path.read_text(encoding="utf-8"))
    mod["version"] = "1.0.1"
    write_json(mod_path, mod)

    settings_path = repo / "adb/settings.json"
    sdata = json.loads(settings_path.read_text(encoding="utf-8"))
    found_usb = False
    for item in sdata["settings"]:
        if item["key"] == "usb":
            item["default"] = False
            found_usb = True
    assert found_usb
    write_json(settings_path, sdata)

    # Update adb/test.sh to test safe defaults (usb=false, tcp=false -> host,adb)
    test_sh = repo / "adb/test.sh"
    test_sh.write_text(
        """#!/bin/sh
# Off-car test of the adb mod on the patch engine: safe defaults keep USB in host mode
# (host,adb) and TCP off; each toggle sets its props, live mirror and adbd restart;
# restore gives the stock values back.
set -e
M=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R=$T/root
. "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked mod SDK}/test/lib.sh"
prop() { grep "^prop/$1=" $R/settings.txt | cut -d= -f2-; }
check "NEMESIS package refuses adb.tcp (mod.json nemesis_conflicts)" "hmgapi@>=1.0,!adb.tcp" \\
    "$(HMGAPI_NEMESIS_CONFLICTS=/dev/null $HMGAPI_SDK/bin/nemesis-meta.py depends hmgapi.adb 'hmgapi@>=1.0' --mod $M/mod.json)"
mkdir -p $R/data/hmgapi/mods $R/data/hmgapi/settings
printf 'ro.board.platform=tcc803x\\n' > $R/props.txt
printf 'prop/persist.service.adb.tcp.enable=1\\nprop/persist.sys.usb.config=adb\\nprop/persist.adb.tcp.port=5555\\n' > $R/settings.txt
install_mod "$M"

eng apply; check "defaults apply" 0 $rc
check "Engineering Mode switch off (no eth0 address)" 0 "$(prop persist.service.adb.tcp.enable)"
check "USB port kept in host mode by default (flash drives + wired CP/AA work)" "host,adb" "$(prop persist.sys.usb.config)"
check "adb over Wi-Fi off by default: port unset" "" "$(prop persist.adb.tcp.port)"
has "switched live to host,adb" "live/sys.usb.config=host,adb" "$(cat $R/actions.log 2>/dev/null)"
has "adbd restarted" "restart adbd" "$(cat $R/actions.log 2>/dev/null)"

printf '{"usb": true}\\n' > $R/data/hmgapi/settings/adb.json
: > $R/actions.log
eng apply; check "USB on applies" 0 $rc
check "USB adb on" "adb" "$(prop persist.sys.usb.config)"
has "switched live to adb" "live/sys.usb.config=adb" "$(cat $R/actions.log)"

printf '{"usb": false, "tcp": true}\\n' > $R/data/hmgapi/settings/adb.json
: > $R/actions.log
eng apply; check "TCP on: port 5555 (eth0 left alone)" 5555 "$(prop persist.adb.tcp.port)"
check "port back to host mode (stock)" "host,adb" "$(prop persist.sys.usb.config)"
has "adbd restarted" "restart adbd" "$(cat $R/actions.log)"
check "... the car's switch stays off" 0 "$(prop persist.service.adb.tcp.enable)"

printf '{"usb": true, "tcp": true}\\n' > $R/data/hmgapi/settings/adb.json
: > $R/actions.log
eng apply; check "both on again" "adb 5555" "$(prop persist.sys.usb.config) $(prop persist.adb.tcp.port)"
check "USB on again: live switch only (TCP was already on)" "live/sys.usb.config=adb|" "$(grep -v STATE_CHANGED $R/actions.log | tr '\\n' '|')"

: > $R/actions.log
eng restore; check "restore" 0 $rc
check "stock values back" "1 adb 5555" "$(prop persist.service.adb.tcp.enable) $(prop persist.sys.usb.config) $(prop persist.adb.tcp.port)"
exit $fail
""",
        encoding="utf-8",
    )
    test_sh.chmod(test_sh.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def _adapt_engine_test_for_standalone_wireless(test_path: Path) -> None:
    s = test_path.read_text(encoding="utf-8")
    s = s.replace(
        'cp -r "$HMGAPI/mods/wireless-carplay-aa" "$R/data/hmgapi/mods/wireless-carplay-aa"',
        'cp -r "${WIRELESS_MOD:-$HMGAPI/mods/wireless-carplay-aa}" "$R/data/hmgapi/mods/wireless-carplay-aa"\n'
        '    mkdir -p "$R/system/onebin/USA"\n'
        '    echo "-/system/etc/permissions/com.hkmc.software.wifihotspot.xml" > "$R/system/onebin/USA/featurexml.cfg"',
    )
    s = s.replace(
        'wireless="$HMGAPI/mods/wireless-carplay-aa/recipes/aa-tbt-$suffix.recipe"',
        'wireless="${WIRELESS_MOD:-$HMGAPI/mods/wireless-carplay-aa}/recipes/aa-tbt-$suffix.recipe"',
    )
    s = s.replace(
        'HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=$P',
        'HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=$P HMGAPI_ONEBIN=USA',
    )
    test_path.write_text(s, encoding="utf-8")


def patch_aa_dpi(repo: Path) -> None:
    print("== Preparing aa-dpi 0.3.0 (dpi=200, tap_limit=false, video_window=6)")
    mod_path = repo / "hmgapi-mod/mod.json"
    mod = json.loads(mod_path.read_text(encoding="utf-8"))
    assert mod["version"] == "0.3.0"
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

    dpi_hooks = repo / "hmgapi-mod/hooks/dev/aadpi/hook/DpiHooks.java"
    hsrc = dpi_hooks.read_text(encoding="utf-8")
    old_tap = 'boolean limit = mod.settingBoolean("tap_limit", true);'
    new_tap = 'boolean limit = mod.settingBoolean("tap_limit", false);'
    assert old_tap in hsrc, "Expected tap_limit fallback in DpiHooks.java"
    dpi_hooks.write_text(hsrc.replace(old_tap, new_tap, 1), encoding="utf-8")

    _adapt_engine_test_for_standalone_wireless(repo / "hmgapi-mod/test/engine-test.sh")


def patch_egmp_aa_ev(repo: Path) -> None:
    print("== Preparing egmp-aa-ev 0.7.0")
    mod_path = repo / "hmgapi-mod/mod.json"
    mod = json.loads(mod_path.read_text(encoding="utf-8"))
    assert mod["version"] == "0.7.0"
    mod["nemesis_conflicts"] = ["aa.ev.model"]
    write_json(mod_path, mod)
    _adapt_engine_test_for_standalone_wireless(repo / "hmgapi-mod/test/engine-test.sh")


def patch_light_vibes(repo: Path) -> None:
    if not (repo / "build.sh").exists():
        return
    print("== Preparing light-vibes 0.6.7")
    bpath = repo / "build.sh"
    bsrc = bpath.read_text(encoding="utf-8")
    old_jar = "ANDROID_JAR=$SDK/platforms/android-19/android.jar"
    new_jar = (
        'if [ -f "$SDK/platforms/android-19/android.jar" ]; then ANDROID_JAR=$SDK/platforms/android-19/android.jar\n'
        'else ANDROID_JAR=$(ls "$SDK"/platforms/android-*/android.jar | sort -V | tail -1); fi'
    )
    assert old_jar in bsrc, "Expected ANDROID_JAR line in light-vibes/build.sh"
    bpath.write_text(bsrc.replace(old_jar, new_jar, 1), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--work-dir", required=True, help="Directory containing working copies of upstream mod repos")
    args = ap.parse_args()
    w = Path(args.work_dir).resolve()

    patch_wireless(w / "hmgapi-wireless-cp-aa")
    patch_adb(w / "hmgapi-adb")
    patch_aa_dpi(w / "hmg-aa-dpi")
    patch_egmp_aa_ev(w / "egmp-aa-ev-hmg-api")
    patch_light_vibes(w / "light-vibes")
    print("All upstream mods prepared cleanly.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
