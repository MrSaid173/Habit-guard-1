#!/system/bin/sh
# usage: gestures.sh off | restore
# Turns wake gestures (double tap, lift/pick-up) off and remembers the old values.
SAVE=/data/adb/habitguard/gestures.saved
KEYS="double_tap_to_wake wake_gesture_enabled doze_pulse_on_pick_up doze_pulse_on_double_tap doze_tap_gesture doze_pick_up_gesture doze_wake_screen_gesture doze_wake_display_gesture doze_quick_pickup_gesture pick_up_gesture_wakeup_mode wake_when_plugged_or_unplugged ambient_tilt_to_wake ambient_touch_to_wake"

case "$1" in
off)
  [ -f "$SAVE" ] || : > "$SAVE"
  for ns in secure system global; do
    # one `settings list` per namespace instead of 13 `settings get` (each one starts a JVM, ~0.3 s)
    ALL=$(settings list $ns 2>/dev/null)
    for k in $KEYS; do
      v=$(printf '%s\n' "$ALL" | sed -n "s/^$k=//p" | head -n 1)
      case "$v" in ""|null) continue ;; esac
      grep -q "^$ns $k " "$SAVE" || echo "$ns $k $v" >> "$SAVE"
      [ "$v" != "0" ] && settings put $ns $k 0
    done
  done
  ;;
restore)
  [ -f "$SAVE" ] || exit 0
  while read -r ns k v; do
    [ -n "$v" ] && settings put "$ns" "$k" "$v"
  done < "$SAVE"
  rm -f "$SAVE"
  ;;
esac
exit 0
