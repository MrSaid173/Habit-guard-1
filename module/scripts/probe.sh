#!/system/bin/sh
# Habit Guard probe: what can THIS phone do, and what actually wakes it.
#   su -c sh probe.sh              capability report -> /sdcard/habitguard_probe.txt
#   su -c sh probe.sh watch [sec]  live: prints every wake-up with its reason (default 120 s).
#                                  Lift the phone, touch the fingerprint sensor, look at it, send
#                                  yourself a notification... and see which source wakes it.
MODDIR=${0%/*}/..
OUT=${HG_PROBE_OUT:-/sdcard/habitguard_probe.txt}
KEYS="double_tap_to_wake wake_gesture_enabled doze_pulse_on_pick_up doze_pulse_on_double_tap doze_tap_gesture doze_pick_up_gesture doze_wake_screen_gesture doze_wake_display_gesture doze_quick_pickup_gesture pick_up_gesture_wakeup_mode wake_when_plugged_or_unplugged ambient_tilt_to_wake ambient_touch_to_wake"

if [ "$1" = watch ]; then
  SEC=${2:-120}
  echo "Watching wake-ups for $SEC s (Ctrl+C to stop)..."
  if command -v timeout >/dev/null 2>&1; then
    timeout "$SEC" logcat -b all -T 1 -v time -e 'Waking up from|Going to sleep'
  else
    logcat -b all -T 1 -v time -e 'Waking up from|Going to sleep'
  fi
  exit 0
fi

{
echo "===== DEVICE ====="
for p in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk ro.miui.ui.version.name ro.mi.os.version.name ro.build.display.id; do
  echo "$p=$(getprop $p)"
done

echo; echo "===== CAPABILITIES ====="
[ -e /dev/uinput ] || [ -e /dev/input/uinput ] && echo "uinput: yes (power-key delay + touch muting possible)" || echo "uinput: NO (daemon cannot work)"
echo "appops TURN_SCREEN_ON: $(sh "$MODDIR/scripts/appops.sh" probe) (yes = apps can be stopped from lighting the screen)"
echo "accessibility cover: $(settings get secure enabled_accessibility_services 2>/dev/null | grep -c habitguard) (1 = enabled)"

echo; echo "===== INPUT DEVICES ====="
getevent -lp 2>/dev/null | awk '
  /^add device/ { if (n != "") print n "  power=" pw "  touch=" ts; n = $0; pw = 0; ts = 0 }
  /name:/       { n = n " " $0 }
  /KEY_POWER/   { pw = 1 }
  /ABS_MT_POSITION_X|BTN_TOUCH/ { ts = 1 }
  END           { if (n != "") print n "  power=" pw "  touch=" ts }'

echo; echo "===== WAKE SETTINGS PRESENT ON THIS ROM (namespace key = value) ====="
for ns in secure system global; do
  for k in $KEYS; do
    v=$(settings get $ns $k 2>/dev/null)
    case "$v" in ""|null) continue ;; esac
    echo "$ns $k = $v"
  done
done

echo; echo "===== WAKE REASONS SEEN IN THE LOG (count / reason) ====="
logcat -b all -d 2>/dev/null | grep -E "Waking up from" | grep -oE "reason=[A-Za-z_.:0-9]+" | sort | uniq -c | sort -rn | head -20
echo; echo "--- last wake-ups ---"
logcat -b all -d 2>/dev/null | grep -E "Waking up from" | tail -15
echo
echo "Tip: 'sh probe.sh watch 120' shows wake-ups live, so you can match every source to its reason."
} > "$OUT" 2>&1
echo "Saved: $OUT"
