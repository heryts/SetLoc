#!/system/bin/sh
MODDIR=$(cd "$(dirname "$0")" && pwd)
APP=com.heryts.setloc
APK="$MODDIR/apk/SetLoc.apk"
PREFNAME="${APP}_prefs.xml"

LOG="$MODDIR/bridge.log"
LOCKD="$MODDIR/.daemon.lock"
KV="81 120 55 109 75 50 118 84 110 76 57 112 "
enc() {
  kr=$KV
  vals=
  for cv in $(printf %s "$1" | od -An -v -tu1); do
    k=${kr%% *}
    kr=${kr#* }
    [ -n "$kr" ] || kr=$KV
    vals="$vals $(( (cv + k) % 256 ))"
  done
  [ -n "$vals" ] && printf '%02x' $vals
}
dec() {
  rest="$1"
  case "$rest" in ''|null) return ;; *[!0-9a-fA-F]*) return ;; esac
  [ $((${#rest} % 2)) -eq 0 ] || return
  kr=$KV
  esc=
  while [ -n "$rest" ]; do
    pair=${rest%"${rest#??}"}
    rest=${rest#??}
    k=${kr%% *}
    kr=${kr#* }
    [ -n "$kr" ] || kr=$KV
    v=$(( (0x$pair - k + 256) % 256 ))
    esc="$esc\\$(( (v>>6)&7 ))$(( (v>>3)&7 ))$(( v&7 ))"
  done
  printf "$esc"
}
KEY=691c6a
SYSKEY=da59e5
BEATKEY=eccaa8
TRKKEY=7ff7c7
NETKEY=5770df
BTKEY=c60482
TRK_W=48
TRK_LEAD=24
LASTF="$MODDIR/.last_app_cfg"
CFGS=/data/system/gpsz_config
CFGF=/data/adb/gpsz_config
STAMP="$MODDIR/.sync_stamp"

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
  [ $(( $(date +%s) - $(stat -c %Y "$PREFC" 2>/dev/null || echo 0) )) -ge 15 ] || return 0
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
csget() { V=$(sget "$1"); [ -n "$V" ] && [ "$V" != null ] && dec "$V"; }
csput() { sput "$1" "$(enc "$2")"; }
numok() {
  _nm=$1; _nx=
  case "$_nm" in *[eE]*) _nx=${_nm#*[eE]}; _nm=${_nm%%[eE]*}; [ -n "$_nx" ] || return 1 ;; esac
  case "$_nm" in -*|+*) _nm=${_nm#?} ;; esac
  case "$_nm" in ''|*[!0-9.]*|*.*.*) return 1 ;; esac
  case "$_nm" in *[0-9]*) ;; *) return 1 ;; esac
  case "${_nx#[-+]}" in *[!0-9]*) return 1 ;; esac
  [ -z "$_nx" ] || [ -n "${_nx#[-+]}" ] || return 1
  return 0
}
inrange() {
  case "$1" in ''|*[!0-9.-]*|*.*.*|?*-*) return 1 ;; esac
  v=${1#-}; i=${v%%.*}; [ -n "$i" ] || i=0
  f=; case "$v" in *.*) f=${v#*.} ;; esac
  [ "$i" -lt "$2" ] 2>/dev/null && return 0
  [ "$i" -eq "$2" ] 2>/dev/null || return 1
  case "$f" in *[!0]*) return 1 ;; esac
  return 0
}

compose() {
  X=";spd=$SPD;alt=$ALT;altv=$ALTV;rr=$RR"
  numok "$BRG" && X="$X;brg=$BRG"
  OUT="enabled=$E;lat=$LAT;lng=$LNG;acc=$ACC;random=$R$X$PL"
}

route_apply() {
  E=$B_E; R=$B_R; LAT=$B_LAT; LNG=$B_LNG; SPD=$B_SPD; BRG=$B_BRG; ALT=$B_ALT; PL=""; ENDED=0
  read UP _ < /proc/uptime
  EL=$(( (${UP%.*}${UP#*.} - RT_T0) * 10 ))
  if [ $EL -lt 0 ]; then IDX=1; else IDX=$(( EL / RT_TK + 1 )); fi
  if [ $IDX -ge $RT_NN ]; then IDX=$RT_NN; ENDED=1; fi
  RIDX=$IDX
  if [ "$RT_TLW" -gt 0 ]; then
    TLINE=$(dd if="$RT_TF" bs=$RT_TLW skip=$((IDX - 1)) count=1 2>/dev/null)
  else
    TLINE=$(sed -n "${IDX}{p;q}" "$RT_TF")
  fi
  set -- $TLINE
  if numok "$1" && numok "$2" && numok "$3" && numok "$4" && numok "$5"; then
    LAT=$1; LNG=$2; SPD=$3; BRG=$4; ALT=$5
    E=1; PL=";pl=1"
    if [ $ENDED = 1 ]; then
      R=0; [ "$RT_RND" = true ] && R=1
      if [ "$RT_HE" -gt 0 ] && [ $EL -ge $(( (RT_NN - 1) * RT_TK + RT_HE )) ]; then E=0; PL=""; fi
    else
      R=0
    fi
    if [ "$TRK" = 1 ] && [ "$PL" = ";pl=1" ]; then
      PL=";tk=1;pl=1"; LAT=$T_LAT; LNG=$T_LNG; SPD=0; BRG=; ALT=$T_ALT
    fi
  fi
}

route_due() {
  read UP _ < /proc/uptime
  EL=$(( (${UP%.*}${UP#*.} - RT_T0) * 10 ))
  if [ $EL -lt 0 ]; then DI=1; else DI=$(( EL / RT_TK + 1 )); fi
  if [ $DI -ge $RT_NN ]; then
    [ "$RIDX" != "$RT_NN" ] && return 0
    [ "$RT_HE" -gt 0 ] && [ $EL -ge $(( (RT_NN - 1) * RT_TK + RT_HE )) ] && return 0
    return 1
  fi
  [ "$TRK" != 1 ] && [ "$DI" != "$RIDX" ]
}

trk_win() {
  S=$1; C=$TRK_W
  [ $S -ge $RT_NN ] && { S=$RT_NN; C=1; }
  [ $((S + C - 1)) -gt $RT_NN ] && C=$((RT_NN - S + 1))
  D=$(dd if="$RT_TF" bs=$RT_TLW skip=$((S - 1)) count=$C 2>/dev/null | tr -s ' \n' ',|')
  [ -n "$D" ] || return 1
  sput $TRKKEY "t=$((RT_T0 * 10 + (S - 1) * RT_TK));k=$RT_TK;a=$((PC * 10));d=$D" || return 1
  [ "$WKEY" != "$RT_T0:$RT_NN:$RT_TF" ] && log "trk: jendela pertama t0=$RT_T0 n=$RT_NN mulai tick $S"
  WKEY="$RT_T0:$RT_NN:$RT_TF"; WS=$S; WE=$((S + C - 1)); HOLD_AT=$PC
  return 0
}

trk_pump() {
  read UP _ < /proc/uptime
  PC=${UP%.*}${UP#*.}
  EL=$(( (PC - RT_T0) * 10 ))
  if [ $EL -lt 0 ]; then IX=1; else IX=$(( EL / RT_TK + 1 )); fi
  [ $IX -gt $RT_NN ] && IX=$RT_NN
  TRK_NAP=5
  [ $((RT_NN - IX)) -le 12 ] && TRK_NAP=1
  NEED=0
  if [ "$WKEY" != "$RT_T0:$RT_NN:$RT_TF" ] || [ $IX -lt ${WS:-1} ] || [ $IX -gt ${WE:-0} ]; then
    NEED=1
  elif [ $WE -lt $RT_NN ]; then
    [ $IX -ge $((WE - TRK_LEAD)) ] && NEED=1
  elif [ $IX -ge $RT_NN ] && [ $((PC - HOLD_AT)) -ge 1000 ]; then
    NEED=1
  fi
  [ $NEED = 1 ] && { trk_win $IX || return 1; }
  return 0
}

derive() {
  if [ "$FAST" = 1 ] && [ "$RT_OK" = 1 ]; then
    route_apply; compose
    return 0
  fi
  FAST=0; RT_OK=0; TRK=0
  if [ -n "$PREFC" ] && [ -f "$PREFC" ]; then PREF=$PREFC; else PREF=$(find_pref); fi
  [ -n "$PREF" ] && [ -f "$PREF" ] || return 2
  PX=$(cat "$PREF" 2>/dev/null)
  START=$(fval boolean start)
  LAT=$(fval float latitude);  LNG=$(fval float longitude)
  LD=$(fstr latitude_d); LG=$(fstr longitude_d)
  numok "$LD" && numok "$LG" && { LAT=$LD; LNG=$LG; }
  RND=$(fval boolean random_position); [ -z "$RND" ] && RND=true
  ACC=$(fstr accuracy_settings)
  [ -z "$LAT" ] && LAT=-7.7829
  [ -z "$LNG" ] && LNG=110.3671
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
  B_E=$E; B_R=$R; B_LAT=$LAT; B_LNG=$LNG; B_SPD=$SPD; B_BRG=$BRG; B_ALT=$ALT
  if [ "$(fval boolean route_play)" = true ]; then
    [ -z "$CURBC" ] && CURBC=$(sget boot_count)
    T0=$(fval long route_t0); TK=$(fval long route_tick); NN=$(fval int route_n)
    HE=$(fval long route_hold_end); BC=$(fval int route_bc); [ -z "$HE" ] && HE=0
    TW=$(fval int route_tlw); numok "$TW" || TW=0
    TF="${PREF%/shared_prefs/*}/files/route_timeline.txt"
    if [ -f "$TF" ] && [ "$BC" = "$CURBC" ] && numok "$T0" && numok "$TK" && numok "$NN" && numok "$HE" \
       && [ "$TK" -gt 0 ] && [ "$NN" -gt 0 ]; then
      RT_OK=1; RT_T0=$T0; RT_TK=$TK; RT_NN=$NN; RT_HE=$HE; RT_TLW=$TW; RT_TF=$TF; RT_RND=$RND
      if [ "$TW" -gt 0 ] && [ ! -f "$MODDIR/disable_trk" ]; then
        TRK=1
        if [ "$T0:$NN:$TW:$TF" != "$L1KEY" ]; then
          set -- $(dd if="$TF" bs=$TW count=1 2>/dev/null)
          if numok "$1" && numok "$2" && numok "$5"; then T_LAT=$1; T_LNG=$2; T_ALT=$5; L1KEY="$T0:$NN:$TW:$TF"; else TRK=0; fi
        fi
      fi
      route_apply
    fi
  fi
  compose
}

write_mirror() {
  printf '%s\n' "$MBODY" > "$1.tmp" 2>/dev/null || return 1
  chmod 600 "$1.tmp"
  if [ "$2" = sys ]; then
    chown 1000:1000 "$1.tmp" 2>/dev/null
    chcon u:object_r:system_data_file:s0 "$1.tmp" 2>/dev/null
  fi
  mv -f "$1.tmp" "$1"
}

mirror_cfg() {
  MC=${1-$NEW}
  case "$MC" in *";pl=1") return 0 ;; esac
  MBODY=$(printf 'cfg:%s\nsys:%s\nnet:%s\nbt:%s' "$MC" "$(csget "$SYSKEY")" "$(csget "$NETKEY")" "$(csget "$BTKEY")")
  write_mirror "$CFGS" sys
  write_mirror "$CFGF" root
}

mirror_stale() {
  case "$1" in *";pl=1") return 1 ;; esac
  grep -qxF "cfg:$1" "$CFGS" 2>/dev/null && return 1
  return 0
}

sync_cfg() {
  derive; RC=$?
  NEW=$OUT
  if [ $RC -ne 0 ]; then
    case $RC in 2) MSG="pref app tidak ditemukan ($PREFNAME) di /data/data, /data/user*, /data/misc/*/prefs" ;; *) MSG="lat/lng tidak valid di pref" ;; esac
    [ "$QUIET" != 1 ] && [ "$(cat "$MODDIR/.last_err" 2>/dev/null)" != "$MSG" ] && { log "sync gagal: $MSG"; echo "$MSG" > "$MODDIR/.last_err"; }
    return 1
  fi
  [ -e "$MODDIR/.last_err" ] && rm -f "$MODDIR/.last_err"
  case "$OUT" in
    *";tk=1;pl=1") trk_pump || { TRK=0; OF=$FAST; FAST=1; derive; FAST=$OF; NEW=$OUT; } ;;
  esac
  case "$NEW" in
    enabled=0*) CLS=off ;;
    *";tk=1;pl=1") CLS="trk t0=$RT_T0 n=$RT_NN" ;;
    *";pl=1") CLS="route t0=$RT_T0 n=$RT_NN" ;;
    *) CLS=static ;;
  esac
  [ "$CLS" != "$LASTCLS" ] && { LASTCLS=$CLS; log "cfg: $CLS"; }
  PREV=; { read -r PREV < "$LASTF"; } 2>/dev/null
  if [ "$1" != force ] && [ "$NEW" = "$PREV" ]; then
    CUR=$(csget $KEY)
    [ "$CUR" = "$NEW" ] && { mirror_stale "$CUR" && mirror_cfg "$CUR"; return 0; }
  fi
  csput $KEY "$NEW" || { log "settings put GAGAL"; return 1; }
  echo "$NEW" > "$LASTF"
  mirror_cfg
  return 0
}

pref_set() {
  TMP="$MODDIR/.pref.tmp"
  if grep -q "<$1 name=\"$2\"" "$PREF"; then
    sed "s|<$1 name=\"$2\" value=\"[^\"]*\"|<$1 name=\"$2\" value=\"$3\"|" "$PREF" > "$TMP"
  else
    sed "s|</map>|    <$1 name=\"$2\" value=\"$3\" />\\
</map>|" "$PREF" > "$TMP"
  fi
  if [ -s "$TMP" ] && grep -q "name=\"$2\" value=\"$3\"" "$TMP"; then
    cat "$TMP" > "$PREF" || log "pref: gagal menulis $2"
  else
    log "pref: gagal menyusun $2"
  fi
  rm -f "$TMP"
}

pref_str() {
  TMP="$MODDIR/.pref.tmp"
  if grep -q "<string name=\"$1\">" "$PREF"; then
    sed "s|<string name=\"$1\">[^<]*</string>|<string name=\"$1\">$2</string>|" "$PREF" > "$TMP"
  else
    sed "s|</map>|    <string name=\"$1\">$2</string>\\
</map>|" "$PREF" > "$TMP"
  fi
  if [ -s "$TMP" ] && grep -q "<string name=\"$1\">$2</string>" "$TMP" && grep -q '</map>' "$TMP"; then
    cat "$TMP" > "$PREF" || log "pref: gagal menulis $1"
  else
    log "pref: gagal menyusun $1"
  fi
  rm -f "$TMP"
}

pref_del() {
  TMP="$MODDIR/.pref.tmp"
  grep -q "name=\"$1\"" "$PREF" || return 0
  sed "/<string name=\"$1\">[^<]*<\/string>/d" "$PREF" > "$TMP"
  if [ -s "$TMP" ] && ! grep -q "name=\"$1\"" "$TMP"; then
    cat "$TMP" > "$PREF" || log "pref: gagal menghapus $1"
  fi
  rm -f "$TMP"
}

app_pref() {
  PREF=$(find_pref)
  [ -n "$PREF" ] && [ -f "$PREF" ] || { log "$1: pref app tidak ditemukan"; return 1; }
}

remember() { FAST=0; derive && { NEW=$OUT; echo "$NEW" > "$LASTF"; }; return 0; }

set_app_start() {
  V=false; [ "$1" = 1 ] && V=true
  app_pref setstart || return 1
  [ "$(pval boolean start)" = "$V" ] && return 0
  am force-stop "$APP" >/dev/null 2>&1
  pref_set boolean start "$V"
  remember
}

set_app_pos() {
  numok "$1" && numok "$2" && inrange "$1" 90 && inrange "$2" 180 || { log "setpos: koordinat tidak valid"; return 1; }
  if [ -n "$3" ]; then
    case "$3" in *[!0-9.]*|*.*.*|.|'') log "setpos: akurasi tidak valid"; return 1 ;; esac
  fi
  app_pref setpos || return 1
  am force-stop "$APP" >/dev/null 2>&1
  pref_del latitude_d; pref_del longitude_d
  pref_set float latitude "$1"; pref_set float longitude "$2"
  pref_set float speed_ms 0; pref_set float bearing -1
  [ -n "$3" ] && pref_str accuracy_settings "$3"
  remember
}

set_app_random() {
  case "$1" in 0) V=false ;; 1) V=true ;; *) log "setrandom: nilai tidak valid"; return 1 ;; esac
  app_pref setrandom || return 1
  [ "$(pval boolean random_position)" = "$V" ] && return 0
  am force-stop "$APP" >/dev/null 2>&1
  pref_set boolean random_position "$V"
  remember
}

pulse() {
  read UP _ < /proc/uptime
  [ "${UP%.*}" != "$LASTBEAT" ] && csput $BEATKEY "${UP%.*}" && LASTBEAT=${UP%.*}
  return 0
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

sigdue() {
  [ "$SIGNOW" = 1 ] && { SIGNOW=0; return 0; }
  [ $((N % SIGEVERY)) -eq 0 ] && return 0
  [ "$INO" = 1 ] && [ "$TRK" = 1 ] && return 0
  return 1
}

changed() {
  WHY=
  [ "$PREFC" != "$SYNCED" ] && { WHY=path; return 0; }
  [ "$PREFC" -nt "$STAMP" ] && { WHY=nt; return 0; }
  sigdue && [ "$(sig)" != "$LAST" ] && { WHY=sig; return 0; }
  return 1
}

ino_stop() { [ -n "$WPID" ] && kill "$WPID" 2>/dev/null; WPID=; WDIR=; INO=0; }

ino_up() {
  INO=0
  command -v inotifyd >/dev/null 2>&1 || return 0
  [ -n "$PREFC" ] || return 0
  WD=${PREFC%/*}
  if [ -n "$WPID" ] && [ "$WD" = "$WDIR" ] && kill -0 "$WPID" 2>/dev/null; then INO=1; return 0; fi
  ino_stop
  chmod 755 "$MODDIR/notify.sh" 2>/dev/null
  GPSZ_PID=$$ inotifyd "$MODDIR/notify.sh" "$WD:wyn" >/dev/null 2>&1 &
  WPID=$!; WDIR=$WD
  sleep 0.2
  if kill -0 "$WPID" 2>/dev/null; then INO=1; log "inotifyd aktif di $WD"
  else WPID=; WDIR=; log "inotifyd tidak jalan, pakai polling"; fi
}

wait_wake() {
  _w=$1
  while [ "$_w" -gt 0 ] && [ "$WOKE" != 1 ]; do
    sleep 1 & SPID=$!
    wait $SPID 2>/dev/null
    kill $SPID 2>/dev/null
    _w=$((_w - 1))
  done
  WOKE=0
}

nap() {
  if [ "$PLAYING" = 1 ] && [ "$RT_OK" = 1 ] && [ "$TRK" = 1 ]; then
    if [ "$INO" = 1 ]; then
      [ "$WOKE" = 1 ] && { WOKE=0; return; }
      wait_wake ${TRK_NAP:-5}
    else
      sleep 1
    fi
    return
  fi
  if [ "$PLAYING" = 1 ] && [ "$RT_OK" = 1 ]; then
    read UP _ < /proc/uptime
    MS=$(( RT_TK - ((${UP%.*}${UP#*.} - RT_T0) * 10) % RT_TK ))
    [ $MS -gt 500 ] && MS=500
    [ $MS -lt 50 ] && MS=50
    FR=$(( MS % 1000 + 1000 ))
    sleep "0.${FR#1}"
    return
  fi
  if [ "$INO" = 1 ] && [ "$PLAYING" != 1 ]; then
    [ "$WOKE" = 1 ] && { WOKE=0; return; }
    wait_wake 5
    return
  fi
  sleep $DELAY
}

case "$1" in
  install) install_app; exit $? ;;
  sync)    sync_cfg; exit 0 ;;
  mirror)  C=$(csget $KEY); [ -n "$C" ] && mirror_cfg "$C"; exit 0 ;;
  force)   sync_cfg force; exit $? ;;
  setstart) set_app_start "$2"; exit $? ;;
  setpos)  set_app_pos "$2" "$3" "$4"; exit $? ;;
  setrandom) set_app_random "$2"; exit $? ;;
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
    FAST=0; OUT=; derive; echo "derived=$OUT"
    echo "cfg=$(csget $KEY)"
    for F in "$CFGS" "$CFGF"; do
      [ -f "$F" ] && echo "mirror=$(ls -lZ "$F" 2>/dev/null)" || echo "mirror=tidak ada ($F)"
    done
    exit 0 ;;
  sysdefault)
    if [ -z "$(sget "$SYSKEY")" ] || [ "$(sget "$SYSKEY")" = null ]; then csput "$SYSKEY" 2; fi
    exit 0 ;;
  daemon) ;;
  *) echo "usage: bridge.sh ensure [super]|sync|mirror|force|setstart 0|1|setpos <lat> <lng> [acc]|setrandom 0|1|status|install|sysdefault"; exit 1 ;;
esac

yield_lock() { lock_fast || { log "daemon: kalah arbitrasi dari pid $WIN, keluar"; exit 0; }; }

acquire_lock || exit 0
on_exit() {
  RC=$1
  ino_stop
  rm -f "$LOCKD/$$" "$LOCKD/.t$$" 2>/dev/null
  rmdir "$LOCKD" 2>/dev/null
  log "daemon exit (pid $$, rc $RC)"
}
on_sig() { log "daemon menerima sinyal $1 (pid $$)"; exit $2; }
trap 'on_sig TERM 143' TERM
trap 'on_sig HUP 129' HUP
trap 'on_sig INT 130' INT
trap 'WOKE=1; SIGNOW=1' USR1
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
: > "$STAMP"
SYNCED=$PREFC
INO=0; WPID=; WDIR=; WOKE=0
ino_up
sync_cfg
app_installed && keepalive
LAST=$(sig)
DELAY=0.5; N=0; PLAYING=0; SIGEVERY=10
while true; do
  nap
  N=$((N+1))
  yield_lock
  if [ $((N % 100)) -eq 0 ]; then lock_clean; fi
  if [ $((N % 20)) -eq 0 ] || [ ! -f "$PREFC" ]; then PREFC=""; PREFC=$(find_pref); fi
  if [ "$INO" = 1 ]; then
    kill -0 "$WPID" 2>/dev/null || ino_up
  elif [ $((N % 20)) -eq 0 ]; then
    ino_up
  fi
  [ "$INO" = 1 ] && [ "${PREFC%/*}" != "$WDIR" ] && ino_up
  if changed; then
    log "pref berubah via=$WHY play=$PLAYING trk=$TRK"
    : > "$STAMP"
    SYNCED=$PREFC
    S=$(sig)
    [ "$S" != "$LAST" ] && sleep 0.1
    OK=0
    for TRY in 1 2 3 4 5 6; do
      FAST=0; QUIET=1; sync_cfg; RC=$?; QUIET=0
      [ $RC -eq 0 ] && { OK=1; break; }
      sleep 0.05
      PREFC=""; PREFC=$(find_pref)
    done
    [ $OK = 1 ] && LAST=$S || { FAST=0; sync_cfg && LAST=$S; }
  elif [ $PLAYING = 1 ] && [ "$RT_OK" = 1 ] && route_due; then
    FAST=1; QUIET=1; sync_cfg; QUIET=0; FAST=0
  elif [ $PLAYING = 1 ] && [ "$RT_OK" = 1 ] && [ "$TRK" = 1 ]; then
    trk_pump || { TRK=0; FAST=1; QUIET=1; sync_cfg; QUIET=0; FAST=0; }
  fi
  is_playing && PLAYING=1 || PLAYING=0
  if [ $PLAYING = 1 ]; then
    SIGEVERY=5
    [ "$TRK" != 1 ] && pulse
  elif [ "$INO" = 1 ]; then
    SIGEVERY=1
  else
    SIGEVERY=10
  fi
  if [ $PLAYING = 1 ]; then
    read UP _ < /proc/uptime
    PN=${UP%.*}${UP#*.}
    if [ $((PN - ${PKAT:-0})) -ge 1200 ]; then PKAT=$PN; poke_check; fi
  fi
  case "$NEW" in
    *enabled=1*)
      DELAY=0.3
      [ "$WASON" != 1 ] && { WASON=1; keepalive; } ;;
    *) DELAY=1; WASON=0 ;;
  esac
done
