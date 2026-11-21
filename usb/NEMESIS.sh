#!/system/bin/sh
# Gen5W Suite Unified One-Tap Installer (executed by NEMESIS USB Handler as root).
#
# 1. Uninstalls legacy/competing standalone NEMESIS packages if installed.
# 2. Installs or updates HMG-API Core 1.1.1-rc.8 (Home Wi-Fi + BLE + VehicleHub + QuickBoot).
# 3. Verifies/enables the car's hook map (260507 + 260903) and pre-grants
#    owner permissions for comfort and EV preconditioning commands.
# 4. Installs all signed suite mods (preserving existing user settings across
#    author-key migrations), seeds hmgapi-openlink into overrides.allow_cellular,
#    and runs a single atomic `hmgapid engine apply`.
# 5. Syncs mod APKs and NEMESIS Manager package listings, then prompts for one
#    head-unit restart.

umask 077
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export HMGAPI_FORCE=1

case "$0" in
    */*) USB=${0%/*} ;;
    *)   USB=. ;;
esac
cd "$USB" || exit 1
USB=$PWD

mkdir -p /data/hmgapi
LOG=/data/hmgapi/suite-install.log
echo "=== Gen5W Suite install $(date) from $USB ===" > "$LOG"

SUITE_UUID="28ad71e0-07c0-8e61-8bf3-380cc71f9280"

msg() {
    echo "$1"
    echo "$(date '+%H:%M:%S') $1" >> "$LOG"
}

toast() {
    if [ "$(getprop sys.boot_completed)" = 1 ]; then
        am broadcast -a com.hkmc.intent.action.TOAST \
            -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
            --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
    fi
}

finish_log() {
    if [ -d "$USB/HMGAPI" ]; then
        cp "$LOG" "$USB/HMGAPI/suite-install.log" >/dev/null 2>&1
    fi
    sync
}

fail_install() {
    msg "FAIL: $1"
    toast "Gen5W Suite install failed: $1"
    finish_log
    exit 1
}

NEMESIS=/system/xbin/nemesis
[ -x "$NEMESIS" ] || NEMESIS=/system/bin/nemesis
[ -x "$NEMESIS" ] || fail_install "NEMESIS CLI not found at /system/xbin/nemesis"

[ -f "$USB/packages/hmgapi.zip" ] || fail_install "Missing packages/hmgapi.zip on USB"

toast "Gen5W Suite: installing HMG-API + mods (please wait ~30s)..."

# 1. Remove legacy/competing standalone NEMESIS packages + Leafia/Evergreen precursors cleanly
uninstall_nemesis_pkg() {
    local pkg=$1 out
    [ -f "/data/nemesis/apps/$pkg/NEMESIS.data" ] || return 0
    msg "Uninstalling legacy/conflicting NEMESIS package: $pkg"
    out=$("$NEMESIS" uninstall "$pkg" 2>&1) || out=$("$NEMESIS" uninstall --force "$pkg" 2>&1) || fail_install "Could not uninstall $pkg: $out"
    msg "$out"
}

for pkg in \
    aa.dpi \
    aa.ev.model \
    coldstart.heat \
    local.hkmc.factory82 \
    hkmc.factory82 \
    wireless.projection \
    enhanced.projection \
    projection.tbt \
    carplay.session \
    wireless.carplay.aa \
    carplay.wifi.audio \
    voicehandler.vr-gates \
    adb.tcp \
    light.vibes \
    ambient.sound \
    gen5w.ambient.sound
do
    uninstall_nemesis_pkg "$pkg"
done

# Sweep /data/nemesis/apps/* for any remaining Leafia or Evergreen precursor NEMESIS packages
for app_dir in /data/nemesis/apps/*; do
    [ -f "$app_dir/NEMESIS.data" ] || continue
    pkg=${app_dir##*/}
    is_precursor=0
    case "$pkg" in
        *leafia*|*Leafia*|*LEAFIA*|*evergreen*|*Evergreen*|*EVERGREEN*)
            is_precursor=1 ;;
        hmgapi|hmgapi.*)
            ;;
        *)
            while IFS= read -r line; do
                case "$line" in
                    id=*leafia*|id=*Leafia*|id=*LEAFIA*|id=*evergreen*|id=*Evergreen*|id=*EVERGREEN*|\
                    name=*leafia*|name=*Leafia*|name=*LEAFIA*|name=*evergreen*|name=*Evergreen*|name=*EVERGREEN*|\
                    short-desc=*leafia*|short-desc=*Leafia*|short-desc=*evergreen*|short-desc=*Evergreen*)
                        is_precursor=1
                        break ;;
                esac
            done < "$app_dir/NEMESIS.data" ;;
    esac
    [ "$is_precursor" = 1 ] && uninstall_nemesis_pkg "$pkg"
done

# Clean up pre-HMG-API direct runtime installs (Leafia and Evergreen dev/early builds)
for legacy_dir in /data/leafia /data/evergreen; do
    if [ -f "$legacy_dir/uninstall.sh" ]; then
        msg "Running legacy precursor uninstaller: $legacy_dir/uninstall.sh"
        out=$(HMGAPI_FORCE=1 LEAFIA_FORCE=1 EVERGREEN_FORCE=1 sh "$legacy_dir/uninstall.sh" --purge 2>&1) \
            || out=$(HMGAPI_FORCE=1 LEAFIA_FORCE=1 EVERGREEN_FORCE=1 sh "$legacy_dir/uninstall.sh" 2>&1) \
            || fail_install "Legacy uninstall ($legacy_dir) failed: $out"
        msg "$out"
    fi
done
if [ -f /system/bin/app_process.leafia-stock ] || [ -f /system/bin/app_process.evergreen-stock ]; then
    fail_install "Legacy Leafia/Evergreen app_process wrapper still present without uninstaller; restore stock first"
fi
if [ "$(getprop sys.boot_completed)" = 1 ]; then
    for legacy_apk in leafia.app evergreen.app com.leafia.app com.evergreen.app; do
        case "$(pm path "$legacy_apk" 2>/dev/null)" in
            package:*)
                msg "Removing legacy precursor app: $legacy_apk"
                pm uninstall "$legacy_apk" >> "$LOG" 2>&1 ;;
        esac
    done
fi
rm -rf /data/local/coldstart-heat /data/leafia /data/evergreen >/dev/null 2>&1

# 2. Install or update HMG-API Core 1.1.1-rc.8 via NEMESIS
if [ -f /data/nemesis/apps/hmgapi/NEMESIS.data ]; then
    msg "Updating HMG-API Core from packages/hmgapi.zip"
    out=$("$NEMESIS" install --update --force --accept-risk "$USB/packages/hmgapi.zip" 2>&1) || fail_install "HMG-API update failed: $out"
else
    msg "Installing HMG-API Core from packages/hmgapi.zip"
    out=$("$NEMESIS" install --accept-risk "$USB/packages/hmgapi.zip" 2>&1) || fail_install "HMG-API install failed: $out"
fi
msg "$out"

# Ensure Zygote hook boot guard is cleared and hooks are enabled
mkdir -p /data/hmgapi/hook
chmod 700 /data/hmgapi/hook
echo 0 > /data/hmgapi/hook/starts
rm -f /data/hmgapi/hook/disable
: > /data/hmgapi/hook/enable

# Verify HmgApi.apk installed cleanly from 0711 /data/hmgapi/HmgApi.apk when Android is up
if [ "$(getprop sys.boot_completed)" = 1 ] && [ -f /data/hmgapi/HmgApi.apk ]; then
    case "$(pm path hmgapi.app 2>/dev/null)" in
        package:*) ;;
        *)
            msg "Ensuring HMG-API app is installed from /data/hmgapi/HmgApi.apk"
            setprop sys.boot_completed 0
            pm_out=$(pm install -r /data/hmgapi/HmgApi.apk 2>&1)
            setprop sys.boot_completed 1
            msg "$pm_out"
            case "$pm_out" in
                *Success*) rm -f /data/hmgapi/public/HmgApi.apk ;;
            esac
            ;;
    esac
fi

HMGAPID=/system/bin/hmgapid
[ -x "$HMGAPID" ] || fail_install "/system/bin/hmgapid missing after core install"

if ! "$HMGAPID" ping >/dev/null 2>&1; then
    "$HMGAPID" daemon >/dev/null 2>&1
    for i in 1 2 3 4 5; do
        "$HMGAPID" ping >/dev/null 2>&1 && break
        sleep 1
    done
fi
"$HMGAPID" ping >/dev/null 2>&1 || fail_install "hmgapid daemon did not start"

# Clear any pending trial/interrupted journal before installing mods
if [ -f /data/hmgapi/state/journal.json ]; then
    msg "Clearing prior engine journal"
    "$HMGAPID" engine keep >> "$LOG" 2>&1 || "$HMGAPID" engine recover >> "$LOG" 2>&1
fi

# Remove any legacy Leafia/Evergreen mod folders inside /data/hmgapi/mods
for mod_dir in /data/hmgapi/mods/*; do
    [ -d "$mod_dir" ] || continue
    legacy_id=${mod_dir##*/}
    case "$legacy_id" in
        *leafia*|*Leafia*|*evergreen*|*Evergreen*)
            msg "Removing legacy precursor mod: $legacy_id"
            "$HMGAPID" engine remove "$legacy_id" --from-nemesis >> "$LOG" 2>&1 ;;
    esac
done

# 3. Activate car hook map (260507 + 260903) and pre-grant owner command switches
msg "Configuring hook map verification and owner command permissions"
"$HMGAPID" allow testing_maps on >> "$LOG" 2>&1
"$HMGAPID" allow block_ambient_light off >> "$LOG" 2>&1
"$HMGAPID" engine verify-map >> "$LOG" 2>&1 || msg "note: verify-map reported warnings (testing_maps is on)"

for sw in \
    startup_notice \
    cmd.hmgapi-comfort.startup_notice \
    cmd.hmgapi-comfort.seat_heat \
    cmd.hmgapi-comfort.seat_vent \
    cmd.hmgapi-comfort.wheel_heat \
    cmd.egmp-aa-ev.conditioning
do
    "$HMGAPID" allow "$sw" on >> "$LOG" 2>&1
done

# If upgrading from AwsomeFox aa-dpi 0.2.x or an earlier build with tap_limit=true default (or RESET_SETTINGS), apply DPI 200 + tap_limit=false + 60fps
mkdir -p /data/hmgapi/settings
if [ -f "$USB/HMGAPI/RESET_SETTINGS" ]; then
    msg "HMGAPI/RESET_SETTINGS found on USB: resetting mod settings to suite defaults"
    rm -f /data/hmgapi/settings/wireless-carplay-aa.json \
          /data/hmgapi/settings/adb.json \
          /data/hmgapi/settings/aa-dpi.json \
          /data/hmgapi/settings/egmp-aa-ev.json \
          /data/hmgapi/settings/hmgapi-comfort.json \
          /data/hmgapi/settings/light-vibes.json \
          /data/hmgapi/settings/.openlink_cellular_seeded
elif [ -f /data/hmgapi/mods/aa-dpi/mod.json ] && grep -q '"0\.2\.' /data/hmgapi/mods/aa-dpi/mod.json 2>/dev/null; then
    msg "Upgrading aa-dpi from 0.2.x -> 0.3.0: applying DPI 200, tap_limit=false, 60fps window"
    echo '{"dpi": 200, "real_dpi": "same", "tap_limit": false, "video_window": 6}' > /data/hmgapi/settings/aa-dpi.json
    chmod 600 /data/hmgapi/settings/aa-dpi.json
elif [ -f /data/hmgapi/mods/aa-dpi/settings.json ] && [ -f /data/hmgapi/settings/aa-dpi.json ] \
     && grep -q '"default": true' /data/hmgapi/mods/aa-dpi/settings.json 2>/dev/null \
     && grep -q '"tap_limit": *true' /data/hmgapi/settings/aa-dpi.json 2>/dev/null; then
    msg "Migrating aa-dpi tap_limit default to off"
    echo '{"dpi": 200, "real_dpi": "same", "tap_limit": false, "video_window": 6}' > /data/hmgapi/settings/aa-dpi.json
    chmod 600 /data/hmgapi/settings/aa-dpi.json
fi

# Seed safe USB host-mode setting for official Open-HMG adb package when no user adb.json exists yet
if [ ! -f /data/hmgapi/settings/adb.json ]; then
    msg "Seeding /data/hmgapi/settings/adb.json (usb=false) so USB drives and wired CP/AA stay active"
    echo '{"usb": false}' > /data/hmgapi/settings/adb.json
    chmod 600 /data/hmgapi/settings/adb.json
fi

# 4. Install all signed suite mods in dependency order (preserving settings across author-key transitions)
VYSSE_UUID="7251ce71-a146-8a1e-958b-dbffc6af7359"
EGMP_UUID="839375d3-8aea-884a-a9e9-c79964fe5d88"

install_suite_mod() {
    local id=$1 zip=$2 expected_uuid=$3 was_off=0 out
    [ -f "$zip" ] || fail_install "Missing mod package $zip"
    if [ -d "/data/hmgapi/mods/$id" ]; then
        [ -f "/data/hmgapi/mods/$id/.disabled" ] && was_off=1
        if ! grep -q "$expected_uuid" "/data/hmgapi/mods/$id/META-HMGAPI/manifest.json" 2>/dev/null; then
            msg "Migrating $id to author key $expected_uuid (preserving settings)"
            "$HMGAPID" engine remove "$id" --from-nemesis >> "$LOG" 2>&1
        fi
    fi
    msg "Installing mod $id from ${zip##*/}"
    out=$("$HMGAPID" engine install "$zip" --force --any-car --from-nemesis 2>&1) || fail_install "Install of $id failed: $out"
    msg "$out"
    if [ "$was_off" = 1 ] && [ ! -f "$USB/HMGAPI/ENABLE_ALL" ]; then
        : > "/data/hmgapi/mods/$id/.disabled"
    else
        rm -f "/data/hmgapi/mods/$id/.disabled"
    fi
}

install_suite_mod wireless-carplay-aa "$USB/HMGAPI/updates/01-wireless-carplay-aa.hmgapi.zip" "$SUITE_UUID"
install_suite_mod adb                 "$USB/HMGAPI/updates/02-adb.hmgapi.zip"                 "$VYSSE_UUID"
install_suite_mod aa-dpi              "$USB/HMGAPI/updates/03-aa-dpi.hmgapi.zip"              "$SUITE_UUID"
install_suite_mod egmp-aa-ev          "$USB/HMGAPI/updates/04-egmp-aa-ev.hmgapi.zip"          "$EGMP_UUID"
install_suite_mod hmgapi-comfort      "$USB/HMGAPI/updates/05-hmgapi-comfort.hmgapi.zip"      "$SUITE_UUID"
install_suite_mod light-vibes         "$USB/HMGAPI/updates/06-light-vibes.hmgapi.zip"         "$SUITE_UUID"

# Seed hmgapi-openlink into hmgapi.json overrides.allow_cellular once (toggleable anytime in Settings > Home Wi-Fi > Mobile data)
if [ ! -f /data/hmgapi/settings/.openlink_cellular_seeded ]; then
    msg "Seeding hmgapi-openlink into /data/hmgapi/settings/hmgapi.json overrides.allow_cellular"
    HJ=/data/hmgapi/settings/hmgapi.json
    hj=""
    if [ -f "$HJ" ]; then
        while IFS= read -r l || [ -n "$l" ]; do
            hj="$hj$l"
        done < "$HJ"
    fi
    case "$hj" in
        *'"allow_cellular"'*)
            ac_tail=${hj#*\"allow_cellular\"*\[}
            ac_body=${ac_tail%%\]*}
            case "$ac_body" in
                *'"hmgapi-openlink"'*) ;;
                *'"'*)
                    ac_pre=${hj%%\"allow_cellular\"*}
                    ac_after=${hj#*\"allow_cellular\"}
                    ac_open=${ac_after#*\[}
                    hj="${ac_pre}\"allow_cellular\": [\"hmgapi-openlink\", ${ac_open}" ;;
                *)
                    ac_pre=${hj%%\"allow_cellular\"*}
                    ac_after=${hj#*\"allow_cellular\"}
                    ac_open=${ac_after#*\[}
                    hj="${ac_pre}\"allow_cellular\": [\"hmgapi-openlink\"${ac_open}" ;;
            esac ;;
        *'"overrides"'*)
            ov_pre=${hj%%\"overrides\"*}
            ov_after=${hj#*\"overrides\"}
            ov_open=${ov_after#*\{}
            ov_trim=${ov_open#"${ov_open%%[! ]*}"}
            case "$ov_trim" in
                \}*) hj="${ov_pre}\"overrides\": {\"allow_cellular\": [\"hmgapi-openlink\"]${ov_trim}" ;;
                *)   hj="${ov_pre}\"overrides\": {\"allow_cellular\": [\"hmgapi-openlink\"], ${ov_open}" ;;
            esac ;;
        *:*\})
            base=${hj%\}}
            hj="${base}, \"overrides\": {\"allow_cellular\": [\"hmgapi-openlink\"]}}" ;;
        *)
            hj='{"updates": false, "overrides": {"allow_cellular": ["hmgapi-openlink"]}}' ;;
    esac
    echo "$hj" > "$HJ"
    chmod 600 "$HJ"
    : > /data/hmgapi/settings/.openlink_cellular_seeded
    chmod 600 /data/hmgapi/settings/.openlink_cellular_seeded
fi

# 5. Single atomic engine apply + mod APKs + NEMESIS package listing sync
msg "Applying all enabled mods in a single transaction"
out=$("$HMGAPID" engine apply 2>&1) || fail_install "engine apply failed: $out"
msg "$out"

if [ "$(getprop sys.boot_completed)" = 1 ]; then
    msg "Syncing mod companion APKs"
    out=$("$HMGAPID" engine apps 2>&1) || msg "note: engine apps: $out"
    msg "$out"
fi

msg "Syncing NEMESIS Manager package list"
out=$("$HMGAPID" engine nemesis-sync 2>&1) || msg "note: nemesis-sync: $out"
msg "$out"

# Restart vehicle hub service so updated Hub/Comfort commands are live immediately
"$HMGAPID" service-restart hmgapi vehicle >/dev/null 2>&1

msg "=== Gen5W Suite install complete! Restart the head unit once to activate hooks. ==="
toast "Gen5W Suite installed! Restart the head unit once to activate all mods."
finish_log

if [ -f "$USB/HMGAPI/AUTO_REBOOT" ]; then
    sleep 2
    reboot
fi
exit 0
