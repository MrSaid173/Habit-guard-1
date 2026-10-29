#!/system/bin/sh
# usage: a11y.sh on | off
# Switches the Habit Guard cover (accessibility service, see app/.../Cover.kt) on or off.
# Only our own entry in enabled_accessibility_services is touched.
PKG=com.habitguard
SVC="$PKG/$PKG.CoverService"

LIST=$(settings get secure enabled_accessibility_services 2>/dev/null)
[ "$LIST" = null ] && LIST=""
HAS=0
case ":$LIST:" in *":$PKG/"*) HAS=1 ;; esac

case "$1" in
on)
  if [ "$HAS" = 0 ]; then
    if [ -z "$LIST" ]; then NEW="$SVC"; else NEW="$LIST:$SVC"; fi
    settings put secure enabled_accessibility_services "$NEW"
  fi
  [ "$(settings get secure accessibility_enabled 2>/dev/null)" = 1 ] || settings put secure accessibility_enabled 1
  ;;
off)
  [ "$HAS" = 1 ] || exit 0
  NEW=$(echo "$LIST" | tr ':' '\n' | grep -v "^$PKG/" | tr '\n' ':' | sed 's/:$//')
  if [ -z "$NEW" ]; then
    settings delete secure enabled_accessibility_services
    settings put secure accessibility_enabled 0
  else
    settings put secure enabled_accessibility_services "$NEW"
  fi
  ;;
esac
exit 0
