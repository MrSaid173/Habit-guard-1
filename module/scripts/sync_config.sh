#!/system/bin/sh
# Copies /data/adb/habitguard/config.json into the app's private dir.
# WebUI (later stages) calls this after saving settings.
PKG=com.habitguard
SRC=/data/adb/habitguard/config.json
APPDIR=/data/data/$PKG
DST=$APPDIR/files

[ -f "$SRC" ] || exit 0
[ -d "$APPDIR" ] || exit 0
mkdir -p "$DST"
cp "$SRC" "$DST/config.json"
APP_UID=$(stat -c %u "$APPDIR")
chown "$APP_UID:$APP_UID" "$DST" "$DST/config.json"
chmod 700 "$DST"
chmod 600 "$DST/config.json"
restorecon -R "$DST" 2>/dev/null
exit 0
