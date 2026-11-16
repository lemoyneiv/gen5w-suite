#!/bin/sh
# Build, test, sign, integration-verify, and package the complete Gen5W Suite:
#
#   ./build-suite.sh                 build core + 6 mods + unified USB installers
#   ./build-suite.sh --with-extras   also build & include ../brake-indicator-gen5w
#
# Outputs in dist/:
#   dist/gen5w-suite-usb.zip                 One-tap NEMESIS execute USB stick (recommended)
#   dist/gen5w-suite-nemesis-store-usb.zip   Interactive NEMESIS USB Package Store stick (type=list)
#   dist/gen5w-suite-hmgapi-updates-usb.zip  HMG-API Updates stick (HMGAPI/updates/*.zip)
#   dist/mods/*.zip                          Individual signed HMG-API + NEMESIS packages
#   dist/SHA256SUMS                          SHA-256 checksums
set -e
cd "$(dirname "$0")"
SUITE_ROOT=$PWD

WITH_EXTRAS=0
for arg in "$@"; do
    case "$arg" in
        --with-extras) WITH_EXTRAS=1 ;;
        *) echo "usage: $0 [--with-extras]" >&2; exit 2 ;;
    esac
done

# 1. Locate Android SDK, HMG-API fork checkout, Mod SDK, and persistent signing keys
if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -d "$HOME/Android" ]; then ANDROID_HOME="$HOME/Android"
    elif [ -d /opt/homebrew/share/android-commandlinetools ]; then ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    elif [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME="$HOME/Library/Android/sdk"
    fi
fi
export ANDROID_HOME

HMGAPI=${HMGAPI:-$SUITE_ROOT/../HMG-API}
[ -f "$HMGAPI/build.sh" ] || { echo "build-suite.sh: HMG-API checkout not found at $HMGAPI (set HMGAPI)" >&2; exit 1; }
HMGAPI=$(cd "$HMGAPI" && pwd)
export HMGAPI

if [ -n "${HMGAPI_KEY:-}" ]; then
    KEY_DIR=$(dirname "$HMGAPI_KEY")
elif [ -f "$HOME/.hmgapi/author.key" ]; then
    KEY_DIR="$HOME/.hmgapi"
    HMGAPI_KEY="$KEY_DIR/author.key"
elif [ -f "$HOME/.gemini/hmgapi/author.key" ]; then
    KEY_DIR="$HOME/.gemini/hmgapi"
    HMGAPI_KEY="$KEY_DIR/author.key"
else
    KEY_DIR="$HOME/.hmgapi"
    HMGAPI_KEY="$KEY_DIR/author.key"
fi
export HMGAPI_KEY
[ -f "$HMGAPI_KEY" ] || { echo "build-suite.sh: missing author key at $HMGAPI_KEY" >&2; exit 1; }

# Ensure ~/.hmgapi resolves to KEY_DIR when absent so mods using ~/.hmgapi/* keystores share persistent keys
if [ ! -e "$HOME/.hmgapi" ] && [ "$KEY_DIR" != "$HOME/.hmgapi" ]; then
    ln -s "$KEY_DIR" "$HOME/.hmgapi"
fi

CORE_APP_KEY="$KEY_DIR/hmgapi-core-app.p12"
if [ ! -d "$HMGAPI/out/sdk" ] || [ ! -f "$HMGAPI/out/dist/hmgapi-1.1.1-rc.2-nemesis-package.zip" ]; then
    echo "== Building HMG-API Core 1.1.1-rc.2 & Mod SDK =="
    (cd "$HMGAPI" && HMGAPI_KEY="$CORE_APP_KEY" ./build.sh && ./build.sh sdk && sh tools/mkinstallers.sh)
fi
HMGAPI_SDK=${HMGAPI_SDK:-$HMGAPI/out/sdk}
export HMGAPI_SDK
export HMGAPI_FLOOR_POINTS="$HMGAPI_SDK/points.txt"

# 2. Fetch (or reuse cached) upstream mod repositories at pinned commits
CACHE_DIR=${MOD_CACHE_DIR:-$SUITE_ROOT/.cache/upstream}
mkdir -p "$CACHE_DIR"

fetch_upstream() {
    name=$1
    url=$2
    sha=$3
    if [ -d "/tmp/build-src/$name/.git" ] && [ ! -d "$CACHE_DIR/$name/.git" ]; then
        cp -a "/tmp/build-src/$name" "$CACHE_DIR/$name"
    elif [ ! -d "$CACHE_DIR/$name/.git" ]; then
        echo "Cloning $url -> $CACHE_DIR/$name"
        git clone -q "$url" "$CACHE_DIR/$name"
    fi
    if ! git -C "$CACHE_DIR/$name" cat-file -e "$sha^{commit}" 2>/dev/null; then
        git -C "$CACHE_DIR/$name" fetch -q origin "$sha"
    fi
}

fetch_upstream hmgapi-wireless-cp-aa https://github.com/Open-HMG/hmgapi-wireless-cp-aa.git 7fea56def357ec15f605bc6631554f11c3d578e4
fetch_upstream hmgapi-adb            https://github.com/Open-HMG/hmgapi-adb.git            8d9dd39872e87702f32f8cbb90896c63ac77d6bc
fetch_upstream hmg-aa-dpi            https://github.com/eliotwass/hmg-aa-dpi.git           4222625d25e2776f926cf372ff3220fc3ec6d20c
fetch_upstream egmp-aa-ev-hmg-api    https://github.com/AwsomeFox/egmp-aa-ev-hmg-api.git   f12237caa205c8b13f86220169455388c30cf271
fetch_upstream light-vibes           https://github.com/lemoyneiv/light-vibes.git          2bdd33a506f380d2bc0305284856d185416afeff
fetch_upstream tinyalsa              https://github.com/tinyalsa/tinyalsa.git              a7cd26a17fe2add2ff38d2f281ed0b919187a068

WORK_SRC="$SUITE_ROOT/out/work-src"
rm -rf "$SUITE_ROOT/out" "$SUITE_ROOT/dist"
mkdir -p "$WORK_SRC" "$SUITE_ROOT/dist/mods"

prepare_copy() {
    name=$1
    sha=$2
    cp -a "$CACHE_DIR/$name" "$WORK_SRC/$name"
    git -C "$WORK_SRC/$name" checkout -qf "$sha"
    git -C "$WORK_SRC/$name" clean -fdx >/dev/null
}

prepare_copy hmgapi-wireless-cp-aa 7fea56def357ec15f605bc6631554f11c3d578e4
prepare_copy hmgapi-adb            8d9dd39872e87702f32f8cbb90896c63ac77d6bc
prepare_copy hmg-aa-dpi            4222625d25e2776f926cf372ff3220fc3ec6d20c
prepare_copy egmp-aa-ev-hmg-api    f12237caa205c8b13f86220169455388c30cf271
prepare_copy light-vibes           2bdd33a506f380d2bc0305284856d185416afeff

mkdir -p "$WORK_SRC/light-vibes/third_party"
cp -a "$CACHE_DIR/tinyalsa" "$WORK_SRC/light-vibes/third_party/tinyalsa"
git -C "$WORK_SRC/light-vibes/third_party/tinyalsa" checkout -qf a7cd26a17fe2add2ff38d2f281ed0b919187a068
git -C "$WORK_SRC/light-vibes/third_party/tinyalsa" clean -fdx >/dev/null

echo ""
echo "=================================================================="
echo " STEP 1: Preparing Upstream Mod Sources"
echo "=================================================================="
python3 "$SUITE_ROOT/patches/prepare-mods.py" --work-dir "$WORK_SRC"

echo ""
echo "=================================================================="
echo " STEP 2: Building, Testing & Signing wireless-carplay-aa 2.2.1"
echo "=================================================================="
(
    cd "$WORK_SRC/hmgapi-wireless-cp-aa"
    python3 "$HMGAPI_SDK/bin/check-points.py" wireless-carplay-aa
    sh "$HMGAPI_SDK/bin/build-mod.sh" wireless-carplay-aa out/build/wireless-carplay-aa out/work/wireless-carplay-aa
    for t in wireless-carplay-aa/test.sh wireless-carplay-aa/tools/*/test.sh; do
        echo "-- running $t"
        HMGAPI_MOD_BUILD="$PWD/out/build/wireless-carplay-aa" sh "$t"
    done
    sh "$HMGAPI_SDK/bin/check-scripts.sh" wireless-carplay-aa
    out=$(HMGAPI_MOD_BUILD="$PWD/out/build/wireless-carplay-aa" sh "$HMGAPI_SDK/bin/mkmodpkg.sh" wireless-carplay-aa "$HMGAPI_KEY")
    echo "$out"
    z=$(echo "$out" | tail -1)
    sh "$HMGAPI_SDK/bin/mknemesismod.sh" "$z"
    cp "$z" "$SUITE_ROOT/dist/mods/wireless-carplay-aa-v2.2.1.hmgapi.zip"
    cp out/dist/wireless-carplay-aa-2.2.1-nemesis-package.zip "$SUITE_ROOT/dist/mods/wireless-carplay-aa-v2.2.1-nemesis-package.zip"
)
WIRELESS_BUILT="$WORK_SRC/hmgapi-wireless-cp-aa/out/build/wireless-carplay-aa"

echo ""
echo "=================================================================="
echo " STEP 3: Building, Testing & Signing adb 1.0.1"
echo "=================================================================="
(
    cd "$WORK_SRC/hmgapi-adb"
    python3 "$HMGAPI_SDK/bin/check-points.py" adb
    sh "$HMGAPI_SDK/bin/build-mod.sh" adb out/build/adb out/work/adb
    HMGAPI_MOD_BUILD="$PWD/out/build/adb" sh adb/test.sh
    sh "$HMGAPI_SDK/bin/check-scripts.sh" adb
    out=$(HMGAPI_MOD_BUILD="$PWD/out/build/adb" sh "$HMGAPI_SDK/bin/mkmodpkg.sh" adb "$HMGAPI_KEY")
    echo "$out"
    z=$(echo "$out" | tail -1)
    sh "$HMGAPI_SDK/bin/mknemesismod.sh" "$z"
    cp "$z" "$SUITE_ROOT/dist/mods/adb-v1.0.1.hmgapi.zip"
    cp out/dist/adb-1.0.1-nemesis-package.zip "$SUITE_ROOT/dist/mods/adb-v1.0.1-nemesis-package.zip"
)

echo ""
echo "=================================================================="
echo " STEP 4: Building, Testing & Signing aa-dpi 0.3.0 (DPI 200, tap_limit=off, 60fps)"
echo "=================================================================="
(
    cd "$WORK_SRC/hmg-aa-dpi"
    python3 "$HMGAPI_SDK/bin/check-points.py" hmgapi-mod
    HMGAPI="$HMGAPI" sh hmgapi-mod/build.sh "$HMGAPI_KEY"
    HMGAPI="$HMGAPI" WIRELESS_MOD="$WIRELESS_BUILT" sh hmgapi-mod/test/run.sh
    sh "$HMGAPI_SDK/bin/mknemesismod.sh" dist/aa-dpi-0.3.0.zip
    cp dist/aa-dpi-0.3.0.zip "$SUITE_ROOT/dist/mods/aa-dpi-v0.3.0.hmgapi.zip"
    cp out/dist/aa-dpi-0.3.0-nemesis-package.zip "$SUITE_ROOT/dist/mods/aa-dpi-v0.3.0-nemesis-package.zip"
)

echo ""
echo "=================================================================="
echo " STEP 5: Building, Testing & Signing egmp-aa-ev 0.7.0"
echo "=================================================================="
(
    cd "$WORK_SRC/egmp-aa-ev-hmg-api"
    python3 "$HMGAPI_SDK/bin/check-points.py" hmgapi-mod
    HMGAPI="$HMGAPI" sh hmgapi-mod/build.sh "$HMGAPI_KEY"
    HMGAPI="$HMGAPI" WIRELESS_MOD="$WIRELESS_BUILT" sh hmgapi-mod/test/run.sh
    sh "$HMGAPI_SDK/bin/mknemesismod.sh" dist/egmp-aa-ev-0.7.0.zip
    cp dist/egmp-aa-ev-0.7.0.zip "$SUITE_ROOT/dist/mods/egmp-aa-ev-v0.7.0.hmgapi.zip"
    cp out/dist/egmp-aa-ev-0.7.0-nemesis-package.zip "$SUITE_ROOT/dist/mods/egmp-aa-ev-v0.7.0-nemesis-package.zip"
)

echo ""
echo "=================================================================="
echo " STEP 6: Building, Testing & Signing hmgapi-comfort 1.0.1"
echo "=================================================================="
sh "$SUITE_ROOT/hmgapi-comfort/build.sh"
cp "$SUITE_ROOT/hmgapi-comfort/dist/hmgapi-comfort-v1.0.1.hmgapi.zip" "$SUITE_ROOT/dist/mods/"
cp "$SUITE_ROOT/hmgapi-comfort/dist/hmgapi-comfort-v1.0.1-nemesis-package.zip" "$SUITE_ROOT/dist/mods/"

echo ""
echo "=================================================================="
echo " STEP 6b: Building, Testing & Signing light-vibes 0.6.7 (intense-effects)"
echo "=================================================================="
(
    cd "$WORK_SRC/light-vibes"
    python3 "$HMGAPI_SDK/bin/check-points.py" mod
    sh "$HMGAPI_SDK/bin/check-scripts.sh" mod
    ANDROID_HOME="$ANDROID_HOME" HMGAPI_KEY="$HMGAPI_KEY" HMGAPI_DIR="$HMGAPI" bash ./build.sh test
    LVVER=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' mod/mod.json)
    cp "dist/light-vibes-v${LVVER}.hmgapi.zip" "$SUITE_ROOT/dist/mods/light-vibes-v${LVVER}.hmgapi.zip"
    cp "dist/light-vibes-v${LVVER}-nemesis-package.zip" "$SUITE_ROOT/dist/mods/light-vibes-v${LVVER}-nemesis-package.zip"
)
LVVER=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$WORK_SRC/light-vibes/mod/mod.json")

if [ "$WITH_EXTRAS" = 1 ]; then
    echo ""
    echo "=================================================================="
    echo " STEP 6c: Building Optional Extra brake-indicator-gen5w"
    echo "=================================================================="
    BRAKE_REPO=${BRAKE_REPO:-$SUITE_ROOT/../brake-indicator-gen5w}
    [ -x "$BRAKE_REPO/build.sh" ] || { echo "Missing $BRAKE_REPO/build.sh" >&2; exit 1; }
    (cd "$BRAKE_REPO" && HMGAPI_SDK="$HMGAPI_SDK" HMGAPI_KEY="$HMGAPI_KEY" ./build.sh)
    BVER=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$BRAKE_REPO/brake-indicator-gen5w/mod.json")
    cp "$BRAKE_REPO/dist/brake-indicator-gen5w-v${BVER}.hmgapi.zip" "$SUITE_ROOT/dist/mods/"
    cp "$BRAKE_REPO/dist/brake-indicator-gen5w-v${BVER}-nemesis-package.zip" "$SUITE_ROOT/dist/mods/"
fi

CORE_VER=$(cat "$HMGAPI_SDK/VERSION")
CORE_NEM_PKG="$HMGAPI/out/dist/hmgapi-${CORE_VER}-nemesis-package.zip"
cp "$CORE_NEM_PKG" "$SUITE_ROOT/dist/mods/hmgapi-v${CORE_VER}-nemesis-package.zip"

echo ""
echo "=================================================================="
echo " STEP 7: Checking USB Installer Scripts & Assembling USB Sticks"
echo "=================================================================="
sh "$SUITE_ROOT/tools/installer-test.sh"

STAGE_EXEC="$SUITE_ROOT/out/usb-execute"
STAGE_LIST="$SUITE_ROOT/out/usb-list"
STAGE_UPD="$SUITE_ROOT/out/usb-updates"
mkdir -p "$STAGE_EXEC/packages" "$STAGE_EXEC/HMGAPI/updates" \
         "$STAGE_LIST/packages" "$STAGE_LIST/HMGAPI/updates" \
         "$STAGE_UPD/HMGAPI/updates"

# Populate HMGAPI/FORCE and ordered HMGAPI/updates/*.hmgapi.zip
for d in "$STAGE_EXEC" "$STAGE_LIST" "$STAGE_UPD"; do
    : > "$d/HMGAPI/FORCE"
    cp "$SUITE_ROOT/dist/mods/wireless-carplay-aa-v2.2.1.hmgapi.zip" "$d/HMGAPI/updates/01-wireless-carplay-aa.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/adb-v1.0.1.hmgapi.zip"                 "$d/HMGAPI/updates/02-adb.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/aa-dpi-v0.3.0.hmgapi.zip"              "$d/HMGAPI/updates/03-aa-dpi.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/egmp-aa-ev-v0.7.0.hmgapi.zip"          "$d/HMGAPI/updates/04-egmp-aa-ev.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/hmgapi-comfort-v1.0.1.hmgapi.zip"      "$d/HMGAPI/updates/05-hmgapi-comfort.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/light-vibes-v${LVVER}.hmgapi.zip"      "$d/HMGAPI/updates/06-light-vibes.hmgapi.zip"
    if [ "$WITH_EXTRAS" = 1 ]; then
        cp "$SUITE_ROOT/dist/mods/brake-indicator-gen5w-v${BVER}.hmgapi.zip" "$d/HMGAPI/updates/07-brake-indicator-gen5w.hmgapi.zip"
    fi
done

# Populate NEMESIS packages/ folder for both NEMESIS sticks
for d in "$STAGE_EXEC" "$STAGE_LIST"; do
    cp "$CORE_NEM_PKG"                                                         "$d/packages/hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/wireless-carplay-aa-v2.2.1-nemesis-package.zip" "$d/packages/hmgapi.wireless-carplay-aa.zip"
    cp "$SUITE_ROOT/dist/mods/adb-v1.0.1-nemesis-package.zip"                 "$d/packages/hmgapi.adb.zip"
    cp "$SUITE_ROOT/dist/mods/aa-dpi-v0.3.0-nemesis-package.zip"              "$d/packages/hmgapi.aa-dpi.zip"
    cp "$SUITE_ROOT/dist/mods/egmp-aa-ev-v0.7.0-nemesis-package.zip"          "$d/packages/hmgapi.egmp-aa-ev.zip"
    cp "$SUITE_ROOT/dist/mods/hmgapi-comfort-v1.0.1-nemesis-package.zip"      "$d/packages/hmgapi.hmgapi-comfort.zip"
    cp "$SUITE_ROOT/dist/mods/light-vibes-v${LVVER}-nemesis-package.zip"      "$d/packages/hmgapi.light-vibes.zip"
    if [ "$WITH_EXTRAS" = 1 ]; then
        cp "$SUITE_ROOT/dist/mods/brake-indicator-gen5w-v${BVER}-nemesis-package.zip" "$d/packages/hmgapi.brake-indicator-gen5w.zip"
    fi
done

# One-tap execute stick root files
cp "$SUITE_ROOT/usb/NEMESIS.data" "$STAGE_EXEC/NEMESIS.data"
cp "$SUITE_ROOT/usb/NEMESIS.sh"   "$STAGE_EXEC/NEMESIS.sh"
chmod 755 "$STAGE_EXEC/NEMESIS.sh"

# Interactive NEMESIS Package Store USB list stick descriptor
TODAY=$(date -u '+%Y-%m-%d')
cat > "$STAGE_LIST/NEMESIS.data" <<EOF
[nemesis]
version=1.1
type=list

[package.0]
path=packages/hmgapi.zip
name=HMG-API Core (${CORE_VER} BLE + VehicleHub + Home Tiles)
short-desc=Install first: HMG-API runtime, BLE central, Home Screen Tiles, and VehicleHub
published=${TODAY}

[package.1]
path=packages/hmgapi.wireless-carplay-aa.zip
name=Wireless CarPlay + Android Auto (2.2.1)
short-desc=Wireless CP/AA (rc.2), cluster/HUD TBT, BT focus guard, freeze & audio fixes
published=${TODAY}

[package.2]
path=packages/hmgapi.adb.zip
name=ADB over Wi-Fi / USB (1.0.1)
short-desc=Hotspot ADB on port 5555 with USB host mode kept safe by default
published=${TODAY}

[package.3]
path=packages/hmgapi.aa-dpi.zip
name=Android Auto DPI (0.3.0)
short-desc=Target 200 DPI, tap limit off, and 60 fps video window
published=${TODAY}

[package.4]
path=packages/hmgapi.egmp-aa-ev.zip
name=EV Routing for Android Auto (0.7.0)
short-desc=Google Maps EV SoC, charge curves, and DC preconditioning
published=${TODAY}

[package.5]
path=packages/hmgapi.hmgapi-comfort.zip
name=Comfort & Startup (1.0.1)
short-desc=Multi-tier auto seat/wheel heat + ventilation & instant welcome dismisser
published=${TODAY}

[package.6]
path=packages/hmgapi.light-vibes.zip
name=Light Vibes (${LVVER} Intense Effects)
short-desc=Music & speed reactive ambient lighting for Gen5W
published=${TODAY}
EOF

if [ "$WITH_EXTRAS" = 1 ]; then
    cat >> "$STAGE_LIST/NEMESIS.data" <<EOF

[package.7]
path=packages/hmgapi.brake-indicator-gen5w.zip
name=Brake Light Indicator (${BVER})
short-desc=Status-bar brake lamp indicator with E-GMP regen + BLE OBD support
published=${TODAY}
EOF
fi

echo ""
echo "=================================================================="
echo " STEP 8: End-to-End Multi-Mod Integration Test (260507 & 260903)"
echo "=================================================================="
sh "$SUITE_ROOT/tools/integration-test.sh" "$STAGE_EXEC/HMGAPI/updates"

(cd "$STAGE_EXEC" && zip -qr "$SUITE_ROOT/dist/gen5w-suite-usb.zip" .)
(cd "$STAGE_LIST" && zip -qr "$SUITE_ROOT/dist/gen5w-suite-nemesis-store-usb.zip" .)
(cd "$STAGE_UPD"  && zip -qr "$SUITE_ROOT/dist/gen5w-suite-hmgapi-updates-usb.zip" .)

(cd "$SUITE_ROOT/dist" && sha256sum *.zip mods/*.zip > SHA256SUMS)
echo ""
echo "=================================================================="
echo " Gen5W Suite Build & Verification SUCCEEDED!"
echo "=================================================================="
cat "$SUITE_ROOT/dist/SHA256SUMS"
