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
check "heartbeat -> gpsz_sys=2" '$G system status | grep -q "gpsz_sys=2"'
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
check "doctor memuat daemon, supervisor, log" 'echo "$out" | grep -q "daemon=" && echo "$out" | grep -q "supervisor=" && echo "$out" | grep -q "bridge.log"'
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

echo "[bridge.sh: pref_set]"
sed -n '/^pref_set() {/,/^}/p' "$A/bridge.sh" > "$T/pref_set.sh"
printf '%s\n' "<map>" '    <boolean name="start" value="false" />' "</map>" > "$T/p.xml"
( PREF="$T/p.xml"; MODDIR="$T"; log() { :; }; . "$T/pref_set.sh"
  pref_set boolean start true; pref_set float latitude -7.5 )
check "pref_set mengubah nilai yang ada" 'grep -q "name=\"start\" value=\"true\"" "$T/p.xml"'
check "pref_set menambah kunci yang belum ada" 'grep -q "name=\"latitude\" value=\"-7.5\"" "$T/p.xml"'

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
: > "$O/.hb_default"; : > "$O/disable_super"; echo OLDSUM > "$O/.apk_sum"; echo log > "$O/bridge.log"
out=$( ui_print() { echo "$*"; }; MODPATH="$N"; OLD_MOD="$O"; . "$A/customize.d/40-install-apk.sh" )
check "daftar app, .all, android, .sysoff ikut" '[ -e "$N/packages/com.a.app" ] && [ -e "$N/packages/com.b.app" ] && [ -e "$N/packages/.all" ] && [ -e "$N/packages/android" ] && [ -e "$N/packages/.sysoff" ]'
check "mtime android dipertahankan (status live tidak berubah jadi pending)" '[ "$(stat -c %Y "$N/packages/android")" = "$(stat -c %Y "$O/packages/android")" ]'
check ".hb_default dan disable_super ikut" '[ -e "$N/.hb_default" ] && [ -e "$N/disable_super" ]'
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
  S="$T/svc"; rm -rf "$S"; mkdir -p "$S"; cp "$A/service.sh" "$S/"
  [ -n "$2" ] && : > "$S/.hb_default"
  if [ "$1" = unset ]; then rm -f "$T/st/gpsz_sys"; else printf '%s' "$1" > "$T/st/gpsz_sys"; fi
  ( setsid sh -c 'echo $$ > "$1/pgid"; exec sh "$1/service.sh"' sh "$S" >/dev/null 2>&1 & )
  sleep 1.5
  kill -9 -- "-$(cat "$S/pgid")" 2>/dev/null
}
svc 1
check "reinstall: mode penuh (1) tidak ditimpa jadi heartbeat, penanda dibuat" '[ "$(cat "$T/st/gpsz_sys")" = 1 ] && [ -e "$S/.hb_default" ]'
svc 0
check "reinstall: mode mati (0) tidak ditimpa, penanda dibuat" '[ "$(cat "$T/st/gpsz_sys")" = 0 ] && [ -e "$S/.hb_default" ]'
svc unset
check "pemasangan pertama: default heartbeat (2) diterapkan, penanda dibuat" '[ "$(cat "$T/st/gpsz_sys")" = 2 ] && [ -e "$S/.hb_default" ]'
svc 1 marker
check "penanda sudah ada: tidak menyentuh pengaturan" '[ "$(cat "$T/st/gpsz_sys")" = 1 ]'

echo "[hook: Config.checkBeat (beat daemon)]"
TD="$(cd "$(dirname "$0")" && pwd)"
if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  check "checkBeat + refresh: freeze, off, resume, jeda poll, titik tunggal, snapshot, flag sistem, onReady sekali" 'sh "$TD/beat/run.sh" >"$T/beat.log" 2>&1 || { cat "$T/beat.log"; false; }'
else
  echo "  lewati (javac/java tidak ada)"
fi

echo
echo "lulus: $PASS  gagal: $FAIL"
[ "$FAIL" = 0 ]
