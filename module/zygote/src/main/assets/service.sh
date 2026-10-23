#!/system/bin/sh
MODDIR=${0%/*}
chmod 755 "$MODDIR/setlocd" 2>/dev/null
"$MODDIR/setlocd" service >/dev/null 2>&1 &
