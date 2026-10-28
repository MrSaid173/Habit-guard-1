#!/system/bin/sh
# Habit Guard diagnostics. Run as root:  su -c sh /sdcard/diag.sh
# Result: /sdcard/habitguard_diag.txt
OUT=/sdcard/habitguard_diag.txt
PKG=com.habitguard
sec() { echo; echo "===== $1 ====="; }
{
sec "DEVICE"
for p in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.display.id ro.build.version.incremental ro.miui.ui.version.name ro.mi.os.version.name ro.build.version.security_patch; do echo "$p=$(getprop $p)"; done
id; getenforce; uname -r
ksud -V 2>/dev/null; ls /data/adb/ksu 2>/dev/null | head -3; magisk -V 2>/dev/null

sec "MODULE FILES"
ls -la /data/adb/modules/habitguard /data/adb/modules/habitguard/system/app/HabitGuard 2>&1
ls /data/adb/modules/habitguard/webroot 2>&1
ls /data/adb/modules/habitguard/disable /data/adb/modules/habitguard/remove /data/adb/habitguard/disable 2>&1

sec "CONFIG (/data/adb) and APP COPY"
cat /data/adb/habitguard/config.json 2>&1
ls -laZ /data/data/$PKG/files 2>&1
cat /data/data/$PKG/files/config.json 2>&1

sec "PACKAGE"
pm list packages -f | grep habitguard
dumpsys package $PKG | grep -E "versionName|codePath|pkgFlags|flags=|stopped|enabled=|userId|SYSTEM_ALERT_WINDOW|POST_NOTIFICATIONS|granted=true" | head -30

sec "APPOPS"
cmd appops get $PKG 2>&1 | head -60

sec "SERVICE / PROCESS"
ps -A 2>/dev/null | grep -i habitguard
dumpsys activity services $PKG 2>&1 | head -40
echo "-- try to start service now:"
am start-foreground-service -n $PKG/.GuardService 2>&1
sleep 2
ps -A 2>/dev/null | grep -i habitguard

sec "BATTERY / IDLE"
dumpsys deviceidle whitelist 2>&1 | grep -i habitguard

sec "POWER / KEYGUARD STATE"
dumpsys power 2>&1 | grep -E "mWakefulness=|Display Power|mWakeLockSummary" | head -5
dumpsys window 2>&1 | grep -E "isKeyguardShowing|mShowingLockscreen|mDreamingLockscreen" | head -5

sec "INPUT DEVICES WITH KEY_POWER"
getevent -lp 2>/dev/null | awk '/^add device/{d=$0} /name:/{n=$0} /KEY_POWER/{print d; print n; print "   has KEY_POWER"}'

sec "TOUCH / WAKE GESTURE NODES"
ls /sys/class/touch/*/ 2>/dev/null | head -40
ls /proc/touchpanel 2>/dev/null | head -40
find /sys/devices /sys/class /proc -maxdepth 5 \( -iname '*double*tap*' -o -iname '*gesture*' -o -iname '*dt2w*' -o -iname '*wakeup_gesture*' \) 2>/dev/null | head -40

sec "WAKE-RELATED SETTINGS"
settings list secure 2>/dev/null | grep -iE "doze|wake|tap|lift|pick|aod|ambient|raise"
settings list system 2>/dev/null | grep -iE "doze|wake|tap|lift|pick|aod|ambient|raise|gesture"
settings list global 2>/dev/null | grep -iE "wake|doze"

sec "DAEMON LOG"
tail -40 /data/adb/habitguard/hgd.log 2>&1
ls -la /data/data/$PKG/files 2>&1

sec "MIUI OPS (10020 lock screen / 10021 bg start)"
cmd appops get $PKG 10020 2>&1
cmd appops get $PKG 10021 2>&1

sec "WAKE REASONS"
logcat -b all -d 2>/dev/null | grep -iE "Waking up from|Going to sleep|WAKE_REASON|power_screen_state|power_screen_broadcast" | tail -60

sec "LOGCAT (habitguard / crashes / avc)"
logcat -d 2>/dev/null | grep -iE "habitguard|AndroidRuntime|FATAL EXCEPTION|avc:.*habitguard|avc:.*com.habitguard" | tail -80
} > $OUT 2>&1
echo "Saved: $OUT"
