#!/system/bin/sh
# SetLoc Zygisk bridge  v1.0 (setstart: Start/Stop modul = Start/Stop app)
#   ensure  : pastikan daemon sinkronisasi hidup (dipanggil service.sh & WebUI)
#   daemon  : (internal) pasang app bila belum ada, lalu salin titik dari app -> settings global gpsz_cfg
#   install : pasang app SetLoc dari apk/SetLoc.apk
#   sync    : sinkron sekali (hanya jika pengaturan di app BERUBAH sejak sinkron terakhir)
#   force   : sinkron paksa (timpa gpsz_cfg dengan isi app sekarang)
#   setstart 0|1 : ubah status Start/Stop DI APP (pref 'start'); app di-force-stop agar UI-nya memuat ulang
#   setpos <lat> <lng> : ubah titik lokasi DI APP (pref latitude/longitude); app di-force-stop
#   status  : cetak diagnosa (daemon/app/pref/gpsz_cfg)
MODDIR=$(cd "$(dirname "$0")" && pwd)
APP=com.heryts.setloc
APK="$MODDIR/apk/SetLoc.apk"
PREFNAME="${APP}_prefs.xml"
PREF=""
KEY=gpsz_cfg
LOG="$MODDIR/bridge.log"
PIDF="$MODDIR/.daemon.pid"
LASTF="$MODDIR/.last_app_cfg"     # nilai turunan dari app yang terakhir disinkronkan

log() {
  [ -f "$LOG" ] && [ "$(stat -c %s "$LOG" 2>/dev/null || echo 0)" -gt 100000 ] && : > "$LOG"
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
}


# Cari file pref app di semua lokasi yang mungkin (multi-user bisa mengalihkan
# prefs, jadi /data/data/... bisa kosong/basi). Ambil yang paling baru diubah.
find_pref() {
  [ -n "$GPSZ_PREF" ] && { echo "$GPSZ_PREF"; return; }
  [ -n "$PREFC" ] && [ -f "$PREFC" ] && { echo "$PREFC"; return; }   # cache dari daemon (hindari ls tiap putaran)
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
  OUT=$(pm install -r /data/local/tmp/gpsz_app.apk 2>&1)
  rm -f /data/local/tmp/gpsz_app.apk
  echo "$OUT"
  log "pm install: $OUT"
  case "$OUT" in *Success*) return 0 ;; *) return 1 ;; esac
}

pval() { sed -n "s/.*<$1 name=\"$2\" value=\"\([^\"]*\)\".*/\1/p" "$PREF" | head -1; }
pstr() { sed -n "s|.*<string name=\"$1\">\([^<]*\)</string>.*|\1|p" "$PREF" | head -1; }
# Versi cepat untuk derive(): isi pref dibaca sekali ke $PX, ekstraksi murni shell (tanpa spawn sed/head)
fval() { L=${PX#*"<$1 name=\"$2\" value=\""}; [ "$L" = "$PX" ] && return; echo "${L%%\"*}"; }
fstr() { L=${PX#*"<string name=\"$1\">"}; [ "$L" = "$PX" ] && return; echo "${L%%<*}"; }

# Settings.Global lewat `cmd settings` (native, ~puluhan ms) -- `settings` memulai JVM baru tiap panggilan (~0,3-0,6 dtk).
sget() { cmd settings get global "$1" 2>/dev/null || settings get global "$1" 2>/dev/null; }
sput() { cmd settings put global "$1" "$2" >/dev/null 2>&1 || settings put global "$1" "$2"; }
numok() { case "$1" in ''|*[!0-9.eE+-]*) return 1 ;; *) return 0 ;; esac; }

# Hitung nilai gpsz_cfg dari prefs app. Cetak ke stdout; return 1 bila tidak bisa.
derive() {
  PREF=$(find_pref)
  [ -n "$PREF" ] && [ -f "$PREF" ] || return 2
  PX=$(cat "$PREF" 2>/dev/null)
  START=$(fval boolean start)
  LAT=$(fval float latitude);  LNG=$(fval float longitude)
  RND=$(fval boolean random_position)
  ACC=$(fstr accuracy_settings)
  # Belum pernah menekan Start/menyimpan titik di app -> belum ada titik valid
  [ -z "$LAT" ] && LAT=40.7128
  [ -z "$LNG" ] && LNG=-74.0060
  numok "$LAT" && numok "$LNG" || return 1
  case "$ACC" in ''|*[!0-9.]*) ACC=10 ;; esac
  E=0; [ "$START" = true ] && E=1
  R=0; [ "$RND" = true ] && R=1
  # gerak: kecepatan (m/s), arah (derajat; negatif/kosong = tanpa arah), ketinggian (m)
  SPD=$(fval float speed_ms); BRG=$(fval float bearing); ALT=$(fval float altitude)
  numok "$SPD" || SPD=0
  numok "$ALT" || ALT=0
  ALTV=$(fval float altitude_var); numok "$ALTV" || ALTV=0
  RR=$(fval float random_radius); numok "$RR" || RR=8
  X=";spd=$SPD;alt=$ALT;altv=$ALTV;rr=$RR"
  numok "$BRG" && X="$X;brg=$BRG"
  echo "enabled=$E;lat=$LAT;lng=$LNG;acc=$ACC;random=$R$X"
}

# $1 = force|""   Menimpa gpsz_cfg HANYA bila isi turunan dari app berubah
# (atau force). Dengan begitu toggle manual lewat action.sh tidak tertimpa
# sampai pengaturan di app benar-benar diubah.
sync_cfg() {
  NEW=$(derive); RC=$?
  if [ $RC -ne 0 ]; then
    case $RC in 2) MSG="pref app tidak ditemukan ($PREFNAME) di /data/data, /data/user*, /data/misc/*/prefs" ;; *) MSG="lat/lng tidak valid di pref" ;; esac
    [ "$QUIET" != 1 ] && [ "$(cat "$MODDIR/.last_err" 2>/dev/null)" != "$MSG" ] && { log "sync gagal: $MSG"; echo "$MSG" > "$MODDIR/.last_err"; }
    return 1
  fi
  rm -f "$MODDIR/.last_err"
  PREV=$(cat "$LASTF" 2>/dev/null)
  if [ "$1" = force ] || [ "$NEW" != "$PREV" ]; then
    # app mengubah sesuatu: tulis langsung (put idempotent, tanpa `get` lebih dulu)
    sput $KEY "$NEW" && log "sync: $NEW" || { log "settings put GAGAL"; return 1; }
    echo "$NEW" > "$LASTF"
  else
    CUR=$(sget $KEY)
    if [ -z "$CUR" ] || [ "$CUR" = null ]; then   # gpsz_cfg terhapus: pulihkan
      sput $KEY "$NEW" && log "sync (pulihkan): $NEW" || { log "settings put GAGAL"; return 1; }
      echo "$NEW" > "$LASTF"
    fi
  fi
  return 0
}

# Samakan tombol Start/Stop di app SetLoc dengan status modul.
# App membaca pref 'start' hanya saat dibuka dan menyimpannya dari memori, jadi app dimatikan dulu
# (kalau tidak, ia menimpa perubahan ini dengan nilai lamanya), lalu file diubah DI TEMPAT (cat >) agar
# pemilik/label SELinux file tetap milik app. LASTF diperbarui supaya daemon tidak menganggapnya
# sebagai perubahan dari app dan menimpa gpsz_cfg (mis. titik manual dari 'gpsz.sh set').
set_app_start() {
  V=false; [ "$1" = 1 ] && V=true
  PREF=$(find_pref)
  if [ -z "$PREF" ] || [ ! -f "$PREF" ]; then log "setstart: pref app tidak ditemukan"; return 1; fi
  CURV=$(pval boolean start)
  [ "$CURV" = "$V" ] && return 0
  am force-stop "$APP" >/dev/null 2>&1
  TMP="$MODDIR/.pref.tmp"
  if grep -q '<boolean name="start"' "$PREF"; then
    sed "s|<boolean name=\"start\" value=\"[^\"]*\"|<boolean name=\"start\" value=\"$V\"|" "$PREF" > "$TMP"
  else
    sed "s|</map>|    <boolean name=\"start\" value=\"$V\" />\
</map>|" "$PREF" > "$TMP"
  fi
  if [ -s "$TMP" ] && grep -q "name=\"start\" value=\"$V\"" "$TMP"; then
    cat "$TMP" > "$PREF" && log "setstart: start=$V" || log "setstart: gagal menulis $PREF"
  else
    log "setstart: gagal menyusun pref"
  fi
  rm -f "$TMP"
  NEW=$(derive) && echo "$NEW" > "$LASTF"
  return 0
}

# Ubah titik (latitude/longitude) DI APP SetLoc supaya sama dengan titik manual dari WebUI/gpsz.sh set.
# Cara kerja sama seperti set_app_start: app di-force-stop dulu, file diubah di tempat (cat >), LASTF diperbarui.
set_app_pos() {
  numok "$1" && numok "$2" || { log "setpos: koordinat tidak valid"; return 1; }
  PREF=$(find_pref)
  if [ -z "$PREF" ] || [ ! -f "$PREF" ]; then log "setpos: pref app tidak ditemukan"; return 1; fi
  am force-stop "$APP" >/dev/null 2>&1
  TMP="$MODDIR/.pref.tmp"
  for KV in "latitude $1" "longitude $2" "speed_ms 0" "bearing -1"; do
    K=${KV% *}; V=${KV#* }
    if grep -q "<float name=\"$K\"" "$PREF"; then
      sed "s|<float name=\"$K\" value=\"[^\"]*\"|<float name=\"$K\" value=\"$V\"|" "$PREF" > "$TMP"
    else
      sed "s|</map>|    <float name=\"$K\" value=\"$V\" />\
</map>|" "$PREF" > "$TMP"
    fi
    if [ -s "$TMP" ] && grep -q "name=\"$K\" value=\"$V\"" "$TMP"; then
      cat "$TMP" > "$PREF" || log "setpos: gagal menulis $PREF"
    else
      log "setpos: gagal menyusun pref ($K)"
    fi
  done
  rm -f "$TMP"
  log "setpos: $1,$2"
  NEW=$(derive) && echo "$NEW" > "$LASTF"
  return 0
}

daemon_running() {
  [ -f "$PIDF" ] || return 1
  P=$(cat "$PIDF" 2>/dev/null)
  [ -n "$P" ] && [ -r "/proc/$P/cmdline" ] && tr '\0' ' ' < "/proc/$P/cmdline" | grep -q 'bridge.sh'
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

sig() { P=$(find_pref); [ -n "$P" ] && echo "$P $(stat -c '%i %y %s' "$P" 2>/dev/null)"; }

case "$1" in
  install) install_app; exit $? ;;
  sync)    sync_cfg; exit 0 ;;
  force)   sync_cfg force; exit $? ;;
  setstart) set_app_start "$2"; exit $? ;;
  setpos)  set_app_pos "$2" "$3"; exit $? ;;
  ensure)  if start_daemon; then echo "daemon=running"; else echo "daemon=stopped"; exit 1; fi; exit 0 ;;
  status)
    daemon_running && echo "daemon=running" || echo "daemon=stopped"
    app_installed && echo "app=installed" || echo "app=missing"
    PREF=$(find_pref deep)
    if [ -n "$PREF" ] && [ -f "$PREF" ]; then echo "pref=found"; echo "pref_path=$PREF"; echo "pref_mtime=$(stat -c %y "$PREF" 2>/dev/null)"
      echo "pref_start=$(pval boolean start) lat=$(pval float latitude) lng=$(pval float longitude)"
    else echo "pref=missing"; fi
    echo "derived=$(derive)"
    echo "cfg=$(sget $KEY)"
    exit 0 ;;
  daemon) ;;
  *) echo "usage: bridge.sh ensure|sync|force|setstart 0|1|setpos <lat> <lng>|status|install"; exit 1 ;;
esac

# ---- daemon ----
daemon_running && [ "$(cat "$PIDF")" != "$$" ] && exit 0   # sudah ada instance lain
echo $$ > "$PIDF"
trap 'rm -f "$PIDF"' EXIT
log "daemon start (pid $$)"
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 5
app_installed || install_app >/dev/null
PREFC=""; PREFC=$(find_pref)
sync_cfg
LAST=$(sig)
log "daemon jalan, pantau $(find_pref deep)"
# Polling cepat (0,2 dtk) selama spoof aktif supaya posisi rute tidak tertinggal; 0,5 dtk saat nonaktif.
DELAY=0.5; N=0
while true; do
  sleep $DELAY
  N=$((N+1))
  # path pref di-cache; cari ulang tiap ~5 dtk atau bila file hilang
  if [ $((N % 20)) -eq 0 ] || [ ! -f "$PREFC" ]; then PREFC=""; PREFC=$(find_pref); fi
  S=$(sig)
  if [ "$S" != "$LAST" ]; then      # juga memicu saat file baru muncul/hilang
    sleep 0.1                       # beri waktu SharedPreferences selesai menulis file
    # SharedPreferences menulis ulang file (sesaat hilang/kosong): ulangi cepat & diam-diam sebelum dianggap gagal
    OK=0
    for R in 1 2 3 4 5 6; do
      QUIET=1; sync_cfg; RC=$?; QUIET=0
      [ $RC -eq 0 ] && { OK=1; break; }
      sleep 0.05
      PREFC=""; PREFC=$(find_pref)
    done
    # LAST=$S (bukan sig baru): perubahan yang masuk saat sync tetap terdeteksi di putaran berikut
    if [ $OK = 1 ]; then LAST=$S
    elif sync_cfg; then LAST=$S     # tetap gagal: tercatat di log, dicoba lagi di putaran berikut
    fi
  fi
  case "$NEW" in *enabled=1*) DELAY=0.2 ;; *) DELAY=0.5 ;; esac
done
