#!/system/bin/sh
# Magisk/KernelSU "Action" button: toggle spoofing on/off
MODDIR=${0%/*}
sh $MODDIR/bridge.sh ensure >/dev/null 2>&1
sh $MODDIR/gpsz.sh toggle
sh $MODDIR/gpsz.sh show
