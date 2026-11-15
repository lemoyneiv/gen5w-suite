#!/bin/sh
# Off-car unit test for usb/NEMESIS.sh pre-kickoff cleanup (Leafia, Evergreen,
# competing standalone NEMESIS packages) and aa-dpi tap_limit=false migration.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
HMGAPI_SDK=${HMGAPI_SDK:-$HERE/../HMG-API/out/sdk}
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
        *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;;
    esac
}

hasnt() {
    case "$3" in
        *"$2"*) echo "FAIL  $1: unexpected [$2] in [$3]"; fail=1 ;;
        *) echo "ok    $1" ;;
    esac
}

# 1. Verify usb/NEMESIS.sh passes Gen5W KitKat toolbox script check
sh "$HMGAPI_SDK/bin/check-scripts.sh" "$HERE/usb/NEMESIS.sh"
echo "ok    usb/NEMESIS.sh passes KitKat check-scripts.sh"

# 2. Simulate /data/nemesis/apps, /data/leafia, /data/evergreen, /data/hmgapi on a fake root
R="$T/root"
USB="$T/usb"
mkdir -p "$R/data/nemesis/apps" \
         "$R/data/leafia" \
         "$R/data/evergreen" \
         "$R/data/local/coldstart-heat" \
         "$R/data/hmgapi/mods/aa-dpi/META-HMGAPI" \
         "$R/data/hmgapi/mods/leafia-helper" \
         "$R/data/hmgapi/settings" \
         "$R/system/bin" \
         "$R/system/xbin" \
         "$USB/packages" \
         "$USB/HMGAPI/updates"

# Populate legacy NEMESIS packages that MUST be uninstalled
for p in wireless.carplay.aa carplay.wifi.audio leafia.custom evergreen.tweaks aa.dpi wireless.projection enhanced.projection; do
    mkdir -p "$R/data/nemesis/apps/$p"
    printf '[nemesis]\nid=%s\nname=Test %s\n' "$p" "$p" > "$R/data/nemesis/apps/$p/NEMESIS.data"
done

# A package with a neutral ID whose NEMESIS.data name mentions Evergreen
mkdir -p "$R/data/nemesis/apps/old.projection.helper"
cat > "$R/data/nemesis/apps/old.projection.helper/NEMESIS.data" <<'EOF'
[nemesis]
id=old.projection.helper
name=Evergreen Wireless Helper
EOF

# Legitimate HMG-API packages + an unrelated NEMESIS package that MUST NOT be uninstalled
for p in hmgapi hmgapi.wireless-carplay-aa unrelated.utility; do
    mkdir -p "$R/data/nemesis/apps/$p"
    printf '[nemesis]\nid=%s\nname=Keep %s\ndepends=!wireless.carplay.aa\n' "$p" "$p" > "$R/data/nemesis/apps/$p/NEMESIS.data"
done

# Pre-HMG-API direct Leafia & Evergreen runtime installs
: > "$R/system/bin/app_process.leafia-stock"
cat > "$R/data/leafia/uninstall.sh" <<EOF
#!/bin/sh
echo "leafia-uninstalled \$*" >> "$T/events.log"
rm -f "$R/system/bin/app_process.leafia-stock"
EOF

: > "$R/system/bin/app_process.evergreen-stock"
cat > "$R/data/evergreen/uninstall.sh" <<EOF
#!/bin/sh
echo "evergreen-uninstalled \$*" >> "$T/events.log"
rm -f "$R/system/bin/app_process.evergreen-stock"
EOF

# Existing aa-dpi 0.2.1 with old settings
printf '{"id": "aa-dpi", "version": "0.2.1"}\n' > "$R/data/hmgapi/mods/aa-dpi/mod.json"
printf '{"dpi": 240, "tap_limit": true}\n' > "$R/data/hmgapi/settings/aa-dpi.json"

# Stub USB files andhead-unit binaries (nemesis, hmgapid, getprop, pm, am, reboot, sync)
: > "$USB/packages/hmgapi.zip"
for m in 01-wireless-carplay-aa 02-adb 03-aa-dpi 04-egmp-aa-ev 05-hmgapi-comfort 06-light-vibes; do
    : > "$USB/HMGAPI/updates/$m.hmgapi.zip"
done

BIN="$T/bin"
mkdir -p "$BIN"

cat > "$R/system/xbin/nemesis" <<EOF
#!/bin/sh
echo "nemesis \$*" >> "$T/events.log"
if [ "\$1" = "uninstall" ]; then
    shift
    [ "\$1" = "--force" ] && shift
    rm -rf "$R/data/nemesis/apps/\$1"
fi
exit 0
EOF
chmod 755 "$R/system/xbin/nemesis"

cat > "$R/system/bin/hmgapid" <<EOF
#!/bin/sh
echo "hmgapid \$*" >> "$T/events.log"
exit 0
EOF
chmod 755 "$R/system/bin/hmgapid"

cat > "$BIN/getprop" <<'EOF'
#!/bin/sh
[ "$1" = "sys.boot_completed" ] && echo 1
EOF
cat > "$BIN/pm" <<EOF
#!/bin/sh
if [ "\$1" = "path" ]; then
    case "\$2" in
        leafia.app|evergreen.app) echo "package:/data/app/\$2-1.apk" ;;
    esac
elif [ "\$1" = "uninstall" ]; then
    echo "pm-uninstall \$2" >> "$T/events.log"
fi
EOF
cat > "$BIN/am" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod 755 "$BIN/getprop" "$BIN/pm" "$BIN/am"

# Rewrite absolute /data and /system paths in a test copy of usb/NEMESIS.sh to point into $R
sed -e "s|^export PATH=.*|export PATH=$BIN:/usr/bin:/bin|" \
    -e "s|/data/|$R/data/|g" \
    -e "s|/system/|$R/system/|g" \
    "$HERE/usb/NEMESIS.sh" > "$USB/NEMESIS.sh"
chmod 755 "$USB/NEMESIS.sh"

sh "$USB/NEMESIS.sh" >/dev/null

EVENTS=$(cat "$T/events.log")
has "uninstalled HMG Leafia Plus (wireless.carplay.aa)" "nemesis uninstall wireless.carplay.aa" "$EVENTS"
has "uninstalled carplay.wifi.audio" "nemesis uninstall carplay.wifi.audio" "$EVENTS"
has "uninstalled leafia.custom by id" "nemesis uninstall leafia.custom" "$EVENTS"
has "uninstalled evergreen.tweaks by id" "nemesis uninstall evergreen.tweaks" "$EVENTS"
has "uninstalled old.projection.helper by Evergreen name" "nemesis uninstall old.projection.helper" "$EVENTS"
has "uninstalled enhanced.projection" "nemesis uninstall enhanced.projection" "$EVENTS"
hasnt "did NOT uninstall hmgapi core" "nemesis uninstall hmgapi " "$EVENTS"
hasnt "did NOT uninstall hmgapi.wireless-carplay-aa" "nemesis uninstall hmgapi.wireless-carplay-aa" "$EVENTS"
hasnt "did NOT uninstall unrelated.utility" "nemesis uninstall unrelated.utility" "$EVENTS"
check "hmgapi.wireless-carplay-aa still present" "yes" "$([ -f "$R/data/nemesis/apps/hmgapi.wireless-carplay-aa/NEMESIS.data" ] && echo yes || echo no)"
check "unrelated.utility still present" "yes" "$([ -f "$R/data/nemesis/apps/unrelated.utility/NEMESIS.data" ] && echo yes || echo no)"

has "ran /data/leafia/uninstall.sh" "leafia-uninstalled" "$EVENTS"
has "ran /data/evergreen/uninstall.sh" "evergreen-uninstalled" "$EVENTS"
has "uninstalled leafia.app APK" "pm-uninstall leafia.app" "$EVENTS"
has "uninstalled evergreen.app APK" "pm-uninstall evergreen.app" "$EVENTS"
has "removed legacy leafia-helper mod from /data/hmgapi/mods" "hmgapid engine remove leafia-helper --from-nemesis" "$EVENTS"
has "ensured block_ambient_light is off" "hmgapid allow block_ambient_light off" "$EVENTS"
has "installed 06-light-vibes.hmgapi.zip" "06-light-vibes.hmgapi.zip" "$EVENTS"

check "migrated aa-dpi.json has tap_limit=false and dpi=200" \
    '{"dpi": 200, "real_dpi": "same", "tap_limit": false, "video_window": 6}' \
    "$(cat "$R/data/hmgapi/settings/aa-dpi.json")"

exit "$fail"
