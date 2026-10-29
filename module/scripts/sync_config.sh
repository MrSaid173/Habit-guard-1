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

# The cover (accessibility service) hides the lock screen on wake-ups. It is useful whenever the
# guard shows something on wake-ups: countdown mode, or black mode without true sleep.
COVER=0
if grep -Eq '"enabled"[[:space:]]*:[[:space:]]*true' "$SRC" && \
   ! grep -Eq '"cover"[[:space:]]*:[[:space:]]*false' "$SRC"; then
  if grep -Eq '"mode"[[:space:]]*:[[:space:]]*"countdown"' "$SRC" || \
     ! grep -Eq '"true_sleep"[[:space:]]*:[[:space:]]*true' "$SRC"; then
    COVER=1
  fi
fi
if [ "$COVER" = 1 ]; then
  sh "$MODDIR/scripts/a11y.sh" on
else
  sh "$MODDIR/scripts/a11y.sh" off
fi

[ -d "$APPDIR" ] || exit 0
mkdir -p "$DST"
cp "$SRC" "$DST/config.json"
APP_UID=$(stat -c %u "$APPDIR")
chown "$APP_UID:$APP_UID" "$DST" "$DST/config.json"
chmod 700 "$DST"
chmod 600 "$DST/config.json"
restorecon -R "$DST" 2>/dev/null

# diagnostics: `touch /data/adb/habitguard/trace` + run this script => the app writes files/trace.log
if [ -f /data/adb/habitguard/trace ]; then
  : > "$DST/trace_on"
  chown "$APP_UID:$APP_UID" "$DST/trace_on"
  chmod 600 "$DST/trace_on"
  restorecon "$DST/trace_on" 2>/dev/null
else
  rm -f "$DST/trace_on"
fi
exit 0
