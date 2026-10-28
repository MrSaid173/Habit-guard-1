#!/system/bin/sh
sh "${0%/*}/scripts/gestures.sh" restore
pkill -x hgd 2>/dev/null
rm -rf /data/adb/habitguard
