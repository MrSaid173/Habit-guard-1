#!/system/bin/sh
# Copies /data/adb/habitguard/config.json into the app's private dir
# and applies/restores the wake-gesture switches. WebUI calls this after saving.
MODDIR=${0%/*}/..
PKG=com.habitguard
D=/data/adb/habitguard
SRC=$D/config.json
APPDIR=/data/data/$PKG
DST=$APPDIR/files

mkdir -p "$D" 2>/dev/null
# One sync at a time (WebUI save + boot, two quick saves...): the gesture / accessibility / appops
# switches below are read-modify-write. A later run waits (up to 60 s) instead of being skipped, and
# reads the config only after it got the lock, so the last save always wins.
if command -v flock >/dev/null 2>&1 && ( : >> "$D/sync.lock" ) 2>/dev/null; then
  exec 9>>"$D/sync.lock"
  i=0
  until flock -n -x 9 2>/dev/null; do
    i=$((i + 1))
    [ "$i" -gt 300 ] && exit 0
    sleep 0.2 2>/dev/null || sleep 1
  done
fi

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

# The cover (accessibility service) is a black window above the lock screen. It is armed while the
# screen is off, so a wake-up nobody can veto (fingerprint, face, notification) is already hidden
# behind it while the phone is being put back to sleep; in countdown mode it carries the digits.
COVER=0
if grep -Eq '"enabled"[[:space:]]*:[[:space:]]*true' "$SRC" && \
   ! grep -Eq '"cover"[[:space:]]*:[[:space:]]*false' "$SRC"; then
  COVER=1
fi
if [ "$COVER" = 1 ]; then
  sh "$MODDIR/scripts/a11y.sh" on
else
  sh "$MODDIR/scripts/a11y.sh" off
fi

# Apps that light the screen (notifications, ...): AppOps TURN_SCREEN_ON = ignore for third-party
# apps, only in black true-sleep mode and only if asked for. Runs in the background (many apps).
APPS=0
if grep -Eq '"enabled"[[:space:]]*:[[:space:]]*true' "$SRC" && \
   grep -Eq '"true_sleep"[[:space:]]*:[[:space:]]*true' "$SRC" && \
   ! grep -Eq '"mode"[[:space:]]*:[[:space:]]*"countdown"' "$SRC" && \
   grep -Eq '"block_app_wake"[[:space:]]*:[[:space:]]*true' "$SRC" && \
   [ ! -f /data/adb/habitguard/disable ]; then
  APPS=1
fi
# the desired state is recorded here, under the lock (so the order of saves is kept); the slow part
# runs in the background, WITHOUT our lock (9>&-), and converges to whatever was recorded last
if [ "$APPS" = 1 ]; then
  sh "$MODDIR/scripts/appops.sh" want on
else
  sh "$MODDIR/scripts/appops.sh" want off
fi
( sh "$MODDIR/scripts/appops.sh" apply > "$D/appops.log" 2>&1 9>&- & )

[ -d "$APPDIR" ] || exit 0
mkdir -p "$DST"
APP_UID=$(stat -c %u "$APPDIR")
# copy to a temp name and rename: the app reads config.json at every wake-up and must never see a
# half-written file (it would parse as "disabled" and let that wake-up through)
cp "$SRC" "$DST/config.json.tmp"
chown "$APP_UID:$APP_UID" "$DST" "$DST/config.json.tmp"
chmod 700 "$DST"
chmod 600 "$DST/config.json.tmp"
restorecon -R "$DST" 2>/dev/null
mv -f "$DST/config.json.tmp" "$DST/config.json"

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
