#!/system/bin/sh
# Gen5W Suite Unified One-Tap Installer (executed by NEMESIS USB Handler as root).
#
# 1. Uninstalls legacy/competing standalone NEMESIS packages if installed.
# 2. Installs or updates HMG-API Core 1.1.1-rc.1 (BLE + VehicleHub fixes).
# 3. Verifies/enables the car's hook map (260507 + 260903) and pre-grants
#    owner permissions for comfort and EV preconditioning commands.
# 4. Installs all signed suite mods (preserving existing user settings across
#    author-key migrations) and runs a single atomic `hmgapid engine apply`.
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

# 1. Remove legacy/competing standalone NEMESIS packages cleanly (restores their stock backups first)
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
    voicehandler.vr-gates \
    adb.tcp
do
    if [ -f "/data/nemesis/apps/$pkg/NEMESIS.data" ]; then
        msg "Uninstalling conflicting NEMESIS package: $pkg"
        out=$("$NEMESIS" uninstall "$pkg" 2>&1) || out=$("$NEMESIS" uninstall --force "$pkg" 2>&1) || fail_install "Could not uninstall $pkg: $out"
        msg "$out"
    fi
done
rm -rf /data/local/coldstart-heat >/dev/null 2>&1

# 2. Install or update HMG-API Core 1.1.1-rc.1 via NEMESIS
if [ -f /data/nemesis/apps/hmgapi/NEMESIS.data ]; then
    msg "Updating HMG-API Core from packages/hmgapi.zip"
    out=$("$NEMESIS" install --update --force --accept-risk "$USB/packages/hmgapi.zip" 2>&1) || fail_install "HMG-API update failed: $out"
else
    msg "Installing HMG-API Core from packages/hmgapi.zip"
    out=$("$NEMESIS" install --accept-risk "$USB/packages/hmgapi.zip" 2>&1) || fail_install "HMG-API install failed: $out"
fi
msg "$out"

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

# 3. Activate car hook map (260507 + 260903) and pre-grant owner command switches
msg "Configuring hook map verification and owner command permissions"
"$HMGAPID" allow testing_maps on >> "$LOG" 2>&1
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

# If upgrading from AwsomeFox aa-dpi 0.2.x (or if RESET_SETTINGS is present), ensure DPI 200 + 60fps defaults
if [ -f "$USB/HMGAPI/RESET_SETTINGS" ]; then
    msg "HMGAPI/RESET_SETTINGS found on USB: resetting mod settings to suite defaults"
    rm -f /data/hmgapi/settings/wireless-carplay-aa.json \
          /data/hmgapi/settings/adb.json \
          /data/hmgapi/settings/aa-dpi.json \
          /data/hmgapi/settings/egmp-aa-ev.json \
          /data/hmgapi/settings/hmgapi-comfort.json
elif [ -f /data/hmgapi/mods/aa-dpi/mod.json ] && grep -q '"0\.2\.' /data/hmgapi/mods/aa-dpi/mod.json 2>/dev/null; then
    msg "Upgrading aa-dpi from 0.2.x -> 0.3.0: applying target DPI 200 + 60fps window"
    echo '{"dpi": 200, "real_dpi": "same", "tap_limit": true, "video_window": 6}' > /data/hmgapi/settings/aa-dpi.json
    chmod 600 /data/hmgapi/settings/aa-dpi.json
fi

# 4. Install all signed suite mods in dependency order
install_suite_mod() {
    local id=$1 zip=$2 was_off=0 out
    [ -f "$zip" ] || fail_install "Missing mod package $zip"
    if [ -d "/data/hmgapi/mods/$id" ]; then
        [ -f "/data/hmgapi/mods/$id/.disabled" ] && was_off=1
        if ! grep -q "$SUITE_UUID" "/data/hmgapi/mods/$id/META-INF/hmgapi/manifest.json" 2>/dev/null; then
            msg "Migrating $id to unified suite author key (preserving settings)"
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

install_suite_mod wireless-carplay-aa "$USB/HMGAPI/updates/01-wireless-carplay-aa.hmgapi.zip"
install_suite_mod adb                 "$USB/HMGAPI/updates/02-adb.hmgapi.zip"
install_suite_mod aa-dpi              "$USB/HMGAPI/updates/03-aa-dpi.hmgapi.zip"
install_suite_mod egmp-aa-ev          "$USB/HMGAPI/updates/04-egmp-aa-ev.hmgapi.zip"
install_suite_mod hmgapi-comfort      "$USB/HMGAPI/updates/05-hmgapi-comfort.hmgapi.zip"

if [ -f "$USB/HMGAPI/updates/06-brake-indicator-gen5w.hmgapi.zip" ]; then
    install_suite_mod brake-indicator-gen5w "$USB/HMGAPI/updates/06-brake-indicator-gen5w.hmgapi.zip"
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
