#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/bridge.sh" ensure >/dev/null 2>&1 &
(
  while true; do
    sleep 10
    [ -f "$MODDIR/disable_super" ] && continue
    alive=0
    for f in "$MODDIR"/.daemon.lock/*; do
      p=${f##*/}
      case "$p" in ''|*[!0-9]*) continue ;; esac
      c=; { read -r c < "/proc/$p/cmdline"; } 2>/dev/null
      case "$c" in *bridge.sh*daemon*) alive=1; break ;; esac
    done
    if [ $alive = 1 ]; then
      [ -e "$MODDIR/.super_fail" ] && rm -f "$MODDIR/.super_fail"
    else
      sh "$MODDIR/bridge.sh" ensure super >/dev/null 2>&1
    fi
  done
) >/dev/null 2>&1 &
(
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
  if [ ! -f "$MODDIR/.hb_default" ]; then
    case "$(settings get global gpsz_sys 2>/dev/null)" in
      ''|null) settings put global gpsz_sys 2 && : > "$MODDIR/.hb_default" ;;
      *) : > "$MODDIR/.hb_default" ;;
    esac
  fi
) >/dev/null 2>&1 &
