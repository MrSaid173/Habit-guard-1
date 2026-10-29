#!/system/bin/sh
# Read-only: is the app an active device admin, and which keyguard features are disabled right now?
#   sh /data/adb/modules/habitguard/scripts/wallcheck.sh
# disabledKeyguardFeatures: 0 = wall down, 480 (0x1e0) = biometrics off, 496 (0x1f0) = biometrics + Smart Lock off
echo "== admin log:"; tail -n 5 /data/adb/habitguard/admin.log 2>/dev/null
echo "== device_policy:"
dumpsys device_policy 2>/dev/null | grep -iE 'habitguard|disabledKeyguardFeatures' | head -n 12
echo "== trace (wall lines):"
grep -i wall /data/data/com.habitguard/files/trace.log 2>/dev/null | tail -n 20
