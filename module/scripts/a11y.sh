#!/system/bin/sh
# usage: a11y.sh on | off
# Switches the Habit Guard cover (accessibility service, see app/.../Cover.kt) on or off.
# Only our own entry in enabled_accessibility_services is touched.
#
# The list is a read-modify-write on a shared setting (the user or another app may edit it at the
# same moment), so after writing we read it back and, if our entry is not as wanted, redo the
# whole read-modify-write (up to 3 times). sync_config.sh already serialises our own callers.
PKG=com.habitguard
SVC="$PKG/$PKG.CoverService"

get_list() {
  L=$(settings get secure enabled_accessibility_services 2>/dev/null)
  [ "$L" = null ] && L=""
  echo "$L"
}

has_entry() { # $1 = list
  case ":$1:" in *":$PKG/"*) return 0 ;; esac
  return 1
}

case "$1" in
on) WANT=1 ;;
off) WANT=0 ;;
*) exit 0 ;;
esac

try=0
while [ "$try" -lt 3 ]; do
  try=$((try + 1))
  LIST=$(get_list)
  if has_entry "$LIST"; then HAS=1; else HAS=0; fi

  if [ "$WANT" = 1 ]; then
    if [ "$HAS" = 0 ]; then
      if [ -z "$LIST" ]; then NEW="$SVC"; else NEW="$LIST:$SVC"; fi
      settings put secure enabled_accessibility_services "$NEW"
    fi
    [ "$(settings get secure accessibility_enabled 2>/dev/null)" = 1 ] || settings put secure accessibility_enabled 1
  else
    [ "$HAS" = 1 ] || break
    NEW=$(echo "$LIST" | tr ':' '\n' | grep -v "^$PKG/" | tr '\n' ':' | sed 's/:$//')
    if [ -z "$NEW" ]; then
      settings delete secure enabled_accessibility_services
      settings put secure accessibility_enabled 0
    else
      settings put secure enabled_accessibility_services "$NEW"
    fi
  fi

  # read back: is our entry in the state we wanted?
  if has_entry "$(get_list)"; then NOW=1; else NOW=0; fi
  [ "$NOW" = "$WANT" ] && break
done
exit 0
