#!/system/bin/sh
# usage: admin.sh on
# Makes Habit Guard an active device admin (policy: keyguard features only, see app/.../Wall.kt),
# which is what the "hard wall" needs. Safe to run again: an already active admin stays active.
# There is no "off" here on purpose: the app removes its own admin rights (removeActiveAdmin) when it
# sees hard_wall=false; `dpm remove-active-admin` refuses admins that are not testOnly.
PKG=com.habitguard
CN=$PKG/.Admin
LOG=/data/adb/habitguard/admin.log
case "$1" in
on)
  OUT=$(dpm set-active-admin --user 0 "$CN" 2>&1 || cmd device_policy set-active-admin --user 0 "$CN" 2>&1)
  echo "$(date '+%F %T') set-active-admin: $OUT" >> "$LOG" 2>/dev/null
  ;;
esac
exit 0
