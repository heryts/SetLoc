#!/bin/sh
A="$(cd "$(dirname "$0")/../zygote/src/main/assets" && pwd)"
T=$(mktemp -d)
M="$T/mod"; mkdir -p "$M" "$T/bin" "$T/st"
PASS=0; FAIL=0

ok()   { PASS=$((PASS+1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

live_pids() {
  for f in "$M"/.daemon.lock/*; do
    q=${f##*/}; case "$q" in ''|*[!0-9]*) continue ;; esac
    tr '\0' ' ' < "/proc/$q/cmdline" 2>/dev/null | grep -q 'bridge.sh daemon' && echo "$q"
  done
}
cleanup() { for p in $(live_pids); do kill -9 "$p" 2>/dev/null; done; rm -rf "$T"; }
trap cleanup EXIT

cp "$A/bridge.sh" "$A/gpsz.sh" "$M/"
for c in getprop:'echo 1' cmd:'[ "$1" = settings ] && shift && exec settings "$@"; exit 0' pm:'exit 0' am:'exit 0' \
         dumpsys:'exit 0' logcat:'exit 0'; do
  printf '#!/bin/sh\n%s\n' "${c#*:}" > "$T/bin/${c%%:*}"
done
cat > "$T/bin/settings" <<EOS
#!/bin/sh
D="$T/st"
case "\$1" in
  get) cat "\$D/\$3" 2>/dev/null || echo null ;;
  put) printf '%s' "\$4" > "\$D/\$3" ;;
esac
EOS
cat > "$T/bin/getprop" <<EOS
#!/bin/sh
cat "$T/bootflag" 2>/dev/null || echo 1
EOS
chmod +x "$T/bin/"*
PATH="$T/bin:$PATH"; export PATH

SALT="Qx7mK2vTnL9p"
tag() {
  rest="$SALT$1"
  h=2166136261
  while [ -n "$rest" ]; do
    c=${rest%"${rest#?}"}
    rest=${rest#?}
    o=$(printf '%d' "'$c")
    h=$(( ((h ^ o) * 16777619) & 2147483647 ))
  done
  printf '%06x' $((h & 16777215))
}
enc() {
  rest="$1"
  krest="$SALT"
  out=""
  while [ -n "$rest" ]; do
    c=${rest%"${rest#?}"}; rest=${rest#?}
    [ -n "$krest" ] || krest="$SALT"
    k=${krest%"${krest#?}"}; krest=${krest#?}
    cv=$(printf '%d' "'$c")
    kv=$(printf '%d' "'$k")
    x=$(( (cv + kv) % 256 ))
    out="$out$(printf '%02x' "$x")"
  done
  printf '%s' "$out"
}
SYSTAG=da59e5; TRKTAG=7ff7c7; BEATTAG=eccaa8

echo "[sintaks]"
for f in "$A"/*.sh "$A"/customize.d/*.sh "$A/../../../../setup.sh"; do
  [ -f "$f" ] && check "sh -n $(basename "$f")" "sh -n '$f'"
done

echo "[daemon: arbitrasi]"
for i in 1 2 3 4 5 6; do ( setsid sh "$M/bridge.sh" daemon >/dev/null 2>&1 & ); done
sleep 4
check "6 daemon bersamaan -> tepat 1 hidup" '[ "$(live_pids | wc -l)" = 1 ]'
check "yang kalah mencatat arbitrasi" 'grep -q "kalah arbitrasi" "$M/bridge.log"'

echo "[daemon: SIGKILL dan supervisor]"
W=$(live_pids | head -1); kill -9 "$W"; sleep 0.3
sh "$M/bridge.sh" ensure super >/dev/null; sleep 2
check "ensure super menghidupkan lagi" '[ "$(live_pids | wc -l)" = 1 ]'
check "pid baru berbeda" '[ "$(live_pids | head -1)" != "$W" ]'
check "log menyebut SIGKILL/OOM" 'grep -q "SIGKILL/OOM" "$M/bridge.log"'

echo "[daemon: TERM]"
W=$(live_pids | head -1); kill -TERM "$W"; sleep 7
check "tidak ada daemon hidup" '[ "$(live_pids | wc -l)" = 0 ]'
check "log: sinyal TERM dan exit rc 143" 'grep -q "sinyal TERM" "$M/bridge.log" && grep -q "rc 143" "$M/bridge.log"'
check "folder kunci bersih" '[ ! -e "$M/.daemon.lock" ]'

echo "[ensure: waktu]"
S=$(date +%s); sh "$M/bridge.sh" ensure super >/dev/null; E=$(date +%s)
check "ensure saat folder kosong menghidupkan daemon (<= 5 dtk)" '[ $((E-S)) -le 5 ] && [ "$(live_pids | wc -l)" = 1 ]'
S=$(date +%s); sh "$M/bridge.sh" ensure >/dev/null; E=$(date +%s)
check "ensure saat daemon hidup cepat (<= 1 dtk)" '[ $((E-S)) -le 1 ]'

echo "[daemon: folder kunci dihapus saat daemon hidup]"
W=$(live_pids | head -1); rm -rf "$M/.daemon.lock"
S=$(date +%s); sh "$M/bridge.sh" ensure super >/dev/null; E=$(date +%s); sleep 1
check "ensure tidak menyalakan daemon kedua (pid sama, tetap 1)" '[ "$(live_pids | wc -l)" = 1 ] && [ "$(live_pids | head -1)" = "$W" ] && [ -d "$M/.daemon.lock" ]'
check "ensure menunggu paling lama ~3 dtk" '[ $((E-S)) -le 3 ]'

echo "[daemon: ensure sesudah SIGKILL tidak menunggu]"
W=$(live_pids | head -1); kill -9 "$W"; sleep 0.3
S=$(date +%s); sh "$M/bridge.sh" ensure super >/dev/null; E=$(date +%s); sleep 1
check "restart langsung (<= 2 dtk, tanpa jeda tunggu 2 dtk)" '[ $((E-S)) -le 2 ]'
check "daemon baru hidup, pid berbeda" '[ "$(live_pids | wc -l)" = 1 ] && [ "$(live_pids | head -1)" != "$W" ]'

echo "[daemon: folder kunci dihapus saat menunggu boot]"
W=$(live_pids | head -1); kill -TERM "$W"; sleep 7
echo 0 > "$T/bootflag"
( setsid sh "$M/bridge.sh" daemon >/dev/null 2>&1 & ); sleep 2
W=$(live_pids | head -1); rm -rf "$M/.daemon.lock"; sleep 2
check "lock pulih oleh daemon yang sama (<= 2 dtk)" '[ "$(live_pids | head -1)" = "$W" ] && [ -d "$M/.daemon.lock" ]'
rm -rf "$M/.daemon.lock"; sh "$M/bridge.sh" ensure >/dev/null; sleep 1
check "ensure saat boot tidak membuat daemon kedua" '[ "$(live_pids | wc -l)" = 1 ] && [ "$(live_pids | head -1)" = "$W" ]'
echo 1 > "$T/bootflag"
for p in $(live_pids); do kill -TERM "$p"; done; sleep 7

echo "[gpsz.sh]"
G="sh $M/gpsz.sh"; PK="$M/packages"
out=$($G system status 2>&1)
check "system status awal = off" 'echo "$out" | grep -q "state=off"'
$G system on >/dev/null
check "system on -> pending" '$G system status | grep -q "state=pending"'
touch -d @100 "$PK/android"
check "mtime lama -> live" '$G system status | grep -q "state=live"'
$G system heartbeat >/dev/null
check "heartbeat -> sys=2" '$G system status | grep -q "sys=2"'
$G system off >/dev/null
check "system off menghapus berkas dan menandai .sysoff" '[ ! -e "$PK/android" ] && [ -e "$PK/.sysoff" ]'
$G super off >/dev/null
check "super off membuat disable_super" '[ -f "$M/disable_super" ] && $G super | grep -q dimatikan'
$G super on >/dev/null
check "super on menghapusnya" '[ ! -f "$M/disable_super" ] && $G super | grep -q aktif'
out=$($G doctor 2>&1)
printf 'id=x\nversion=9.9\nversionCode=99\n' > "$M/module.prop"
printf '#!/bin/sh\necho "I GpsZ: system hook: LocationProviderManager.onReportLocation (kind 4)"\necho "W GpsZ: system hooks: fix generator disabled (onReportLocation=true wrap=false)"\n' > "$T/bin/logcat"
out=$($G doctor 2>&1)
check "doctor menampilkan versi modul terpasang" 'echo "$out" | grep -q "modul=9.9 code=99"'
check "doctor menampilkan baris hook sistem, termasuk generator" 'echo "$out" | grep -q "onReportLocation (kind 4)" && echo "$out" | grep -q "fix generator disabled"'
printf '#!/bin/sh\nexit 0\n' > "$T/bin/logcat"
out=$($G doctor 2>&1)
check "doctor memuat daemon, supervisor, trk, log" 'echo "$out" | grep -q "daemon=" && echo "$out" | grep -q "supervisor=" && echo "$out" | grep -q "trk=aktif" && echo "$out" | grep -q "bridge.log"'
cp "$M/bridge.sh" "$M/bridge.real"
printf '#!/bin/sh\necho "$*" >> "%s/calls"\nexec sh "%s/bridge.real" "$@"\n' "$T" "$M" > "$M/bridge.sh"
: > "$T/calls"; $G doctor >/dev/null 2>&1; $G super >/dev/null 2>&1; $G super on >/dev/null 2>&1
check "doctor dan super tidak memanggil bridge.sh sync" '! grep -qx sync "$T/calls"'
: > "$T/calls"; $G pkg add com.c.d >/dev/null 2>&1
check "perintah lain (pkg add) tetap sync dulu" 'grep -qx sync "$T/calls"'
mv -f "$M/bridge.real" "$M/bridge.sh"
check "status: secure_settings=missing bila izin tidak ada" 'sh "$M/bridge.sh" status | grep -q "secure_settings=missing"'
printf '#!/bin/sh\necho "    android.permission.WRITE_SECURE_SETTINGS: granted=true"\n' > "$T/bin/dumpsys"
check "status: secure_settings=granted bila izin ada" 'sh "$M/bridge.sh" status | grep -q "secure_settings=granted"'
$G pkg add com.a.b >/dev/null
check "pkg add membuat berkas paket" '[ -e "$PK/com.a.b" ]'

echo "[mirror: gpsz.sh set/off/net/system ikut memperbarui berkas]"
MR="$T/mr"; rm -rf "$MR"; mkdir -p "$MR/mod" "$MR/sys" "$MR/adb"
cp "$A/bridge.sh" "$A/gpsz.sh" "$MR/mod/"
sed -i "s|^CFGS=.*|CFGS=$MR/sys/gpsz_config|;s|^CFGF=.*|CFGF=$MR/adb/gpsz_config|" "$MR/mod/bridge.sh"
GM="sh $MR/mod/gpsz.sh"
$GM set -7.25 110.5 >/dev/null 2>&1
check "set menulis lat/lng baru ke kedua berkas mirror" 'grep -q "^cfg:enabled=.;lat=-7.25;lng=110.5;" "$MR/sys/gpsz_config" && cmp -s "$MR/sys/gpsz_config" "$MR/adb/gpsz_config"'
check "berkas mirror memuat baris sys/net/bt" 'grep -q "^sys:" "$MR/sys/gpsz_config" && grep -q "^net:" "$MR/sys/gpsz_config" && grep -q "^bt:" "$MR/sys/gpsz_config"'
$GM net off >/dev/null 2>&1
check "net off tercermin ke berkas (net:0)" 'grep -q "^net:0$" "$MR/sys/gpsz_config"'
$GM bt on >/dev/null 2>&1
check "bt on tercermin ke berkas (bt:1)" 'grep -q "^bt:1$" "$MR/sys/gpsz_config"'
$GM system heartbeat >/dev/null 2>&1
check "system heartbeat tercermin ke berkas (sys:2)" 'grep -q "^sys:2$" "$MR/sys/gpsz_config"'
rm -f "$MR/sys/gpsz_config" "$MR/adb/gpsz_config"
sh "$MR/mod/bridge.sh" mirror >/dev/null 2>&1
check "bridge.sh mirror membuat ulang berkas dari Settings.Global" 'grep -q "lat=-7.25;lng=110.5" "$MR/sys/gpsz_config"'
$GM random 1 >/dev/null 2>&1
check "random 1 memperbarui berkas (tidak basi)" 'grep -q ";random=1" "$MR/sys/gpsz_config"'
printf 'cfg:basi\n' > "$MR/sys/gpsz_config"
sh "$MR/mod/bridge.sh" mirror >/dev/null 2>&1
check "berkas basi ditimpa mirror" '! grep -q "^cfg:basi$" "$MR/sys/gpsz_config"'

echo "[bridge.sh: pref_set]"
sed -n '/^pref_set() {/,/^}/p' "$A/bridge.sh" > "$T/pref_set.sh"
printf '%s\n' "<map>" '    <boolean name="start" value="false" />' "</map>" > "$T/p.xml"
( PREF="$T/p.xml"; MODDIR="$T"; log() { :; }; . "$T/pref_set.sh"
  pref_set boolean start true; pref_set float latitude -7.5 )
check "pref_set mengubah nilai yang ada" 'grep -q "name=\"start\" value=\"true\"" "$T/p.xml"'
check "pref_set menambah kunci yang belum ada" 'grep -q "name=\"latitude\" value=\"-7.5\"" "$T/p.xml"'

echo "[bridge.sh: derive, timeline lebar tetap, presisi koordinat]"
P="$T/pb"; rm -rf "$P"; mkdir -p "$P/shared_prefs" "$P/files"
mkdir -p "$P/mod"; cp "$A/bridge.sh" "$P/mod/"
UPC=$(cut -d' ' -f1 /proc/uptime); UPC=${UPC%.*}${UPC#*.}
i=0; : > "$P/files/route_timeline.txt"
while [ $i -lt 6 ]; do printf '%11.6f %11.6f %7.3f %6.2f %8.2f\n' "-7.$i" "110.12345$i" 5 90 12 >> "$P/files/route_timeline.txt"; i=$((i+1)); done
check "timeline: tiap baris tepat 48 byte" '[ "$(wc -c < "$P/files/route_timeline.txt")" = 288 ]'
mkpref() {
  cat > "$P/shared_prefs/com.heryts.setloc_prefs.xml" <<EOX
<map>
    <boolean name="start" value="true" />
    <float name="latitude" value="-7.5" />
    <float name="longitude" value="110.1" />
    <string name="latitude_d">-7.1234567</string>
    <string name="longitude_d">110.7654321</string>
    <boolean name="route_play" value="true" />
    <long name="route_t0" value="$1" />
    <long name="route_tick" value="500" />
    <int name="route_n" value="6" />
    <long name="route_hold_end" value="1" />
    <int name="route_bc" value="0" />
    <int name="route_tlw" value="$2" />
</map>
EOX
}
derive_at() {
  ( MODDIR="$P/mod"; PREFC="$P/shared_prefs/com.heryts.setloc_prefs.xml"; CURBC=0; PATH="$PATH"
    sed -n '/^numok()/p;/^fval()/p;/^fstr()/p;/^sget()/p;/^compose() {/,/^}/p;/^route_apply() {/,/^}/p;/^derive() {/,/^}/p;/^find_pref() {/,/^}/p' "$A/bridge.sh" > "$P/fn.sh"
    . "$P/fn.sh"; FAST=0; derive; echo "$OUT" )
}
mkpref $((UPC)) 48
out=$(derive_at)
check "derive: tick pertama dari timeline lebar tetap (dd)" 'echo "$out" | grep -q "lat=-7.000000;lng=110.123450"'
mkpref $((UPC)) 0
out=$(derive_at)
check "derive: tlw=0 memakai sed (kompatibel timeline lama)" 'echo "$out" | grep -q "lat=-7.000000;lng=110.123450"'
mkpref $((UPC - 100)) 48
touch "$P/mod/disable_trk"
out=$(derive_at)
check "derive: 1 dtk kemudian -> baris ke-3 (IDX naik)" 'echo "$out" | grep -q "lat=-7.2" && echo "$out" | grep -q "pl=1" && ! echo "$out" | grep -q "tk=1"'
rm -f "$P/mod/disable_trk"
out=$(derive_at)
check "derive: mode trk -> cfg tetap di titik awal, spd=0, tanpa brg, tk=1;pl=1" 'echo "$out" | grep -q "lat=-7.000000;lng=110.123450" && echo "$out" | grep -q "spd=0" && ! echo "$out" | grep -q "brg=" && echo "$out" | grep -q ";tk=1;pl=1$"'
mkpref $((UPC - 100000)) 48
out=$(derive_at)
check "derive: lewat akhir + hold -> enabled=0, tanpa pl" 'echo "$out" | grep -q "^enabled=0" && ! echo "$out" | grep -q "pl=1"'
sed -i '/route_play/d' "$P/shared_prefs/com.heryts.setloc_prefs.xml"
out=$(derive_at)
check "derive: titik statis memakai string presisi penuh, bukan float" 'echo "$out" | grep -q "lat=-7.1234567;lng=110.7654321"'

echo "[bridge.sh: jendela trk]"
TL="$T/tl100.txt"; : > "$TL"; i=0
while [ $i -lt 100 ]; do printf '%11.6f %11.6f %7.3f %6.2f %8.2f\n' "-7.$i" "110.5" 5 90 12 >> "$TL"; i=$((i+1)); done
sed -n '/^numok()/p;/^sput()/p;/^TRKKEY=/p;/^TRK_W=/p;/^TRK_LEAD=/p;/^trk_win() {/,/^}/p;/^trk_pump() {/,/^}/p;/^route_due() {/,/^}/p' "$A/bridge.sh" > "$T/trk_fn.sh"
rm -f "$T/st/$TRKTAG"
out=$( . "$T/trk_fn.sh"; RT_TK=500; RT_NN=100; RT_TLW=48; RT_TF="$TL"; RT_HE=0; TRK=1
  read UP _ < /proc/uptime; PC0=${UP%.*}${UP#*.}; RT_T0=$PC0
  trk_pump; echo "rc=$? ws=$WS we=$WE"
  V=$(cat "$T/st/$TRKTAG"); echo "val=$V"
  echo SENT > "$T/st/$TRKTAG"; trk_pump; echo "again=$(cat "$T/st/$TRKTAG")"
  RT_T0=$((PC0 - 450)); WKEY="$RT_T0:$RT_NN:$RT_TF"; WS=1; WE=48; trk_pump; echo "mid=$(cat "$T/st/$TRKTAG")"
  WE=30; trk_pump; echo "near=$WS/$WE"; cat "$T/st/$TRKTAG" | cut -c1-12 | sed 's/^/nearval=/'
  RT_T0=$((PC0 - 6000)); WKEY="$RT_T0:$RT_NN:$RT_TF"; WS=53; WE=100; HOLD_AT=$((PC0 - 500)); echo SENT > "$T/st/$TRKTAG"; trk_pump; echo "hold5=$(cat "$T/st/$TRKTAG")"
  HOLD_AT=$((PC0 - 1500)); trk_pump; echo "hold15=$(cat "$T/st/$TRKTAG")"; echo "holdws=$WS/$WE" )
check "pump awal: jendela 48 tick ditulis (ws=1 we=48)" 'echo "$out" | grep -q "rc=0 ws=1 we=48"'
check "nilai trk memuat t=,k=500,a=,d= dan 48 baris" 'echo "$out" | grep "^val=" | grep -q "^val=t=[0-9]*;k=500;a=[0-9]*;d=," && [ "$(echo "$out" | grep "^val=" | tr -cd "|" | wc -c)" = 48 ]'
check "pump kedua langsung: tidak menulis ulang" 'echo "$out" | grep -q "^again=SENT"'
check "di tengah jendela (tick 10 dari 48): tidak menulis" 'echo "$out" | grep -q "^mid=SENT"'
check "mendekati akhir jendela (tick 10, we=30): jendela baru mulai di tick 10" 'echo "$out" | grep -q "^near=10/57"'
check "hold di akhir rute < 10 dtk: tidak menulis" 'echo "$out" | grep -q "^hold5=SENT"'
check "hold >= 10 dtk: refresh 1 tick (a= baru, satu baris)" 'echo "$out" | grep "^hold15=" | grep -q "k=500;a=" && [ "$(echo "$out" | grep "^hold15=" | tr -cd "|" | wc -c)" = 1 ] && echo "$out" | grep -q "^holdws=100/100"'
out=$( . "$T/trk_fn.sh"; RT_TK=500; RT_NN=100; RT_TLW=48; RT_TF="$T/tidakada"; TRK=1; RT_T0=1; trk_pump; echo "rc=$?" )
check "timeline hilang: trk_pump gagal (rc=1) agar daemon kembali ke mode lama" 'echo "$out" | grep -q "rc=1"'
check "route_due mode trk: ganti tick tidak memicu sinkron" '( . "$T/trk_fn.sh"; RT_TK=500; RT_NN=100; RT_HE=0; TRK=1; RIDX=1; RT_T0=$(( $(cut -d" " -f1 /proc/uptime | tr -d .) - 450 )); ! route_due )'
check "route_due mode lama: ganti tick memicu sinkron" '( . "$T/trk_fn.sh"; RT_TK=500; RT_NN=100; RT_HE=0; TRK=0; RIDX=1; RT_T0=$(( $(cut -d" " -f1 /proc/uptime | tr -d .) - 450 )); route_due )'
check "route_due mode trk: tiba di akhir rute memicu sinkron sekali" '( . "$T/trk_fn.sh"; RT_TK=500; RT_NN=100; RT_HE=0; TRK=1; RIDX=50; RT_T0=$(( $(cut -d" " -f1 /proc/uptime | tr -d .) - 6000 )); route_due )'
sed -n '/^nap() {/,/^}/p' "$A/bridge.sh" > "$T/nap.sh"
S=$(date +%s)
( PLAYING=1; RT_OK=1; TRK=1; INO=0; WOKE=0; TRK_NAP=5; . "$T/nap.sh"; nap )
E=$(date +%s)
check "nap trk tanpa inotify: tidur 1 dtk (bukan tiap 0,5 dtk)" '[ $((E-S)) -ge 1 ] && [ $((E-S)) -le 2 ]'
S=$(date +%s)
( PLAYING=1; RT_OK=1; TRK=1; INO=1; WOKE=1; . "$T/nap.sh"; nap )
E=$(date +%s)
check "nap trk dengan inotify: WOKE=1 -> kembali segera" '[ $((E-S)) -le 1 ]'

echo "[bridge.sh: pref_del]"
sed -n '/^pref_del() {/,/^}/p' "$A/bridge.sh" > "$T/pref_del.sh"
printf '%s\n' "<map>" '    <string name="latitude_d">-7.1</string>' '    <float name="latitude" value="-7.5" />' "</map>" > "$T/q.xml"
( PREF="$T/q.xml"; MODDIR="$T"; log() { :; }; . "$T/pref_del.sh"; pref_del latitude_d )
check "pref_del menghapus string presisi, float tetap" '! grep -q latitude_d "$T/q.xml" && grep -q "name=\"latitude\" value=\"-7.5\"" "$T/q.xml"'

echo "[inotifyd: notify.sh dan nap]"
W="$T/wk"; rm -f "$W"
( trap 'echo got > "$W"' USR1; sleep 3 & wait $!; ) & WP=$!
sleep 0.3
GPSZ_PID=$WP sh "$A/notify.sh" w /x com.heryts.setloc_prefs.xml.bak; sleep 0.3
check "notify.sh mengabaikan berkas .bak" '[ ! -e "$W" ]'
GPSZ_PID=$WP sh "$A/notify.sh" w /x com.heryts.setloc_prefs.xml; sleep 0.5
check "notify.sh mengirim USR1 untuk *_prefs.xml" '[ -e "$W" ]'
kill $WP 2>/dev/null
sed -n '/^nap() {/,/^}/p' "$A/bridge.sh" > "$T/nap.sh"
S=$(date +%s)
( PLAYING=0; RT_OK=0; INO=1; WOKE=1; DELAY=0.3; . "$T/nap.sh"; nap )
E=$(date +%s)
check "nap: WOKE=1 -> kembali segera tanpa tidur 5 dtk" '[ $((E-S)) -le 1 ]'
S=$(date +%s)
( PLAYING=1; RT_OK=0; INO=1; WOKE=0; DELAY=0.3; . "$T/nap.sh"; nap )
E=$(date +%s)
check "nap: saat play tanpa timeline tetap tidur pendek (beat tiap detik)" '[ $((E-S)) -le 1 ]'

echo "[40-install-apk.sh]"
mkdir -p "$M/apk"; echo apk > "$M/apk/SetLoc.apk"
printf '#!/bin/sh\n[ "$1" = install ] && echo Success\nexit 0\n' > "$T/bin/pm"
out=$( ui_print() { echo "$*"; }; MODPATH="$M"; . "$A/customize.d/40-install-apk.sh" )
check "install sukses: pesan terpasang dan .apk_sum dibuat" 'echo "$out" | grep -q terpasang && [ -s "$M/.apk_sum" ]'
rm -f "$M/.apk_sum"
printf '#!/bin/sh\n[ "$1" = install ] && echo "Failure [INSTALL_FAILED]"\nexit 0\n' > "$T/bin/pm"
out=$( ui_print() { echo "$*"; }; MODPATH="$M"; . "$A/customize.d/40-install-apk.sh" )
check "install gagal: pesan gagal, .apk_sum tidak dibuat" 'echo "$out" | grep -q "pm install gagal" && [ ! -e "$M/.apk_sum" ]'

echo "[40-install-apk.sh: konfigurasi lama dipertahankan]"
O="$T/old"; N="$T/new"; rm -rf "$O" "$N"; mkdir -p "$O/packages" "$N/packages"
: > "$O/packages/com.a.app"; : > "$O/packages/com.b.app"; : > "$O/packages/.all"; : > "$O/packages/android"; : > "$O/packages/.sysoff"
touch -d '2020-01-01 00:00:00' "$O/packages/android"
: > "$O/.hb_default"; : > "$O/disable_super"; : > "$O/disable_trk"; echo OLDSUM > "$O/.apk_sum"; echo log > "$O/bridge.log"
out=$( ui_print() { echo "$*"; }; MODPATH="$N"; OLD_MOD="$O"; . "$A/customize.d/40-install-apk.sh" )
check "daftar app, .all, android, .sysoff ikut" '[ -e "$N/packages/com.a.app" ] && [ -e "$N/packages/com.b.app" ] && [ -e "$N/packages/.all" ] && [ -e "$N/packages/android" ] && [ -e "$N/packages/.sysoff" ]'
check "mtime android dipertahankan (status live tidak berubah jadi pending)" '[ "$(stat -c %Y "$N/packages/android")" = "$(stat -c %Y "$O/packages/android")" ]'
check ".hb_default, disable_super, disable_trk ikut" '[ -e "$N/.hb_default" ] && [ -e "$N/disable_super" ] && [ -e "$N/disable_trk" ]'
check ".apk_sum lama dan bridge.log tidak ikut" '[ ! -e "$N/.apk_sum" ] && [ ! -e "$N/bridge.log" ]'
check "folder modul lama tidak diubah" '[ -e "$O/packages/com.a.app" ] && [ "$(cat "$O/.apk_sum")" = OLDSUM ]'
check "pesan dipertahankan tercetak" 'echo "$out" | grep -q "Konfigurasi lama dipertahankan"'
N2="$T/new2"; mkdir -p "$N2"
out=$( ui_print() { echo "$*"; }; MODPATH="$N2"; OLD_MOD="$T/tidakada"; . "$A/customize.d/40-install-apk.sh" )
check "tanpa modul lama: tidak ada yang dibuat" '[ ! -e "$N2/packages" ] && ! echo "$out" | grep -q "Konfigurasi lama"'
out=$( ui_print() { echo "$*"; }; MODPATH="$O"; OLD_MOD="$O"; . "$A/customize.d/40-install-apk.sh" )
check "modul lama sama dengan tujuan: dilewati tanpa error" '[ -e "$O/packages/com.a.app" ] && ! echo "$out" | grep -q "gagal menyalin"'

echo "[service.sh: mode system hook tidak ditimpa]"
svc() {
  S="$T/svc"; rm -rf "$S"; mkdir -p "$S"; cp "$A/service.sh" "$A/bridge.sh" "$S/"
  [ -n "$2" ] && : > "$S/.hb_default"
  if [ "$1" = unset ]; then rm -f "$T/st/$SYSTAG"; else enc "$1" > "$T/st/$SYSTAG"; fi
  ( setsid sh -c 'echo $$ > "$1/pgid"; exec sh "$1/service.sh"' sh "$S" >/dev/null 2>&1 & )
  sleep 1.5
  kill -9 -- "-$(cat "$S/pgid")" 2>/dev/null
}
sysval() { cat "$T/st/$SYSTAG" 2>/dev/null | ( read -r h; [ -n "$h" ] || { echo ''; exit; }
  krest="$SALT"; out=""; rest="$h"
  while [ -n "$rest" ]; do
    pair=$(printf '%s' "$rest" | cut -c1-2); rest=$(printf '%s' "$rest" | cut -c3-)
    [ -n "$krest" ] || krest="$SALT"; k=${krest%"${krest#?}"}; krest=${krest#?}
    kv=$(printf '%d' "'$k"); xv=$(( 0x$pair )); v=$(( (xv - kv + 256) % 256 ))
    out="$out$(printf "\\$(printf '%03o' "$v")")"
  done
  printf '%s' "$out" )
}
svc 1
check "reinstall: mode penuh (1) tidak ditimpa jadi heartbeat, penanda dibuat" '[ "$(sysval)" = 1 ] && [ -e "$S/.hb_default" ]'
svc 0
check "reinstall: mode mati (0) tidak ditimpa, penanda dibuat" '[ "$(sysval)" = 0 ] && [ -e "$S/.hb_default" ]'
svc unset
check "pemasangan pertama: default heartbeat (2) diterapkan, penanda dibuat" '[ "$(sysval)" = 2 ] && [ -e "$S/.hb_default" ]'
svc 1 marker
check "penanda sudah ada: tidak menyentuh pengaturan" '[ "$(sysval)" = 1 ]'

echo "[kunci Settings.Global dan beat terenkripsi]"
for p in KEY:cfg SYSKEY:sys BEATKEY:beat TRKKEY:trk NETKEY:net BTKEY:bt; do
  for f in bridge.sh gpsz.sh; do
    check "$f ${p%%:*} cocok dengan tag(${p#*:})" '[ "$(sed -n "s/^${p%%:*}=//p" "$A/$f")" = "$(tag "${p#*:}")" ]'
  done
done
sed -n '/^KV=/p;/^enc() {/,/^}/p;/^dec() {/,/^}/p;/^BEATKEY=/p;/^sput() {/p;/^csput() {/p;/^pulse() {/,/^}/p' "$A/bridge.sh" > "$T/beat_fn.sh"
rm -f "$T/st/$BEATTAG"
( . "$T/beat_fn.sh"; LASTBEAT=; pulse; echo "$LASTBEAT" > "$T/beat_up"; enc 12345 > "$T/wire5" )
WIRE_PLAIN=$(cat "$T/beat_up"); WIRE_HEX=$(cat "$T/st/$BEATTAG" 2>/dev/null); WIRE_PLAIN5=12345; WIRE_HEX5=$(cat "$T/wire5")
export WIRE_PLAIN WIRE_HEX WIRE_PLAIN5 WIRE_HEX5
check "beat tidak pernah ditulis lewat sput polos" '! grep -nE "\\bsput[[:space:]]+\"?[$]BEATKEY" "$A/bridge.sh" "$A/gpsz.sh"'
check "pulse: beat ditulis terenkripsi, bukan teks polos" '[ -n "$WIRE_PLAIN" ] && [ "$WIRE_HEX" = "$(enc "$WIRE_PLAIN")" ] && [ "$WIRE_HEX" != "$WIRE_PLAIN" ]'
check "pulse: detik yang sama tidak ditulis ulang" '( . "$T/beat_fn.sh"; LASTBEAT=$WIRE_PLAIN; echo SENT > "$T/st/$BEATTAG"; pulse; [ "$(cat "$T/st/$BEATTAG")" = SENT ] )'
check "enc bridge.sh identik dengan enc referensi (uptime 5 digit)" '[ "$WIRE_HEX5" = "$(enc 12345)" ]'

echo "[hook: Config.checkBeat (beat daemon)]"
TD="$(cd "$(dirname "$0")" && pwd)"
if command -v java >/dev/null 2>&1 && { command -v javac >/dev/null 2>&1 || java --list-modules 2>/dev/null | grep -q '^jdk.compiler@'; }; then
  check "checkBeat + refresh: freeze, off, resume, jeda poll, titik tunggal, snapshot, flag sistem, onReady sekali" 'sh "$TD/beat/run.sh" >"$T/beat.log" 2>&1 || { cat "$T/beat.log"; false; }'
else
  bad "tes Java dilewati: javac/java tidak ada"
fi

echo "[unsafe: SunUnsafe milik sendiri menggantikan SunUnsafeWrapper]"
if command -v java >/dev/null 2>&1 && { command -v javac >/dev/null 2>&1 || java --list-modules 2>/dev/null | grep -q '^jdk.compiler@'; }; then
  check "SunUnsafe: API identik v1.0.2, nol warning javac, bytecode valid, memori/CAS/fence jalan" 'sh "$TD/unsafe/run.sh" >"$T/unsafe.log" 2>&1 || { cat "$T/unsafe.log"; false; }'
else
  bad "tes SunUnsafe dilewati: javac/java tidak ada"
fi
check "settings.gradle.kts memuat :stub dan :unsafe" 'grep -q "include(\":stub\")" "$TD/../settings.gradle.kts" && grep -q "include(\":unsafe\")" "$TD/../settings.gradle.kts"'
check "zygote mengeluarkan SunUnsafeWrapper dan memakai :unsafe" 'grep -q "module = \"SunUnsafeWrapper\"" "$TD/../zygote/build.gradle.kts" && grep -q "implementation(project(\":unsafe\"))" "$TD/../zygote/build.gradle.kts"'
check "stub hanya compileOnly (tidak ikut ke dex)" 'grep -q "compileOnly(project(\":stub\"))" "$TD/../unsafe/build.gradle.kts" && ! grep -q "project(\":stub\")" "$TD/../zygote/build.gradle.kts"'

echo "[webui: posisi dari jendela trk]"
if command -v node >/dev/null 2>&1; then
  WJ="$A/webroot/index.html"
  sed -n '/^function trackPos/,/^}/p' "$WJ" > "$T/tp.js"
  cat >> "$T/tp.js" <<'EOJ'
const r = trackPos('t=1000;k=500;a=1000;d=,-7.0,110.0,0,0,0|,-7.2,110.4,0,0,0|', 1250);
const e = trackPos('t=1000;k=500;a=1000;d=,-7.0,110.0,0,0,0|', 5000);
console.log(r[0].toFixed(3) + ' ' + r[1].toFixed(3) + ' ' + e[0].toFixed(3) + ' ' + (trackPos('t=1;k=500;d=,1,2|', 5) === null));
EOJ
  check "trackPos: interpolasi tengah tick dan tahan titik terakhir, data rusak -> null" '[ "$(node "$T/tp.js")" = "-7.100 110.200 -7.000 true" ]'
  check "readCfg: perintah shell membaca cfg, trk, dan uptime hanya saat tk=1" 'grep -q "SK.trk} 2>/dev/null; read U" "$WJ"'
else
  bad "tes webui dilewati: node tidak ada"
fi

echo
echo "lulus: $PASS  gagal: $FAIL"
[ "$FAIL" = 0 ]
