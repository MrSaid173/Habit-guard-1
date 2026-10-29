#!/system/bin/sh
# Copies /data/adb/habitguard/config.json into the app's private dir
# and applies/restores the wake-gesture switches. WebUI calls this after saving.
MODDIR=${0%/*}/..
PKG=com.habitguard
SRC=/data/adb/habitguard/config.json
APPDIR=/data/data/$PKG
DST=$APPDIR/files

[ -f "$SRC" ] || exit 0

# Wake gestures (double tap, lift) are switched off when "block_gestures" is on, or when
# black-screen true sleep is on. In countdown mode true sleep does NOT touch them:
# double tap / fingerprint wake the phone and simply get the countdown.
BLOCK=0
if grep -Eq '"enabled"[[:space:]]*:[[:space:]]*true' "$SRC"; then
  if grep -Eq '"block_gestures"[[:space:]]*:[[:space:]]*true' "$SRC"; then
    BLOCK=1
  elif grep -Eq '"true_sleep"[[:space:]]*:[[:space:]]*true' "$SRC" && \
       ! grep -Eq '"mode"[[:space:]]*:[[:space:]]*"countdown"' "$SRC"; then
    BLOCK=1
  fi
fi
if [ "$BLOCK" = 1 ]; then
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
