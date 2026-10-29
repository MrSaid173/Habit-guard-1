#!/system/bin/sh
# Runs inside Magisk / KernelSU(-Next) installer. $API, $ARCH, $MODPATH are provided.
if [ "$API" -lt 30 ]; then
  abort "! Android 11+ required"
fi

ui_print "- Habit Guard v0.5.0"
mkdir -p /data/adb/habitguard

# the package manager may keep the manifest of an older build (new components such as the
# accessibility service would stay unknown): drop its parse cache, it is rebuilt on next boot
rm -rf /data/system/package_cache 2>/dev/null

# keep the user's config, but add new keys (all new options default to OFF)
if [ ! -f /data/adb/habitguard/config.json ]; then
  cp "$MODPATH/config/config.json" /data/adb/habitguard/config.json
  ui_print "- Default config created"
else
  ui_print "- Existing config kept"
  grep -q '"true_sleep"' /data/adb/habitguard/config.json || \
    sed -i 's/"enabled"/"true_sleep": false,\n    "block_gestures": false,\n    "enabled"/' /data/adb/habitguard/config.json
fi

# pick the daemon binary for this CPU
case "$ARCH$(getprop ro.product.cpu.abi)" in
  *arm64*) D=arm64 ;;
  *arm*)   D=arm ;;
  *)       D= ;;
esac
if [ -n "$D" ] && [ -f "$MODPATH/bin/hgd-$D" ]; then
  mv "$MODPATH/bin/hgd-$D" "$MODPATH/bin/hgd"
  ui_print "- Daemon installed ($D)"
else
  ui_print "! No daemon for this CPU: overlay mode only"
fi
rm -f "$MODPATH"/bin/hgd-* 2>/dev/null

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/scripts" 0 0 0755 0755
[ -f "$MODPATH/bin/hgd" ] && set_perm "$MODPATH/bin/hgd" 0 0 0755
ui_print "- Reboot to finish"
