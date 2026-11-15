#!/bin/sh
# Build, test, and sign installable HMG-API and NEMESIS packages for hmgapi-comfort into dist/.
set -e
cd "$(dirname "$0")"
MOD=hmgapi-comfort
VER=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$MOD/mod.json")

if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -d "$HOME/Android" ]; then ANDROID_HOME="$HOME/Android"
    elif [ -d /opt/homebrew/share/android-commandlinetools ]; then ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    elif [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME="$HOME/Library/Android/sdk"
    fi
fi
export ANDROID_HOME

if [ -z "${HMGAPI_SDK:-}" ]; then
    if [ -d ../../HMG-API/out/sdk ]; then
        HMGAPI_SDK=$(cd ../../HMG-API/out/sdk && pwd)
    elif [ -d ../HMG-API/out/sdk ]; then
        HMGAPI_SDK=$(cd ../HMG-API/out/sdk && pwd)
    elif [ -x ../../HMG-API/build.sh ]; then
        (cd ../../HMG-API && ./build.sh sdk)
        HMGAPI_SDK=$(cd ../../HMG-API/out/sdk && pwd)
    else
        echo "build.sh: set HMGAPI_SDK to an unpacked HMG-API mod SDK" >&2
        exit 1
    fi
fi
export HMGAPI_SDK

if [ -n "${HMGAPI_KEY:-}" ]; then
    KEY_DIR=$(dirname "$HMGAPI_KEY")
elif [ -f "$HOME/.hmgapi/author.key" ]; then
    KEY_DIR="$HOME/.hmgapi"
    HMGAPI_KEY="$KEY_DIR/author.key"
elif [ -d "$HOME/.gemini" ]; then
    KEY_DIR="$HOME/.gemini/hmgapi"
    HMGAPI_KEY="$KEY_DIR/author.key"
else
    KEY_DIR="$HOME/.hmgapi"
    HMGAPI_KEY="$KEY_DIR/author.key"
fi

umask 077
mkdir -p "$KEY_DIR"
if [ ! -f "$HMGAPI_KEY" ]; then
    java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" hmgapi.engine.Signing keygen "$HMGAPI_KEY"
fi
export HMGAPI_APP_KEY=${HMGAPI_APP_KEY:-$KEY_DIR/hmgapi-comfort-app.p12}
if [ ! -f "$HMGAPI_APP_KEY" ]; then
    keytool -genkeypair -keystore "$HMGAPI_APP_KEY" -storetype pkcs12 \
        -storepass example -keypass example -alias app \
        -keyalg RSA -keysize 2048 -validity 15000 \
        -dname "CN=Comfort and Startup Gen5W" >/dev/null 2>&1
fi
umask 022

echo "== 1. Build APK & stage mod =="
rm -rf out/build out/work out/packages out/dist dist
sh "$HMGAPI_SDK/bin/build-mod.sh" "$MOD" "out/build/$MOD" "out/work/$MOD"

echo "== 2. Run tests & head-unit script check =="
sh "$MOD/tools/unit/test.sh"
HMGAPI_MOD_BUILD="$PWD/out/build/$MOD" sh "$MOD/test.sh"
sh "$HMGAPI_SDK/bin/check-scripts.sh" "$MOD/svc/start.sh" "$MOD/open.sh"

echo "== 3. Sign HMG-API & NEMESIS installable packages =="
PKG_OUT=$(HMGAPI_MOD_BUILD="$PWD/out/build/$MOD" sh "$HMGAPI_SDK/bin/mkmodpkg.sh" "$MOD" "$HMGAPI_KEY")
echo "$PKG_OUT"
SIGNED_ZIP=$(echo "$PKG_OUT" | tail -1)
sh "$HMGAPI_SDK/bin/mknemesismod.sh" "$SIGNED_ZIP"

mkdir -p dist
HMGAPI_ZIP="dist/${MOD}-v${VER}.hmgapi.zip"
NEM_PKG="dist/${MOD}-v${VER}-nemesis-package.zip"
NEM_USB="dist/${MOD}-v${VER}-nemesis-usb.zip"

cp "$SIGNED_ZIP" "$HMGAPI_ZIP"
cp "out/dist/${MOD}-${VER}-nemesis-package.zip" "$NEM_PKG"
cp "out/dist/${MOD}-${VER}-nemesis.zip" "$NEM_USB"

python3 - "$MOD" "$NEM_PKG" "$NEM_USB" <<'PY'
import os, sys, zipfile
mod, nem_pkg, nem_usb = sys.argv[1:4]
def swap_in_zip(path, member, new_bytes):
    with zipfile.ZipFile(path, "r") as z:
        items = [(i, z.read(i)) for i in z.infolist()]
    with zipfile.ZipFile(path, "w") as z:
        for info, data in items:
            z.writestr(info, new_bytes if info.filename == member else data)
open_sh = os.path.join(mod, "open.sh")
if os.path.isfile(open_sh):
    swap_in_zip(nem_pkg, "bin/open.sh", open(open_sh, "rb").read())
    swap_in_zip(nem_usb, f"packages/hmgapi.{mod}.zip", open(nem_pkg, "rb").read())
PY

(cd dist && sha256sum *.zip > SHA256SUMS)
cat dist/SHA256SUMS
