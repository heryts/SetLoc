#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/bridge.sh" ensure >/dev/null 2>&1 &
# Supervisor: daemon yang mati (SIGKILL/OOM/ROM) dinyalakan lagi tiap ~10 dtk; tiap restart dicatat di bridge.log.
(
  while true; do
    sleep 10
    [ -f "$MODDIR/disable_super" ] || sh "$MODDIR/bridge.sh" ensure super >/dev/null 2>&1
  done
) >/dev/null 2>&1 &
# Default system hook = heartbeat (gpsz_sys=2), dipasang sekali per flash (.hb_default); setelah itu pilihan manual dihormati.
(
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
  if [ ! -f "$MODDIR/.hb_default" ]; then
    settings put global gpsz_sys 2 && : > "$MODDIR/.hb_default"
  fi
) >/dev/null 2>&1 &
