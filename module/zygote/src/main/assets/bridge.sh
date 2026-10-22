#!/system/bin/sh
MODDIR=$(cd "$(dirname "$0")" && pwd)
[ -x "$MODDIR/setlocd" ] || chmod 755 "$MODDIR/setlocd"
exec "$MODDIR/setlocd" "$@"
