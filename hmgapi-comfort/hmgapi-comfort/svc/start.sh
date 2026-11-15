#!/system/bin/sh
# Non-root starter script invoked by hmgapid once sys.boot_completed=1 (or when enabled live).
i=0
while [ "$i" -lt 6 ]; do
    if /system/bin/pm path dev.lemoyne.comfort >/dev/null 2>&1; then
        /system/bin/am startservice --user 0 -n dev.lemoyne.comfort/.ComfortService
        exit 0
    fi
    i=$((i + 1))
    /system/bin/sleep 2
done
/system/bin/am startservice --user 0 -n dev.lemoyne.comfort/.ComfortService
