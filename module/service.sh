#!/system/bin/sh
MODDIR=${0%/*}
PKG=com.habitguard

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
# wait until the user storage is unlocked (first unlock after reboot)
until [ -d /sdcard/Android ]; do sleep 3; done
sleep 5

# manual kill-switch: touch /data/adb/habitguard/disable
if [ -f /data/adb/habitguard/disable ]; then
  sh "$MODDIR/scripts/appops.sh" off 2>/dev/null
  exit 0
fi

(cmd appops set $PKG SYSTEM_ALERT_WINDOW allow || appops set $PKG SYSTEM_ALERT_WINDOW allow) 2>/dev/null
pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
cmd deviceidle whitelist +$PKG 2>/dev/null
# MIUI/HyperOS: "show on lock screen" (10020) and "start from background" (10021)
if [ -n "$(getprop ro.miui.ui.version.name)$(getprop ro.mi.os.version.name)" ]; then
  for op in 10020 10021; do
    (cmd appops set $PKG $op allow || appops set $PKG $op allow) 2>/dev/null
  done
fi
cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null

sh "$MODDIR/scripts/sync_config.sh"
am start-foreground-service -n $PKG/.GuardService 2>/dev/null || am startservice -n $PKG/.GuardService

# root daemon for "true sleep" (idle until enabled in config).
# No pkill-then-start (that is a race of its own): hgd takes an flock on hgd.lock at start and a
# second copy exits at once. To restart it by hand: pkill -x hgd; then run this script again.
if [ -x "$MODDIR/bin/hgd" ]; then
  nohup "$MODDIR/bin/hgd" > /data/adb/habitguard/hgd.log 2>&1 &
fi
