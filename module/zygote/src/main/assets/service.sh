#!/system/bin/sh
MODDIR=${0%/*}
chmod 755 "$MODDIR/setlocd" "$MODDIR/gpsz.sh" "$MODDIR/bridge.sh" "$MODDIR/action.sh" 2>/dev/null
sh "$MODDIR/bridge.sh" ensure >/dev/null 2>&1 &
(
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
  if [ ! -f "$MODDIR/.hb_default" ]; then
    sh "$MODDIR/bridge.sh" sysdefault && : > "$MODDIR/.hb_default"
  fi
) >/dev/null 2>&1 &
