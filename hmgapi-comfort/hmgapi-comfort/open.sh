#!/system/bin/sh
# Open the Comfort & Startup app from NEMESIS Manager, installing its APK first if needed.
MOD=${ROOT:-}/data/hmgapi/mods/hmgapi-comfort
say() { echo "Comfort: $1"; }
[ "$(getprop sys.boot_completed)" = 1 ] || { say "Android isn't up yet: try again in a minute"; exit 1; }
case "$(pm path dev.lemoyne.comfort 2>/dev/null)" in
    package:*) ;;
    *)
        A=$MOD/app/Comfort.apk
        [ -f "$A" ] || { say "the app isn't on this unit: install Comfort & Startup again"; exit 1; }
        setprop sys.boot_completed 0
        r=$(pm install -r "$A" 2>&1)
        setprop sys.boot_completed 1
        case "$r" in
            *Success*) say "installed the app" ;;
            *) say "the app didn't install: $r"; exit 1 ;;
        esac ;;
esac
am start -n dev.lemoyne.comfort/.MainActivity >/dev/null 2>&1 || { say "couldn't open the app"; exit 1; }
