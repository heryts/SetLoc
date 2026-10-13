#!/system/bin/sh
MODDIR=${0%/*}
# bridge.sh ensure menjalankan daemon sinkronisasi (app SetLoc -> settings gpsz_cfg) bila belum hidup
sh "$MODDIR/bridge.sh" ensure >/dev/null 2>&1 &
