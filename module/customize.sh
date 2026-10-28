#!/system/bin/sh
# Runs inside Magisk / KernelSU(-Next) installer. $API, $MODPATH are provided.
if [ "$API" -lt 30 ]; then
  abort "! Android 11+ required"
fi

ui_print "- Habit Guard v0.1.0"
mkdir -p /data/adb/habitguard
if [ ! -f /data/adb/habitguard/config.json ]; then
  cp "$MODPATH/config/config.json" /data/adb/habitguard/config.json
  ui_print "- Default config created"
else
  ui_print "- Existing config kept"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/scripts" 0 0 0755 0755
ui_print "- Reboot to finish (APK is installed as a systemless system app)"
