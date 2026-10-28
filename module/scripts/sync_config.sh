#!/system/bin/sh
# Copies /data/adb/habitguard/config.json into the app's private dir
# and applies/restores the wake-gesture switches. WebUI calls this after saving.
MODDIR=${0%/*}/..
PKG=com.habitguard
SRC=/data/adb/habitguard/config.json
APPDIR=/data/data/$PKG
DST=$APPDIR/files

[ -f "$SRC" ] || exit 0

# true sleep needs the gesture wake-ups off, so it implies block_gestures
if grep -Eq '"enabled"[[:space:]]*:[[:space:]]*true' "$SRC" && \
   grep -Eq '"(block_gestures|true_sleep)"[[:space:]]*:[[:space:]]*true' "$SRC"; then
  sh "$MODDIR/scripts/gestures.sh" off
else
  sh "$MODDIR/scripts/gestures.sh" restore
fi

[ -d "$APPDIR" ] || exit 0
mkdir -p "$DST"
cp "$SRC" "$DST/config.json"
APP_UID=$(stat -c %u "$APPDIR")
chown "$APP_UID:$APP_UID" "$DST" "$DST/config.json"
chmod 700 "$DST"
chmod 600 "$DST/config.json"
restorecon -R "$DST" 2>/dev/null
exit 0
