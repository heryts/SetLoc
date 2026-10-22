#!/system/bin/sh
MODDIR=$(cd "$(dirname "$0")" && pwd)
exec "$MODDIR/setlocd" "$@"
