#!/system/bin/sh
sh "${0%/*}/scripts/gestures.sh" restore
sh "${0%/*}/scripts/a11y.sh" off
sh "${0%/*}/scripts/appops.sh" off
pkill -x hgd 2>/dev/null
rm -rf /data/adb/habitguard
