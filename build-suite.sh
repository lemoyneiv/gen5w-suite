#!/bin/sh
# Build, test, sign, integration-verify, and package the complete Gen5W Suite:
#
#   ./build-suite.sh    build core + 7 mods + unified USB installers + standalone Comfort, Light Vibes & EQ Engine
#
# Outputs in dist/:
#   dist/gen5w-suite-usb.zip                 One-tap NEMESIS execute USB stick (recommended)
#   dist/gen5w-suite-nemesis-store-usb.zip   Interactive NEMESIS USB Package Store stick (type=list)
#   dist/gen5w-suite-hmgapi-updates-usb.zip  HMG-API Updates stick (HMGAPI/updates/*.zip)
#   dist/standalone/*.zip                    Standalone Comfort (1.1.11), Light Vibes (0.7.0) & EQ Engine (0.7.8)
#   dist/mods/*.zip                          Individual signed HMG-API + NEMESIS packages
#   dist/SHA256SUMS                          SHA-256 checksums
set -e
umask 022
cd "$(dirname "$0")"
SUITE_ROOT=$PWD

if [ $# -gt 0 ]; then
    echo "usage: $0" >&2
    exit 2
fi

# 1. Locate Android SDK, HMG-API fork checkout, Mod SDK, and persistent signing keys
if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -d /opt/android-sdk ]; then ANDROID_HOME=/opt/android-sdk
    elif [ -d "$HOME/Android" ]; then ANDROID_HOME="$HOME/Android"
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
if [ ! -f "$HMGAPI/out/sdk/VERSION" ] || [ "$(cat "$HMGAPI/out/sdk/VERSION" 2>/dev/null)" != "1.1.1-rc.24" ] || [ ! -f "$HMGAPI/out/dist/hmgapi-1.1.1-rc.24-nemesis-package.zip" ]; then
    echo "== Building HMG-API Core 1.1.1-rc.24 & Mod SDK =="
    (cd "$HMGAPI" && HMGAPI_KEY="$CORE_APP_KEY" ./build.sh && ./build.sh sdk && sh tools/mkinstallers.sh)
fi
HMGAPI_SDK=${HMGAPI_SDK:-$HMGAPI/out/sdk}
export HMGAPI_SDK
export HMGAPI_FLOOR_POINTS="$HMGAPI_SDK/points.txt"

# 2. Fetch (or reuse cached) mod repositories at pinned commits + official upstream releases
CACHE_DIR=${MOD_CACHE_DIR:-$SUITE_ROOT/.cache/upstream}
REL_CACHE=${RELEASE_CACHE_DIR:-$SUITE_ROOT/.cache/releases}
mkdir -p "$CACHE_DIR" "$REL_CACHE/adb" "$REL_CACHE/egmp" "$REL_CACHE/sdk"

fetch_upstream() {
    name=$1
    url=$2
    sha=$3
    if [ -d "$SUITE_ROOT/../$name/.git" ] && [ ! -d "$CACHE_DIR/$name/.git" ]; then
        cp -a "$SUITE_ROOT/../$name" "$CACHE_DIR/$name"
    elif [ -d "/tmp/build-src/$name/.git" ] && [ ! -d "$CACHE_DIR/$name/.git" ]; then
        cp -a "/tmp/build-src/$name" "$CACHE_DIR/$name"
    elif [ ! -d "$CACHE_DIR/$name/.git" ]; then
        echo "Cloning $url -> $CACHE_DIR/$name"
        git clone -q "$url" "$CACHE_DIR/$name"
    fi
    git -C "$CACHE_DIR/$name" remote set-url origin "$url" 2>/dev/null || git -C "$CACHE_DIR/$name" remote add origin "$url"
    if ! git -C "$CACHE_DIR/$name" cat-file -e "$sha^{commit}" 2>/dev/null; then
        if [ -d "$SUITE_ROOT/../$name/.git" ] && git -C "$SUITE_ROOT/../$name" cat-file -e "$sha^{commit}" 2>/dev/null; then
            git -C "$CACHE_DIR/$name" fetch -q "$SUITE_ROOT/../$name" "$sha"
        else
            git -C "$CACHE_DIR/$name" fetch -q origin "$sha" || git -C "$CACHE_DIR/$name" fetch -q origin --tags '+refs/heads/*:refs/remotes/origin/*'
        fi
    fi
}

fetch_release_asset() {
    repo=$1
    tag=$2
    file=$3
    sha=$4
    dest=$5
    if [ ! -f "$dest" ]; then
        mkdir -p "$(dirname "$dest")"
        url="https://github.com/${repo}/releases/download/${tag}/${file}"
        echo "Downloading official release ${repo}@${tag}: ${file}"
        if command -v curl >/dev/null 2>&1 && curl -fsSL -o "$dest" "$url" 2>/dev/null; then
            :
        elif command -v gh >/dev/null 2>&1; then
            gh release download "$tag" -R "$repo" --pattern "$file" --dir "$(dirname "$dest")" --clobber
        else
            echo "build-suite.sh: missing $dest and neither curl nor gh could download $url" >&2
            exit 1
        fi
    fi
    echo "${sha}  ${dest}" | sha256sum -c - >/dev/null
}

verify_official_mod() {
    zip_path=$1
    expected_uuid=$2
    tmp_dir=$(mktemp -d)
    unzip -q "$zip_path" -d "$tmp_dir"
    java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" hmgapi.engine.Signing verify "$tmp_dir" "$expected_uuid"
    python3 "$HMGAPI_SDK/bin/check-points.py" "$tmp_dir"
    sh "$HMGAPI_SDK/bin/check-scripts.sh" "$tmp_dir"
    rm -rf "$tmp_dir"
}

wrap_standalone_usb() {
    mod_id=$1
    mod_ver=$2
    hmg_zip=$3
    nem_pkg=$4
    out_dir=$5
    cp "$hmg_zip" "$out_dir/${mod_id}-v${mod_ver}.hmgapi.zip"
    cp "$nem_pkg" "$out_dir/${mod_id}-v${mod_ver}-nemesis-package.zip"
    python3 - "$mod_id" "$mod_ver" "$hmg_zip" "$nem_pkg" "$out_dir" <<'PY'
import os, sys, zipfile
mid, ver, hmg_zip, nem_pkg, out_dir = sys.argv[1:6]
hmg_usb = os.path.join(out_dir, f"{mid}-v{ver}-hmgapi-usb.zip")
nem_usb = os.path.join(out_dir, f"{mid}-v{ver}-nemesis-usb.zip")
with zipfile.ZipFile(hmg_usb, "w", zipfile.ZIP_STORED) as z:
    zi = zipfile.ZipInfo(f"HMGAPI/updates/{mid}-v{ver}.zip", (2026, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED
    zi.external_attr = 0o644 << 16
    z.writestr(zi, open(hmg_zip, "rb").read())
with zipfile.ZipFile(nem_usb, "w", zipfile.ZIP_DEFLATED) as z:
    ndata = f"[nemesis]\nversion=1.0\ntype=install\npath=packages/hmgapi.{mid}.zip\n".encode("utf-8")
    zi_d = zipfile.ZipInfo("NEMESIS.data", (2026, 1, 1, 0, 0, 0))
    zi_d.external_attr = 0o644 << 16
    z.writestr(zi_d, ndata)
    zi_p = zipfile.ZipInfo(f"packages/hmgapi.{mid}.zip", (2026, 1, 1, 0, 0, 0))
    zi_p.compress_type = zipfile.ZIP_STORED
    zi_p.external_attr = 0o644 << 16
    z.writestr(zi_p, open(nem_pkg, "rb").read())
PY
}

WIRELESS_SHA=084486ac13260d27937bd9a69c996e94acfbea57
COMFORT_SHA=32b5209e628cc533a55ea966996313699f19be6f
LIGHT_VIBES_SHA=dc0704927920e43882f84bc35ef87dbb6eeed9c6
EQ_ENGINE_SHA=2feb050ebc63aaf1aeca10d136b5a7bb0add1002
ADB_TAG=v1.0.1-rc.1
ADB_VER=1.0.1-rc.1
ADB_UUID=7251ce71-a146-8a1e-958b-dbffc6af7359
EGMP_TAG=v0.8.2
EGMP_VER=0.8.2
EGMP_UUID=839375d3-8aea-884a-a9e9-c79964fe5d88
LEGACY_SUITE_UUID=28ad71e0-07c0-8e61-8bf3-380cc71f9280
WIRELESS_REL_SHA=3c169c30242a030876b9f7c2af8d441efb99cc287659fd5d129a53e4f67eacd4
AADPI_REL_SHA=60ffccd21822a4334080329a4bdf4a30ffc82bb5b5d600f5aee2c68514866a17
COMFORT_REL_SHA=6dbe534dd33c5cabea7734548fbbcc366938c8ea62ec7480d4633c5a041fc354
LIGHT_VIBES_REL_SHA=027724010e5ca198b086f5d49afb7d137efd40af6c3f3271dd2b30be4cc5e641

fetch_upstream hmgapi-wireless-cp-aa https://github.com/lemoyneiv/hmgapi-wireless-cp-aa.git "$WIRELESS_SHA"
fetch_upstream hmg-aa-dpi            https://github.com/eliotwass/hmg-aa-dpi.git           4222625d25e2776f926cf372ff3220fc3ec6d20c
fetch_upstream hmgapi-comfort        https://github.com/lemoyneiv/hmgapi-comfort.git       "$COMFORT_SHA"
fetch_upstream light-vibes           https://github.com/lemoyneiv/light-vibes.git          "$LIGHT_VIBES_SHA"
fetch_upstream tinyalsa              https://github.com/tinyalsa/tinyalsa.git              a7cd26a17fe2add2ff38d2f281ed0b919187a068
fetch_upstream Gen5W-EQ-Engine       https://github.com/alexkozler/Gen5W-EQ-Engine.git     "$EQ_ENGINE_SHA"

fetch_release_asset Open-HMG/HMG-API v1.1.0-rc.10 hmgapi-mod-sdk-1.1.0-rc.10.zip \
    48e362e3ec83d826c384b21c8237eb475d87b4e4c9dbc7bcc7dfcc1178b68368 \
    "$REL_CACHE/sdk/hmgapi-mod-sdk-1.1.0-rc.10.zip"
fetch_release_asset Open-HMG/hmgapi-adb "$ADB_TAG" "adb-${ADB_VER}.zip" \
    37f18d4fd5161b91bd740345613ea5803cc1bc766202513cd736739f27bd9538 \
    "$REL_CACHE/adb/adb-${ADB_VER}.zip"
fetch_release_asset Open-HMG/hmgapi-adb "$ADB_TAG" "adb-${ADB_VER}-nemesis-package.zip" \
    043ad962e76be25584832919be9a0f6ef401eec882c07a3b301d3b3739503a1a \
    "$REL_CACHE/adb/adb-${ADB_VER}-nemesis-package.zip"
fetch_release_asset AwsomeFox/egmp-aa-ev-hmg-api "$EGMP_TAG" "egmp-aa-ev-${EGMP_VER}.zip" \
    bbda9d0387a2ed74f0e737f43913fad8c4fbb2d6a9abccabe8b37de2fef14b21 \
    "$REL_CACHE/egmp/egmp-aa-ev-${EGMP_VER}.zip"
fetch_release_asset AwsomeFox/egmp-aa-ev-hmg-api "$EGMP_TAG" "egmp-aa-ev-${EGMP_VER}-nemesis-package.zip" \
    cf9f4d6a5aa37b56e68caace636548011cbfb00eabe110d57190531cc9cb10e0 \
    "$REL_CACHE/egmp/egmp-aa-ev-${EGMP_VER}-nemesis-package.zip"

WORK_SRC="$SUITE_ROOT/out/work-src"
rm -rf "$SUITE_ROOT/out" "$SUITE_ROOT/dist"
mkdir -p "$WORK_SRC" "$SUITE_ROOT/dist/mods" "$SUITE_ROOT/dist/standalone"

prepare_copy() {
    name=$1
    sha=$2
    cp -a "$CACHE_DIR/$name" "$WORK_SRC/$name"
    git -C "$WORK_SRC/$name" checkout -qf "$sha"
    git -C "$WORK_SRC/$name" clean -fdx >/dev/null
}

prepare_copy hmgapi-wireless-cp-aa "$WIRELESS_SHA"
prepare_copy hmg-aa-dpi            4222625d25e2776f926cf372ff3220fc3ec6d20c
prepare_copy hmgapi-comfort        "$COMFORT_SHA"
prepare_copy light-vibes           "$LIGHT_VIBES_SHA"
prepare_copy Gen5W-EQ-Engine       "$EQ_ENGINE_SHA"

mkdir -p "$WORK_SRC/light-vibes/third_party"
cp -a "$CACHE_DIR/tinyalsa" "$WORK_SRC/light-vibes/third_party/tinyalsa"
git -C "$WORK_SRC/light-vibes/third_party/tinyalsa" checkout -qf a7cd26a17fe2add2ff38d2f281ed0b919187a068
git -C "$WORK_SRC/light-vibes/third_party/tinyalsa" clean -fdx >/dev/null

echo ""
echo "=================================================================="
echo " STEP 1: Preparing Patched Mod Sources"
echo "=================================================================="
python3 "$SUITE_ROOT/patches/prepare-mods.py" --work-dir "$WORK_SRC" --hmgapi "$HMGAPI"

echo ""
echo "=================================================================="
echo " STEP 2: Building, Testing & Signing wireless-carplay-aa 2.2.7"
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
    cp "$z" "$SUITE_ROOT/dist/mods/wireless-carplay-aa-v2.2.7.hmgapi.zip"
    cp out/dist/wireless-carplay-aa-2.2.7-nemesis-package.zip "$SUITE_ROOT/dist/mods/wireless-carplay-aa-v2.2.7-nemesis-package.zip"
    mkdir -p out/staged/wireless-carplay-aa
    unzip -q "$z" -d out/staged/wireless-carplay-aa
)
WIRELESS_BUILT="$WORK_SRC/hmgapi-wireless-cp-aa/out/staged/wireless-carplay-aa"

echo ""
echo "=================================================================="
echo " STEP 3: Verifying Official Upstream adb ${ADB_VER} (Open-HMG/hmgapi-adb)"
echo "=================================================================="
verify_official_mod "$REL_CACHE/adb/adb-${ADB_VER}.zip" "$ADB_UUID"
cp "$REL_CACHE/adb/adb-${ADB_VER}.zip"                 "$SUITE_ROOT/dist/mods/adb-v${ADB_VER}.hmgapi.zip"
cp "$REL_CACHE/adb/adb-${ADB_VER}-nemesis-package.zip" "$SUITE_ROOT/dist/mods/adb-v${ADB_VER}-nemesis-package.zip"

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
if [ -f "$REL_CACHE/suite-signed/aa-dpi-v0.3.0.hmgapi.zip" ] && \
   echo "${AADPI_REL_SHA}  $REL_CACHE/suite-signed/aa-dpi-v0.3.0.hmgapi.zip" | sha256sum -c - >/dev/null 2>&1; then
    verify_official_mod "$REL_CACHE/suite-signed/aa-dpi-v0.3.0.hmgapi.zip" "$LEGACY_SUITE_UUID"
    cp "$REL_CACHE/suite-signed/aa-dpi-v0.3.0.hmgapi.zip" "$SUITE_ROOT/dist/mods/aa-dpi-v0.3.0.hmgapi.zip"
    cp "$REL_CACHE/suite-signed/aa-dpi-v0.3.0-nemesis-package.zip" "$SUITE_ROOT/dist/mods/aa-dpi-v0.3.0-nemesis-package.zip"
fi

echo ""
echo "=================================================================="
echo " STEP 5: Verifying Official Upstream egmp-aa-ev ${EGMP_VER} (AwsomeFox)"
echo "=================================================================="
verify_official_mod "$REL_CACHE/egmp/egmp-aa-ev-${EGMP_VER}.zip" "$EGMP_UUID"
cp "$REL_CACHE/egmp/egmp-aa-ev-${EGMP_VER}.zip"                 "$SUITE_ROOT/dist/mods/egmp-aa-ev-v${EGMP_VER}.hmgapi.zip"
cp "$REL_CACHE/egmp/egmp-aa-ev-${EGMP_VER}-nemesis-package.zip" "$SUITE_ROOT/dist/mods/egmp-aa-ev-v${EGMP_VER}-nemesis-package.zip"

CVER=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$WORK_SRC/hmgapi-comfort/hmgapi-comfort/mod.json")
echo ""
echo "=================================================================="
echo " STEP 6: Building, Testing & Verifying hmgapi-comfort ${CVER} (standalone repo)"
echo "=================================================================="
(
    cd "$WORK_SRC/hmgapi-comfort"
    mkdir -p .cache/sdk
    cp "$REL_CACHE/sdk/hmgapi-mod-sdk-1.1.0-rc.10.zip" .cache/sdk/
    env -u HMGAPI_SDK -u HMGAPI_FLOOR_POINTS sh ./build.sh
)
COMFORT_HMG="$WORK_SRC/hmgapi-comfort/dist/hmgapi-comfort-v${CVER}.zip"
COMFORT_NEM="$WORK_SRC/hmgapi-comfort/dist/hmgapi-comfort-v${CVER}-nemesis-package.zip"
if [ -f "$REL_CACHE/suite-signed/hmgapi-comfort-v${CVER}.hmgapi.zip" ] && \
   echo "${COMFORT_REL_SHA}  $REL_CACHE/suite-signed/hmgapi-comfort-v${CVER}.hmgapi.zip" | sha256sum -c - >/dev/null 2>&1; then
    verify_official_mod "$REL_CACHE/suite-signed/hmgapi-comfort-v${CVER}.hmgapi.zip" "$LEGACY_SUITE_UUID"
    COMFORT_HMG="$REL_CACHE/suite-signed/hmgapi-comfort-v${CVER}.hmgapi.zip"
    COMFORT_NEM="$REL_CACHE/suite-signed/hmgapi-comfort-v${CVER}-nemesis-package.zip"
fi
cp "$COMFORT_HMG" "$SUITE_ROOT/dist/mods/hmgapi-comfort-v${CVER}.hmgapi.zip"
cp "$COMFORT_NEM" "$SUITE_ROOT/dist/mods/hmgapi-comfort-v${CVER}-nemesis-package.zip"
wrap_standalone_usb "hmgapi-comfort" "$CVER" "$COMFORT_HMG" "$COMFORT_NEM" "$SUITE_ROOT/dist/standalone"

echo ""
echo "=================================================================="
echo " STEP 6b: Building, Testing & Signing light-vibes 0.7.0 (intense-effects)"
echo "=================================================================="
(
    cd "$WORK_SRC/light-vibes"
    python3 "$HMGAPI_SDK/bin/check-points.py" mod
    sh "$HMGAPI_SDK/bin/check-scripts.sh" mod
    ZIG_WRAP_DIR="$PWD/out/zig-bin"
    if ! command -v zig >/dev/null 2>&1; then
        mkdir -p "$ZIG_WRAP_DIR"
        printf '#!/bin/sh\nexec python3 -m ziglang "$@"\n' > "$ZIG_WRAP_DIR/zig"
        chmod +x "$ZIG_WRAP_DIR/zig"
        export PATH="$ZIG_WRAP_DIR:$PATH"
    fi
    ANDROID_HOME="$ANDROID_HOME" HMGAPI_KEY="$HMGAPI_KEY" HMGAPI_DIR="$HMGAPI" bash ./build.sh test
)
LVVER=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$WORK_SRC/light-vibes/mod/mod.json")
LV_HMG="$WORK_SRC/light-vibes/dist/light-vibes-v${LVVER}.hmgapi.zip"
LV_NEM="$WORK_SRC/light-vibes/dist/light-vibes-v${LVVER}-nemesis-package.zip"
if [ -f "$REL_CACHE/suite-signed/light-vibes-v${LVVER}.hmgapi.zip" ] && \
   echo "${LIGHT_VIBES_REL_SHA}  $REL_CACHE/suite-signed/light-vibes-v${LVVER}.hmgapi.zip" | sha256sum -c - >/dev/null 2>&1; then
    verify_official_mod "$REL_CACHE/suite-signed/light-vibes-v${LVVER}.hmgapi.zip" "$LEGACY_SUITE_UUID"
    LV_HMG="$REL_CACHE/suite-signed/light-vibes-v${LVVER}.hmgapi.zip"
    LV_NEM="$REL_CACHE/suite-signed/light-vibes-v${LVVER}-nemesis-package.zip"
fi
cp "$LV_HMG" "$SUITE_ROOT/dist/mods/light-vibes-v${LVVER}.hmgapi.zip"
cp "$LV_NEM" "$SUITE_ROOT/dist/mods/light-vibes-v${LVVER}-nemesis-package.zip"
wrap_standalone_usb "light-vibes" "$LVVER" "$LV_HMG" "$LV_NEM" "$SUITE_ROOT/dist/standalone"

EQVER=0.7.8
echo ""
echo "=================================================================="
echo " STEP 6c: Building, Testing & Signing eq-engine ${EQVER} (Gen5W-EQ-Engine)"
echo "=================================================================="
(
    cd "$WORK_SRC/Gen5W-EQ-Engine"
    ANDROID_HOME="$ANDROID_HOME" HMGAPI_SDK="$HMGAPI_SDK" HMGAPI_KEY="$HMGAPI_KEY" sh ./build-hmgapi.sh
)
EQ_HMG="$WORK_SRC/Gen5W-EQ-Engine/dist/eq-engine-v${EQVER}.hmgapi.zip"
EQ_NEM="$WORK_SRC/Gen5W-EQ-Engine/dist/eq-engine-v${EQVER}-nemesis-package.zip"
cp "$EQ_HMG" "$SUITE_ROOT/dist/mods/eq-engine-v${EQVER}.hmgapi.zip"
cp "$EQ_NEM" "$SUITE_ROOT/dist/mods/eq-engine-v${EQVER}-nemesis-package.zip"
wrap_standalone_usb "eq-engine" "$EQVER" "$EQ_HMG" "$EQ_NEM" "$SUITE_ROOT/dist/standalone"

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
    cp "$SUITE_ROOT/dist/mods/wireless-carplay-aa-v2.2.7.hmgapi.zip" "$d/HMGAPI/updates/01-wireless-carplay-aa.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/adb-v${ADB_VER}.hmgapi.zip"            "$d/HMGAPI/updates/02-adb.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/aa-dpi-v0.3.0.hmgapi.zip"              "$d/HMGAPI/updates/03-aa-dpi.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/egmp-aa-ev-v${EGMP_VER}.hmgapi.zip"    "$d/HMGAPI/updates/04-egmp-aa-ev.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/hmgapi-comfort-v${CVER}.hmgapi.zip"    "$d/HMGAPI/updates/05-hmgapi-comfort.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/light-vibes-v${LVVER}.hmgapi.zip"      "$d/HMGAPI/updates/06-light-vibes.hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/eq-engine-v${EQVER}.hmgapi.zip"        "$d/HMGAPI/updates/07-eq-engine.hmgapi.zip"
done

# Populate NEMESIS packages/ folder for both NEMESIS sticks
for d in "$STAGE_EXEC" "$STAGE_LIST"; do
    cp "$CORE_NEM_PKG"                                                         "$d/packages/hmgapi.zip"
    cp "$SUITE_ROOT/dist/mods/wireless-carplay-aa-v2.2.7-nemesis-package.zip" "$d/packages/hmgapi.wireless-carplay-aa.zip"
    cp "$SUITE_ROOT/dist/mods/adb-v${ADB_VER}-nemesis-package.zip"            "$d/packages/hmgapi.adb.zip"
    cp "$SUITE_ROOT/dist/mods/aa-dpi-v0.3.0-nemesis-package.zip"              "$d/packages/hmgapi.aa-dpi.zip"
    cp "$SUITE_ROOT/dist/mods/egmp-aa-ev-v${EGMP_VER}-nemesis-package.zip"    "$d/packages/hmgapi.egmp-aa-ev.zip"
    cp "$SUITE_ROOT/dist/mods/hmgapi-comfort-v${CVER}-nemesis-package.zip"    "$d/packages/hmgapi.hmgapi-comfort.zip"
    cp "$SUITE_ROOT/dist/mods/light-vibes-v${LVVER}-nemesis-package.zip"      "$d/packages/hmgapi.light-vibes.zip"
    cp "$SUITE_ROOT/dist/mods/eq-engine-v${EQVER}-nemesis-package.zip"        "$d/packages/hmgapi.eq-engine.zip"
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
name=HMG-API Core (${CORE_VER} FastStart + Home Wi-Fi + BLE + VehicleHub)
short-desc=Install first: HMG-API runtime, Cold-Boot FastStart, Home Wi-Fi & mobile data UI, BLE, and VehicleHub
published=${TODAY}

[package.1]
path=packages/hmgapi.wireless-carplay-aa.zip
name=Wireless CarPlay + Android Auto (2.2.7)
short-desc=Wireless CP/AA (TBT & compass overhaul), AR HUD/cluster intersections, BT focus & freeze fixes
published=${TODAY}

[package.2]
path=packages/hmgapi.adb.zip
name=ADB over Wi-Fi / USB (${ADB_VER} Official Open-HMG)
short-desc=Hotspot ADB on port 5555 with USB host mode kept safe by suite settings
published=${TODAY}

[package.3]
path=packages/hmgapi.aa-dpi.zip
name=Android Auto DPI (0.3.0)
short-desc=Target 200 DPI, tap limit off, and 60 fps video window
published=${TODAY}

[package.4]
path=packages/hmgapi.egmp-aa-ev.zip
name=EV Routing for Android Auto (${EGMP_VER} Official AwsomeFox)
short-desc=Google Maps EV SoC, charge curves, and DC preconditioning
published=${TODAY}

[package.5]
path=packages/hmgapi.hmgapi-comfort.zip
name=Comfort & Startup (${CVER})
short-desc=Multi-tier auto seat/wheel heat + ventilation & instant welcome dismisser
published=${TODAY}

[package.6]
path=packages/hmgapi.light-vibes.zip
name=Light Vibes (${LVVER} Intense Effects)
short-desc=Music & speed reactive ambient lighting for Gen5W
published=${TODAY}

[package.7]
path=packages/hmgapi.eq-engine.zip
name=EQ Engine (${EQVER} Home Screen Tile + 48 kHz + CarPlay EQ)
short-desc=System 10-band parametric / 5-band graphic EQ, CarPlay audio EQ, VHQ resampler & 48 kHz output
published=${TODAY}
EOF

echo ""
echo "=================================================================="
echo " STEP 8: End-to-End Multi-Mod & Standalone Integration Tests"
echo "=================================================================="
sh "$SUITE_ROOT/tools/integration-test.sh" "$STAGE_EXEC/HMGAPI/updates" "$SUITE_ROOT/dist/standalone"

(cd "$STAGE_EXEC" && zip -qr "$SUITE_ROOT/dist/gen5w-suite-usb.zip" .)
(cd "$STAGE_LIST" && zip -qr "$SUITE_ROOT/dist/gen5w-suite-nemesis-store-usb.zip" .)
(cd "$STAGE_UPD"  && zip -qr "$SUITE_ROOT/dist/gen5w-suite-hmgapi-updates-usb.zip" .)

(cd "$SUITE_ROOT/dist/standalone" && sha256sum *.zip > SHA256SUMS)
(cd "$SUITE_ROOT/dist" && sha256sum *.zip standalone/*.zip mods/*.zip > SHA256SUMS)
echo ""
echo "=================================================================="
echo " Gen5W Suite Build & Verification SUCCEEDED!"
echo "=================================================================="
cat "$SUITE_ROOT/dist/SHA256SUMS"
