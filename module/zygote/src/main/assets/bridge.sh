#!/system/bin/sh
MODDIR=$(cd "$(dirname "$0")" && pwd)
APP=com.heryts.setloc
APK="$MODDIR/apk/SetLoc.apk"
PREFNAME="${APP}_prefs.xml"
KEY=gpsz_cfg
LOG="$MODDIR/bridge.log"
LOCKD="$MODDIR/.daemon.lock"
BEATKEY=gpsz_beat
PKGDIR="$MODDIR/packages"
LASTF="$MODDIR/.last_app_cfg"

log() {
  [ -f "$LOG" ] && [ "$(stat -c %s "$LOG" 2>/dev/null || echo 0)" -gt 100000 ] && : > "$LOG"
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
}

keepalive() {
  ( pm grant $APP android.permission.WRITE_SECURE_SETTINGS
    cmd deviceidle whitelist +$APP
    cmd appops set $APP RUN_ANY_IN_BACKGROUND allow
    cmd appops set $APP RUN_IN_BACKGROUND allow
    cmd appops set $APP SYSTEM_ALERT_WINDOW allow
    cmd activity set-standby-bucket $APP active ) >/dev/null 2>&1 &
}

poke_check() {
  PX=$(cat "$PREFC" 2>/dev/null)
  T0=$(fval long route_t0); TK=$(fval long route_tick); NN=$(fval int route_n)
  numok "$T0" && numok "$TK" && numok "$NN" || return 0
  [ "$TK" -gt 0 ] || return 0
  read UP _ < /proc/uptime
  EL=$(( (${UP%.*}${UP#*.} - T0) * 10 ))
  [ $EL -ge 0 ] && [ $EL -lt $(( (NN - 1) * TK )) ] || return 0
  [ $(( $(date +%s) - $(stat -c %Y "$PREFC" 2>/dev/null || echo 0) )) -ge 4 ] || return 0
  pidof $APP >/dev/null 2>&1 || return 0
  am startservice -n $APP/.route.RouteService -a $APP.route.POKE >/dev/null 2>&1 &
}

find_pref() {
  [ -n "$PREFC" ] && [ -f "$PREFC" ] && { echo "$PREFC"; return; }
  CAND=$(ls -t /data/data/$APP/shared_prefs/$PREFNAME \
                /data/user/*/$APP/shared_prefs/$PREFNAME \
                /data/user_de/*/$APP/shared_prefs/$PREFNAME \
                /data/misc/*/prefs/$APP/$PREFNAME \
                /data/misc/*/*/prefs/$APP/$PREFNAME 2>/dev/null | head -1)
  if [ -z "$CAND" ] && [ "$1" = deep ]; then
    CAND=$(find /data/misc /data/user /data/user_de -maxdepth 6 -name "$PREFNAME" 2>/dev/null | head -1)
  fi
  echo "$CAND"
}

app_installed() { pm path "$APP" 2>/dev/null | grep -q '^package:'; }

install_app() {
  [ -f "$APK" ] || { echo "APK tidak ditemukan: $APK"; return 1; }
  cp "$APK" /data/local/tmp/gpsz_app.apk && chmod 644 /data/local/tmp/gpsz_app.apk
  OUT=$(pm install -r -d /data/local/tmp/gpsz_app.apk 2>&1)
  rm -f /data/local/tmp/gpsz_app.apk
  echo "$OUT"
  log "pm install: $OUT"
  case "$OUT" in
    *Success*) pm grant $APP android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; return 0 ;;
    *) return 1 ;;
  esac
}

pval() { sed -n "s/.*<$1 name=\"$2\" value=\"\([^\"]*\)\".*/\1/p" "$PREF" | head -1; }
fval() { L=${PX#*"<$1 name=\"$2\" value=\""}; [ "$L" = "$PX" ] && return; echo "${L%%\"*}"; }
fstr() { L=${PX#*"<string name=\"$1\">"}; [ "$L" = "$PX" ] && return; echo "${L%%<*}"; }

sget() { cmd settings get global "$1" 2>/dev/null || settings get global "$1" 2>/dev/null; }
sput() { cmd settings put global "$1" "$2" >/dev/null 2>&1 || settings put global "$1" "$2"; }
numok() { case "$1" in ''|*[!0-9.eE+-]*) return 1 ;; *) return 0 ;; esac; }

derive() {
  if [ -n "$PREFC" ] && [ -f "$PREFC" ]; then PREF=$PREFC; else PREF=$(find_pref); fi
  [ -n "$PREF" ] && [ -f "$PREF" ] || return 2
  PX=$(cat "$PREF" 2>/dev/null)
  START=$(fval boolean start)
  LAT=$(fval float latitude);  LNG=$(fval float longitude)
  RND=$(fval boolean random_position); [ -z "$RND" ] && RND=true
  ACC=$(fstr accuracy_settings)
  [ -z "$LAT" ] && LAT=40.7128
  [ -z "$LNG" ] && LNG=-74.0060
  numok "$LAT" && numok "$LNG" || return 1
  case "$ACC" in ''|*[!0-9.]*) ACC=10 ;; esac
  E=0; [ "$START" = true ] && E=1
  R=0; [ "$RND" = true ] && R=1
  [ "$(fval boolean route_active)" = true ] && R=0
  SPD=$(fval float speed_ms); BRG=$(fval float bearing); ALT=$(fval float altitude)
  numok "$SPD" || SPD=0
  numok "$ALT" || ALT=0
  ALTV=$(fval float altitude_var); numok "$ALTV" || ALTV=0
  RR=$(fval float random_radius); numok "$RR" || RR=8
  PL=""
  if [ "$(fval boolean route_play)" = true ]; then
    [ -z "$CURBC" ] && CURBC=$(sget boot_count)
    T0=$(fval long route_t0); TK=$(fval long route_tick); NN=$(fval int route_n)
    HE=$(fval long route_hold_end); BC=$(fval int route_bc); [ -z "$HE" ] && HE=0
    TF="${PREF%/shared_prefs/*}/files/route_timeline.txt"
    if [ -f "$TF" ] && [ "$BC" = "$CURBC" ] && numok "$T0" && numok "$TK" && numok "$NN" && numok "$HE" \
       && [ "$TK" -gt 0 ] && [ "$NN" -gt 0 ]; then
      read UP _ < /proc/uptime
      EL=$(( (${UP%.*}${UP#*.} - T0) * 10 ))
      if [ $EL -lt 0 ]; then IDX=1; else IDX=$(( EL / TK + 1 )); fi
      ENDED=0
      if [ $IDX -ge $NN ]; then IDX=$NN; ENDED=1; fi
      set -- $(sed -n "${IDX}{p;q}" "$TF")
      if numok "$1" && numok "$2" && numok "$3" && numok "$4" && numok "$5"; then
        LAT=$1; LNG=$2; SPD=$3; BRG=$4; ALT=$5
        E=1; PL=";pl=1"
        if [ $ENDED = 1 ]; then
          R=0; [ "$RND" = true ] && R=1
          if [ "$HE" -gt 0 ] && [ $EL -ge $(( (NN - 1) * TK + HE )) ]; then E=0; PL=""; fi
        else
          R=0
        fi
      fi
    fi
  fi
  X=";spd=$SPD;alt=$ALT;altv=$ALTV;rr=$RR"
  numok "$BRG" && X="$X;brg=$BRG"
  echo "enabled=$E;lat=$LAT;lng=$LNG;acc=$ACC;random=$R$X$PL"
}

sync_cfg() {
  NEW=$(derive); RC=$?
  if [ $RC -ne 0 ]; then
    case $RC in 2) MSG="pref app tidak ditemukan ($PREFNAME) di /data/data, /data/user*, /data/misc/*/prefs" ;; *) MSG="lat/lng tidak valid di pref" ;; esac
    [ "$QUIET" != 1 ] && [ "$(cat "$MODDIR/.last_err" 2>/dev/null)" != "$MSG" ] && { log "sync gagal: $MSG"; echo "$MSG" > "$MODDIR/.last_err"; }
    return 1
  fi
  [ -e "$MODDIR/.last_err" ] && rm -f "$MODDIR/.last_err"
  PREV=; { read -r PREV < "$LASTF"; } 2>/dev/null
  if [ "$1" != force ] && [ "$NEW" = "$PREV" ]; then
    CUR=$(sget $KEY)
    [ -n "$CUR" ] && [ "$CUR" != null ] && return 0
  fi
  sput $KEY "$NEW" || { log "settings put GAGAL"; return 1; }
  echo "$NEW" > "$LASTF"
  return 0
}

pref_set() {
  TMP="$MODDIR/.pref.tmp"
  if grep -q "<$1 name=\"$2\"" "$PREF"; then
    sed "s|<$1 name=\"$2\" value=\"[^\"]*\"|<$1 name=\"$2\" value=\"$3\"|" "$PREF" > "$TMP"
  else
    sed "s|</map>|    <$1 name=\"$2\" value=\"$3\" /></map>|" "$PREF" > "$TMP"
  fi
  if [ -s "$TMP" ] && grep -q "name=\"$2\" value=\"$3\"" "$TMP"; then
    cat "$TMP" > "$PREF" || log "pref: gagal menulis $2"
  else
    log "pref: gagal menyusun $2"
  fi
  rm -f "$TMP"
}

app_pref() {
  PREF=$(find_pref)
  [ -n "$PREF" ] && [ -f "$PREF" ] || { log "$1: pref app tidak ditemukan"; return 1; }
}

remember() { NEW=$(derive) && echo "$NEW" > "$LASTF"; return 0; }

set_app_start() {
  V=false; [ "$1" = 1 ] && V=true
  app_pref setstart || return 1
  [ "$(pval boolean start)" = "$V" ] && return 0
  am force-stop "$APP" >/dev/null 2>&1
  pref_set boolean start "$V"
  remember
}

set_app_pos() {
  numok "$1" && numok "$2" || { log "setpos: koordinat tidak valid"; return 1; }
  app_pref setpos || return 1
  am force-stop "$APP" >/dev/null 2>&1
  pref_set float latitude "$1"; pref_set float longitude "$2"
  pref_set float speed_ms 0; pref_set float bearing -1
  remember
}

is_playing() { case "$NEW" in *";pl=1") return 0 ;; *) return 1 ;; esac; }

is_daemon_pid() { [ -r "/proc/$1/cmdline" ] && tr '\0' ' ' < "/proc/$1/cmdline" | grep -q 'bridge.sh daemon'; }

daemon_running() {
  for F in "$LOCKD"/*; do
    Q=${F##*/}
    case "$Q" in ''|*[!0-9]*) continue ;; esac
    is_daemon_pid "$Q" && return 0
  done
  return 1
}

lock_reg() {
  for _ in 1 2 3; do
    mkdir -p "$LOCKD" 2>/dev/null
    echo "$MYK" > "$LOCKD/.t$$" 2>/dev/null && mv -f "$LOCKD/.t$$" "$LOCKD/$$" 2>/dev/null && [ -f "$LOCKD/$$" ] && return 0
  done
  return 1
}

lock_clean() {
  for F in "$LOCKD"/*; do
    Q=${F##*/}
    case "$Q" in ''|*[!0-9]*|"$$") continue ;; esac
    is_daemon_pid "$Q" || rm -f "$F" 2>/dev/null
  done
}

lock_fast() {
  [ -f "$LOCKD/$$" ] || lock_reg
  for F in "$LOCKD"/*; do
    Q=${F##*/}
    case "$Q" in ''|*[!0-9]*|"$$") continue ;; esac
    [ -d "/proc/$Q" ] || continue
    K=; read K < "$F" 2>/dev/null
    case "$K" in ''|*[!0-9]*) continue ;; esac
    if [ "$K" -lt "$MYK" ] || { [ "$K" -eq "$MYK" ] && [ "$Q" -lt "$$" ]; }; then
      is_daemon_pid "$Q" && { WIN=$Q; return 1; }
    fi
  done
  return 0
}

acquire_lock() {
  read UP _ < /proc/uptime
  MYK=${UP%.*}${UP#*.}
  lock_reg || return 1
  sleep 0.3
  lock_clean
  lock_fast && return 0
  log "daemon: kalah arbitrasi dari pid $WIN saat start (pid $$), keluar"
  rm -f "$LOCKD/$$" 2>/dev/null
  return 1
}

start_daemon() {
  daemon_running && return 0
  if command -v setsid >/dev/null 2>&1; then
    setsid nohup sh "$MODDIR/bridge.sh" daemon >/dev/null 2>&1 &
  else
    nohup sh "$MODDIR/bridge.sh" daemon >/dev/null 2>&1 &
  fi
  sleep 1
  daemon_running
}

sig() { [ -n "$PREFC" ] && echo "$PREFC $(stat -c '%i %y %s' "$PREFC" 2>/dev/null)"; }

case "$1" in
  install) install_app; exit $? ;;
  sync)    sync_cfg; exit 0 ;;
  force)   sync_cfg force; exit $? ;;
  setstart) set_app_start "$2"; exit $? ;;
  setpos)  set_app_pos "$2" "$3"; exit $? ;;
  ensure)
    daemon_running || { [ -z "$(ls "$LOCKD" 2>/dev/null)" ] && sleep 2; daemon_running; } \
      && { echo "daemon=running"; rm -f "$MODDIR/.super_fail"; exit 0; }
    if [ "$2" = super ]; then
      STALEP=$(ls "$LOCKD" 2>/dev/null | grep '^[0-9][0-9]*$' | tr '\n' ' ')
      STALEP=${STALEP% }
      if [ -n "$STALEP" ]; then log "supervisor: daemon (pid $STALEP) mati TANPA baris exit -> SIGKILL/OOM; restart"
      else log "supervisor: daemon tidak berjalan (alasan: lihat baris 'daemon exit' di atas); restart"; fi
    fi
    if start_daemon; then
      echo "daemon=running"
      [ "$2" = super ] && { log "supervisor: restart OK"; rm -f "$MODDIR/.super_fail"; }
      exit 0
    fi
    echo "daemon=stopped"
    [ "$2" = super ] && [ ! -f "$MODDIR/.super_fail" ] && { log "supervisor: restart GAGAL (dicoba lagi tiap siklus)"; : > "$MODDIR/.super_fail"; }
    exit 1 ;;
  status)
    daemon_running && echo "daemon=running" || echo "daemon=stopped"
    app_installed && echo "app=installed" || echo "app=missing"
    dumpsys package $APP 2>/dev/null | grep -q 'WRITE_SECURE_SETTINGS: granted=true' && echo "secure_settings=granted" || echo "secure_settings=missing"
    PREF=$(find_pref deep)
    if [ -n "$PREF" ] && [ -f "$PREF" ]; then echo "pref=found"; echo "pref_path=$PREF"; echo "pref_mtime=$(stat -c %y "$PREF" 2>/dev/null)"
      echo "pref_start=$(pval boolean start) lat=$(pval float latitude) lng=$(pval float longitude)"
    else echo "pref=missing"; fi
    echo "derived=$(derive)"
    echo "cfg=$(sget $KEY)"
    exit 0 ;;
  daemon) ;;
  *) echo "usage: bridge.sh ensure [super]|sync|force|setstart 0|1|setpos <lat> <lng>|status|install"; exit 1 ;;
esac

yield_lock() { lock_fast || { log "daemon: kalah arbitrasi dari pid $WIN, keluar"; exit 0; }; }

acquire_lock || exit 0
on_exit() {
  RC=$1
  rm -f "$LOCKD/$$" "$LOCKD/.t$$" 2>/dev/null
  rmdir "$LOCKD" 2>/dev/null
  log "daemon exit (pid $$, rc $RC)"
}
on_sig() { log "daemon menerima sinyal $1 (pid $$)"; exit $2; }
trap 'on_sig TERM 143' TERM
trap 'on_sig HUP 129' HUP
trap 'on_sig INT 130' INT
trap 'on_exit $?' EXIT
log "daemon start (pid $$)"
until [ "$(getprop sys.boot_completed)" = 1 ]; do
  yield_lock
  sleep 1
done
for _ in 1 2 3 4 5; do
  sleep 1
  yield_lock
done
SUM=$(md5sum "$APK" 2>/dev/null | cut -d' ' -f1)
if ! app_installed || [ "$SUM" != "$(cat "$MODDIR/.apk_sum" 2>/dev/null)" ]; then
  log "pasang APK modul (sum $SUM)"
  install_app >/dev/null && echo "$SUM" > "$MODDIR/.apk_sum"
fi
PREFC=$(find_pref)
CURBC=$(sget boot_count)
sync_cfg
app_installed && keepalive
LAST=$(sig)
DELAY=0.5; N=0
while true; do
  sleep $DELAY
  N=$((N+1))
  yield_lock
  if [ $((N % 100)) -eq 0 ]; then lock_clean; fi
  if [ $((N % 20)) -eq 0 ] || [ ! -f "$PREFC" ]; then PREFC=""; PREFC=$(find_pref); fi
  S=$(sig)
  is_playing && PLAYING=1 || PLAYING=0
  if [ "$S" != "$LAST" ] || [ $PLAYING = 1 ]; then
    [ "$S" != "$LAST" ] && sleep 0.1
    OK=0
    for R in 1 2 3 4 5 6; do
      QUIET=1; sync_cfg; RC=$?; QUIET=0
      [ $RC -eq 0 ] && { OK=1; break; }
      sleep 0.05
      PREFC=""; PREFC=$(find_pref)
    done
    [ $OK = 1 ] && LAST=$S || { sync_cfg && LAST=$S; }
  fi
  is_playing && PLAYING=1 || PLAYING=0
  if [ $PLAYING = 1 ]; then
    read UP _ < /proc/uptime
    if [ "${UP%.*}" != "$LASTBEAT" ]; then
      sput $BEATKEY "${UP%.*}" && LASTBEAT=${UP%.*}
    fi
  fi
  [ $PLAYING = 1 ] && [ $((N % 24)) -eq 0 ] && poke_check
  case "$NEW" in
    *enabled=1*)
      is_playing && DELAY=0.25 || DELAY=0.2
      [ "$WASON" != 1 ] && { WASON=1; keepalive; } ;;
    *) DELAY=0.5; WASON=0 ;;
  esac
done
