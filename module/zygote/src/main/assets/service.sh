#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/bridge.sh" ensure >/dev/null 2>&1 &
(
  { read -r _p _ < /proc/self/stat; echo -1000 > "/proc/$_p/oom_score_adj"; } 2>/dev/null
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
    [ $alive = 1 ] || sh "$MODDIR/bridge.sh" ensure super >/dev/null 2>&1
  done
) >/dev/null 2>&1 &
(
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
  if [ ! -f "$MODDIR/.hb_default" ]; then
    sh "$MODDIR/bridge.sh" sysdefault && : > "$MODDIR/.hb_default"
  fi
) >/dev/null 2>&1 &
