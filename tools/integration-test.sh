#!/bin/sh
# End-to-end multi-mod integration test for the Gen5W Suite.
#
# Installs ALL signed suite mod .hmgapi.zip packages simultaneously via
# hmgapi.engine.Engine onto simulated US Hyundai Ioniq 5 head units across
# BOTH firmware 260507 and 260903 (NEEV, NEEV23, NEEV23SE), runs `engine plan`,
# `engine apply`, verifies zero conflicts across hooks, native recipes, props,
# services, and companion APKs, verifies trusted author key recognition, and
# confirms `engine restore` returns /system byte-for-byte back to stock.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
HMGAPI=${HMGAPI:?set HMGAPI to HMG-API checkout}
HMGAPI_SDK=${HMGAPI_SDK:?set HMGAPI_SDK to unpacked HMG-API mod SDK}
UPDATES_DIR=${1:?usage: integration-test.sh <updates-dir>}

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail=0

check() {
    if [ "$2" = "$3" ]; then
        echo "ok    $1"
    else
        echo "FAIL  $1: expected [$2], got [$3]"
        fail=1
    fi
}

has() {
    case "$3" in
        *"$2"*) echo "ok    $1" ;;
        *)
            echo "FAIL  $1: wanted [$2] in:"
            echo "$3" | sed 's/^/        /'
            fail=1 ;;
    esac
}

hasnt() {
    case "$3" in
        *"$2"*)
            echo "FAIL  $1: unexpected [$2] in:"
            echo "$3" | sed 's/^/        /'
            fail=1 ;;
        *) echo "ok    $1" ;;
    esac
}

for P in \
    NEEV.USA.0000.V015.012.260507 \
    NEEV23.USA.0000.V015.012.260903 \
    NEEV23SE.USA.0000.V015.012.260903
do
    echo "== Multi-mod integration test on $P =="
    R="$T/$P"
    mkdir -p "$R/system/app" "$R/system/lib" "$R/system/etc/permissions" \
             "$R/system/etc/wifi" "$R/system/onebin/USA" \
             "$R/sys/bus/sdio/devices/mmc1:0001:1" \
             "$R/data/hmgapi/mods" "$R/data/hmgapi/settings/allow"

    printf 'ro.build.product=%s\nro.board.platform=tcc803x\npersist.ium.support=none\n' "$P" > "$R/system/build.prop"
    printf 'ro.build.product=%s\nro.board.platform=tcc803x\nro.build.version.release=4.4.2\nro.mobis.onebindir=USA\n' "$P" > "$R/props.txt"
    printf 'ro.build.product=%s ro.board.platform=tcc803x ro.build.version.release=4.4.2 ro.mobis.onebindir=USA' "$P" > "$R/props.env"
    printf '%s\n%s\n' \
        "-/system/etc/permissions/com.hkmc.software.wifihotspot.xml" \
        "-/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml" \
        > "$R/system/onebin/USA/featurexml.cfg"
    printf 'p2p_disabled=1\n' > "$R/system/etc/wifi/wpa_supplicant.conf"
    printf 'p2p_no_go_freq=5000-5850\n' > "$R/system/etc/wifi/wpa_supplicant_overlay.conf"
    printf 'prop/persist.service.adb.tcp.enable=1\nprop/persist.sys.usb.config=adb\nprop/persist.adb.tcp.port=5555\n' > "$R/settings.txt"

    cp -r "$HMGAPI/maps" "$R/data/hmgapi/maps"
    cp "$HMGAPI_SDK/points.txt" "$R/data/hmgapi/maps/points.txt"
    touch "$R/data/hmgapi/settings/allow/testing_maps"

    # Populate stand-in odex/jar files matching each car.json map
    python3 - "$R" <<'PY'
import glob, hashlib, json, os, sys
R = sys.argv[1]
for cj in glob.glob(R + "/data/hmgapi/maps/*/*/*/*/car.json"):
    car = json.load(open(cj))
    files = car.get("files")
    if isinstance(files, dict):
        for path in files:
            f = R + path
            os.makedirs(os.path.dirname(f), exist_ok=True)
            if not os.path.exists(f):
                open(f, "wb").write(("stand-in " + path).encode())
            files[path] = hashlib.md5(open(f, "rb").read()).hexdigest()
    json.dump(car, open(cj, "w"), indent=2)
PY

    BEFORE=$(cd "$R/system" && find . -type f | sort | xargs sha256sum)
    PROPS=$(cat "$R/props.env")

    eng() {
        set +e
        out=$(HMGAPI_ROOT="$R" HMGAPI_HOST=1 HMGAPI_PRODUCT="$P" HMGAPI_ONEBIN=USA \
              HMGAPI_PROPS="$PROPS" HMGAPI_LPATCH="$HMGAPI_SDK/bin/lpatch" \
              java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" hmgapi.engine.Engine "$@" 2>&1)
        rc=$?
        set -e
    }

    # Install every signed mod zip in order, verifying trusted author list recognition as lemoyneiv
    for z in "$UPDATES_DIR"/*.hmgapi.zip; do
        eng install "$z" --from-nemesis
        check "$P: install ${z##*/}" 0 "$rc"
        has "$P: ${z##*/} recognized on trusted author list as lemoyneiv" "author   lemoyneiv (author key 28ad71e0-07c0-8e61-8bf3-380cc71f9280, on HMG-API's trusted author list)" "$out"
    done

    mod_authors() {
        python3 -c 'import json,sys; print(", ".join(json.load(open(sys.argv[1]))["authors"]))' "$R/data/hmgapi/mods/$1/mod.json"
    }
    check "$P: wireless-carplay-aa authors" "VysseCodes, lemoyneiv" "$(mod_authors wireless-carplay-aa)"
    check "$P: adb authors" "VysseCodes, lemoyneiv" "$(mod_authors adb)"
    check "$P: aa-dpi authors" "eliotwass, AwsomeFox, lemoyneiv" "$(mod_authors aa-dpi)"
    check "$P: egmp-aa-ev stock authors preserved" "willemcvu, AwsomeFox" "$(mod_authors egmp-aa-ev)"
    check "$P: hmgapi-comfort original author" "lemoyneiv" "$(mod_authors hmgapi-comfort)"
    check "$P: light-vibes authors" "Kyle (dibble9012), lemoyneiv" "$(mod_authors light-vibes)"
    check "$P: light-vibes version 0.6.8" "0.6.8" \
        "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$R/data/hmgapi/mods/light-vibes/mod.json")"

    # Plan across ALL mods simultaneously
    eng plan
    check "$P: multi-mod plan succeeds with 0 problems" 0 "$rc"
    hasnt "$P: no mod refuses or fails to load hooks" "won't load" "$out"
    hasnt "$P: no hook conflicts" "conflict" "$out"
    has "$P: gpm.sensor_source.init shared cleanly (aa-dpi + egmp-aa-ev + wireless)" "wireless-carplay-aa (observe)" "$out"
    has "$P: gpm.navigation_status.next_turn shared cleanly (wireless + egmp)" "gpm.navigation_status.next_turn: " "$out"

    # Apply across ALL mods in a single transaction
    eng apply
    check "$P: single atomic apply succeeds" 0 "$rc"
    has "$P: audio converter added" "/system/lib/libleafiaaac.so" "$out"
    check "$P: ADB defaults keep USB in host mode (host,adb)" "host,adb" \
        "$(grep '^prop/persist.sys.usb.config=' "$R/settings.txt" | cut -d= -f2-)"
    check "$P: Engineering Mode eth0 ADB switch turned off" "0" \
        "$(grep '^prop/persist.service.adb.tcp.enable=' "$R/settings.txt" | cut -d= -f2-)"
    has "$P: wireless CarPlay bridge prop set" "prop/persist.iap2.iden.wireless=t" "$(cat "$R/settings.txt")"
    has "$P: egmp-aa-ev relay service scheduled" "egmp-aa-ev relay" "$(cat "$R/data/hmgapi/state/services.txt")"
    has "$P: hmgapi-comfort fast-path welcome boot service scheduled" "hmgapi-comfort welcome system boot yes jar app/Comfort.apk dev.lemoyne.comfort.WelcomeDaemon" "$(cat "$R/data/hmgapi/state/services.txt")"
    has "$P: hmgapi-comfort starter service scheduled" "hmgapi-comfort starter" "$(cat "$R/data/hmgapi/state/services.txt")"
    has "$P: light-vibes lights service scheduled" "light-vibes lights" "$(cat "$R/data/hmgapi/state/services.txt")"
    has "$P: Comfort.apk companion app scheduled" "dev.lemoyne.comfort hmgapi-comfort" "$(cat "$R/data/hmgapi/state/apps.txt")"
    has "$P: LightVibes.apk companion app scheduled" "lightvibes.app light-vibes" "$(cat "$R/data/hmgapi/state/apps.txt")"

    AADPI_DEFAULTS=$(python3 - "$R/data/hmgapi/public/state.json" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))["mods"]["aa-dpi"]["settings"]
if isinstance(s, list):
    s = {x["key"]: x.get("value", x.get("default")) for x in s}
print(f"dpi={s.get('dpi')} tap_limit={s.get('tap_limit')} video_window={s.get('video_window')}")
PY
)
    check "$P: aa-dpi published defaults (dpi=200, tap_limit=False, video_window=6)" \
        "dpi=200 tap_limit=False video_window=6" "$AADPI_DEFAULTS"

    WL_FOCUS=$(python3 - "$R/data/hmgapi/public/state.json" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))["mods"]["wireless-carplay-aa"]["settings"]
if isinstance(s, list):
    s = {x["key"]: x.get("value", x.get("default")) for x in s}
print(f"projection_focus={s.get('projection_focus')}")
PY
)
    check "$P: wireless-carplay-aa published default projection_focus=True" \
        "projection_focus=True" "$WL_FOCUS"

    # Verify Home Screen Tile Manager CLI (launcher-tiles-set + launcher-tiles)
    eng launcher-tiles-set '{"compact_gaps":true,"hidden":["com.hkmc.valet/.ValetActivity"]}'
    check "$P: launcher-tiles-set succeeds" 0 "$rc"
    has "$P: launcher-tiles-set saves 1 hidden tile" "saved: 1 hidden" "$out"
    check "$P: public/launcher-tiles.json written" "yes" "$([ -f "$R/data/hmgapi/public/launcher-tiles.json" ] && echo yes || echo no)"

    # Restore everything back to stock and verify byte-identical /system
    eng restore
    check "$P: engine restore succeeds" 0 "$rc"
    AFTER=$(cd "$R/system" && find . -type f | sort | xargs sha256sum)
    check "$P: /system byte-identical to stock after restore" "$BEFORE" "$AFTER"
done

exit "$fail"
