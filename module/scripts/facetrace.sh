#!/system/bin/sh
# Diagnostic only (read-only, nothing is changed): records what the lock screen and the face unlock
# are doing while you reproduce the problem.
#   sh /data/adb/modules/habitguard/scripts/facetrace.sh [seconds]     (default 25)
# Start it, then at once do your scenario (press the power button / double tap / lift), look at the
# phone at the moments you normally would, and wait until it finishes. Send the resulting file
# /data/adb/habitguard/facetrace.txt (it holds no personal data: only state names and log lines).
OUT=/data/adb/habitguard/facetrace.txt
DUR=${1:-25}
mkdir -p /data/adb/habitguard
{
  echo "== device: $(getprop ro.product.model) android $(getprop ro.build.version.release) sdk $(getprop ro.build.version.sdk) rom: $(getprop ro.build.display.id)"
  echo "== module: $(grep '^version=' /data/adb/modules/habitguard/module.prop)"
  echo "== config: $(cat /data/adb/habitguard/config.json 2>/dev/null | tr -d '\n ')"
  echo "== face-related secure settings:"
  settings list secure 2>/dev/null | grep -iE 'face|biometric|lock_screen|keyguard' 
  echo "== biometric service (sensors, lockout):"
  dumpsys biometric 2>/dev/null | grep -iE 'face|lockout|enrolled|sensor|strength' | head -n 25
} > "$OUT" 2>&1

logcat -c 2>/dev/null
logcat -v threadtime 2>/dev/null | grep -iE 'face|biometric|keyguard|HabitGuard|hgd|wakefulness|Waking up|Going to sleep' > "$OUT.log" &
LP=$!

echo "recording for $DUR s - do your scenario NOW"
t=0
while [ "$t" -lt "$DUR" ]; do
  {
    echo "---- t=${t}s $(date +%T)"
    dumpsys power 2>/dev/null | grep -m1 -E 'mWakefulness='
    dumpsys window 2>/dev/null | grep -E 'mKeyguardShowing|isKeyguardShowing|mOccluded|KeyguardOccluded|mKeyguardGoingAway' | head -n 4
    dumpsys activity service com.android.systemui/.SystemUIService 2>/dev/null \
      | grep -iE 'shouldListenForFace|mFaceRunningState|faceAuthenticated|faceAuthEnabled|mKeyguardOccluded|mBouncer|mAuthInterruptActive|userCanSkipBouncer' | head -n 12
  } >> "$OUT" 2>&1
  t=$((t + 1))
  sleep 1
done
kill "$LP" 2>/dev/null
{ echo "== log (face/biometric/keyguard/HabitGuard):"; cat "$OUT.log"; } >> "$OUT"
rm -f "$OUT.log"
echo "done: $OUT"
