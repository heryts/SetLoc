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
    tr '\0' ' ' < "/proc/$q/cmdline" 2>/dev/null | grep -q 'setlocd daemon' && echo "$q"
  done
}
kill_bridges() {
  for k in super any; do
    for d in /proc/[0-9]*; do
      c=$({ tr '\0' ' ' < "$d/cmdline"; } 2>/dev/null)
      case "$k:$c" in
        super:*"$1"/*setlocd*super*|any:*"$1"/*setlocd*) kill -9 "${d#/proc/}" 2>/dev/null ;;
      esac
    done
  done
}
cleanup() { kill_bridges "$T"; rm -rf "$T"; }
trap cleanup EXIT

cp "$A/bridge.sh" "$A/gpsz.sh" "$M/"
D="$A/../../../../../daemon"
BIN=${SETLOCD_BIN:-}
if [ -z "$BIN" ]; then
  command -v cargo >/dev/null 2>&1 || { echo "butuh cargo atau SETLOCD_BIN"; exit 1; }
  ( cd "$D" && cargo build --release -q ) || { echo "build daemon gagal"; exit 1; }
  BIN="$D/target/release/setlocd"
fi
cp "$BIN" "$M/setlocd"; chmod +x "$M/setlocd"
: > "$M/disable_super"
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

SALT="Vzvwk4Rxj7Ps"
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
SYSTAG=db078e; TRKTAG=c724d0; BEATTAG=c1e67d

echo "[sintaks]"
for f in "$A"/*.sh "$A"/customize.d/*.sh "$A/../../../../setup.sh"; do
  [ -f "$f" ] && check "sh -n $(basename "$f")" "sh -n '$f'"
done

echo "[daemon: arbitrasi]"
for i in 1 2 3 4 5 6; do ( setsid sh "$M/bridge.sh" daemon >/dev/null 2>&1 & ); done
sleep 4
check "6 daemon bersamaan -> tepat 1 hidup" '[ "$(live_pids | wc -l)" = 1 ]'

echo "[daemon: SIGKILL dan supervisor]"
W=$(live_pids | head -1); kill -9 "$W"; sleep 0.3
sh "$M/bridge.sh" ensure super >/dev/null; sleep 2
check "ensure super menghidupkan lagi" '[ "$(live_pids | wc -l)" = 1 ]'
check "pid baru berbeda" '[ "$(live_pids | head -1)" != "$W" ]'

echo "[daemon: TERM]"
W=$(live_pids | head -1); kill -TERM "$W"; sleep 7
check "tidak ada daemon hidup" '[ "$(live_pids | wc -l)" = 0 ]'
check "tidak ada bridge.log" '[ ! -e "$M/bridge.log" ]'
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

echo "[supervisor: saling mengawasi]"
SV="$T/sv"; rm -rf "$SV"; mkdir -p "$SV"
cp "$A/bridge.sh" "$A/gpsz.sh" "$M/setlocd" "$SV/"
export SETLOCD_SUPER_SLEEP_MS=1000 SETLOCD_SUPER_CHECK_CS=200
sv_dpids() { for f in "$SV"/.daemon.lock/*; do q=${f##*/}; case "$q" in ''|*[!0-9]*) continue ;; esac; { tr '\0' ' ' < "/proc/$q/cmdline"; } 2>/dev/null | grep -q 'setlocd daemon' && echo "$q"; done; }
sv_spid() { cat "$SV/.super.pid" 2>/dev/null; }
sv_alive() { p=$(sv_spid); [ -n "$p" ] && { tr '\0' ' ' < "/proc/$p/cmdline"; } 2>/dev/null | grep -q 'setlocd super'; }
sv_count() { n=0; for d in /proc/[0-9]*; do { tr '\0' ' ' < "$d/cmdline"; } 2>/dev/null | grep -q "$SV/setlocd supe[r]" && n=$((n+1)); done; echo $n; }
sv_clean() { kill_bridges "$SV"; sleep 0.3; }
sh "$SV/bridge.sh" ensure >/dev/null 2>&1; sleep 1
check "ensure menghidupkan daemon dan supervisor" '[ "$(sv_dpids | wc -l)" = 1 ] && sv_alive'
check "hanya ada satu proses supervisor" '[ "$(sv_count)" = 1 ]'
SW=$(sv_dpids | head -1); kill -9 "$SW"; sleep 4
check "supervisor menghidupkan lagi daemon yang di-SIGKILL" '[ "$(sv_dpids | wc -l)" = 1 ] && [ "$(sv_dpids | head -1)" != "$SW" ]'
sleep 6
SP0=$(sv_spid); kill -9 "$SP0"; sleep 8
check "daemon menghidupkan lagi supervisor yang di-SIGKILL" 'sv_alive && [ "$(sv_spid)" != "$SP0" ]'
check "supervisor pengganti tetap satu proses" '[ "$(sv_count)" = 1 ]'
: > "$SV/disable_super"; SP0=$(sv_spid); kill -9 "$SP0"; sleep 8
check "disable_super: supervisor tidak dihidupkan lagi oleh daemon" '! sv_alive'
SW=$(sv_dpids | head -1); kill -9 "$SW"; sleep 4
check "disable_super: daemon yang di-kill tidak dihidupkan lagi" '[ "$(sv_dpids | wc -l)" = 0 ]'
rm -f "$SV/disable_super"; sh "$SV/bridge.sh" ensure >/dev/null 2>&1; sleep 1
check "ensure sesudah disable_super dihapus menghidupkan keduanya" '[ "$(sv_dpids | wc -l)" = 1 ] && sv_alive'
out=$(sh "$SV/bridge.sh" status 2>/dev/null)
check "status melaporkan super=running" 'echo "$out" | grep -qx "super=running"'
sv_clean
out=$(sh "$SV/bridge.sh" status 2>/dev/null)
check "status melaporkan super=stopped saat mati" 'echo "$out" | grep -qx "super=stopped"'
for i in 1 2 3; do ( setsid sh "$SV/bridge.sh" super >/dev/null 2>&1 & ); done
sleep 3
check "tiga supervisor bersamaan -> tersisa satu" '[ "$(sv_count)" = 1 ]'
sv_clean

unset SETLOCD_SUPER_SLEEP_MS SETLOCD_SUPER_CHECK_CS


echo "[make-keystore.sh]"
MK="$A/../../../../../make-keystore.sh"
check "make-keystore: read -r dan password lewat env, bukan argumen" 'grep -q "IFS= read -r PASS" "$MK" && grep -q -- "-passout env:KS_PASS" "$MK" && ! grep -q "pass:" "$MK"'
if command -v openssl >/dev/null 2>&1 && command -v java >/dev/null 2>&1 && { command -v javac >/dev/null 2>&1 || java --list-modules 2>/dev/null | grep -q '^jdk.compiler@'; }; then
  KD="$T/ks"; rm -rf "$KD"; mkdir -p "$KD/app"; cp "$MK" "$KD/"
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout "$KD/k.pem" -out "$KD/c.pem" -subj /CN=t -days 2 >/dev/null 2>&1
  cat > "$KD/Prop.java" <<'EOJ'
import java.io.*;
import java.util.*;
public class Prop { public static void main(String[] a) throws Exception { Properties p = new Properties(); try (FileInputStream f = new FileInputStream(a[0])) { p.load(f); } System.out.print(p.getProperty(a[1])); } }
EOJ
  cat > "$KD/t.sh" <<'EOS'
KD=$1; PW='a\b$c "d" `e` \\f'
printf '%s\n' "$PW" | sh "$KD/make-keystore.sh" "$KD/k.pem" "$KD/c.pem" "$KD/o.p12" >/dev/null 2>&1 || exit 1
P="$PW" openssl pkcs12 -in "$KD/o.p12" -passin env:P -nokeys >/dev/null 2>&1 || exit 2
[ "$(java "$KD/Prop.java" "$KD/app/keystore.properties" storePassword)" = "$PW" ] || exit 3
[ "$(java "$KD/Prop.java" "$KD/app/keystore.properties" keyPassword)" = "$PW" ] || exit 4
[ "$(java "$KD/Prop.java" "$KD/app/keystore.properties" storeFile)" = "$KD/o.p12" ] || exit 5
EOS
  check "make-keystore: password dengan backslash, \$, kutip dan spasi utuh di p12 dan keystore.properties" 'sh "$KD/t.sh" "$KD"'
else
  bad "tes make-keystore dilewati: openssl/java tidak ada"
fi

if command -v openssl >/dev/null 2>&1 && command -v java >/dev/null 2>&1 && [ -d "$KD" ]; then
  cat > "$KD/t2.sh" <<'EOS'
KD=$1
for PW in '  lead space' ' lead' 'trail ' ' both ' "$(printf '\ttab')"; do
  rm -f "$KD/o2.p12" "$KD/app/keystore.properties"
  printf '%s\n' "$PW" | sh "$KD/make-keystore.sh" "$KD/k.pem" "$KD/c.pem" "$KD/o2.p12" >/dev/null 2>&1 || exit 1
  P="$PW" openssl pkcs12 -in "$KD/o2.p12" -passin env:P -nokeys >/dev/null 2>&1 || exit 2
  [ "$(java "$KD/Prop.java" "$KD/app/keystore.properties" storePassword)" = "$PW" ] || exit 3
  [ "$(java "$KD/Prop.java" "$KD/app/keystore.properties" keyPassword)" = "$PW" ] || exit 4
done
EOS
  check "make-keystore: spasi/tab di awal atau akhir password utuh (p12 dan Properties)" 'sh "$KD/t2.sh" "$KD"'
  check "make-keystore: password kosong ditolak" '! printf "\n" | sh "$KD/make-keystore.sh" "$KD/k.pem" "$KD/c.pem" "$KD/o3.p12" >/dev/null 2>&1 && [ ! -e "$KD/o3.p12" ]'
fi


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
  S="$T/svc"; rm -rf "$S"; mkdir -p "$S"; cp "$A/service.sh" "$A/bridge.sh" "$M/setlocd" "$S/"
  [ -n "$2" ] && : > "$S/.hb_default"
  if [ "$1" = unset ]; then rm -f "$T/st/$SYSTAG"; else enc "$1" > "$T/st/$SYSTAG"; fi
  ( setsid sh -c 'echo $$ > "$1/pgid"; exec sh "$1/service.sh"' sh "$S" >/dev/null 2>&1 & )
  sleep 1.5
  kill -9 -- "-$(cat "$S/pgid")" 2>/dev/null
  kill_bridges "$S"
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
check "SALT WebUI sama dengan salt build (dekode state system/heartbeat)" '[ "$(sed -n "s/^const SALT = .\(.*\).;.*/\1/p" "$A/webroot/index.html")" = "$SALT" ]'
check "SALT WebUI sama dengan Config.java" '[ "$(sed -n "s/^const SALT = .\(.*\).;.*/\1/p" "$A/webroot/index.html")" = "$(sed -n "s/.*SALT = \"\(.*\)\";/\1/p" "$A/../java/io/gpsz/zygote/Config.java")" ]'

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
  check "PER_OBJ berbasis identitas: equals/hashCode Location tidak dipanggil, entri di-GC dibersihkan" 'sh "$TD/idmap/run.sh" >"$T/idmap.log" 2>&1 || { cat "$T/idmap.log"; false; }'
  check "LocationHooks tidak memakai WeakHashMap/Collections.synchronizedMap untuk PER_OBJ" '! grep -q "WeakHashMap" "$TD/../zygote/src/main/java/io/gpsz/zygote/LocationHooks.java" && grep -q "WeakIdentityMap<Mark> PER_OBJ" "$TD/../zygote/src/main/java/io/gpsz/zygote/LocationHooks.java"'
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
console.log(r[0].toFixed(3) + ' ' + r[1].toFixed(3) + ' ' + e[0].toFixed(3) + ' ' + (trackPos('t=1;k=500;d=,1,2|', 5) === null) + ' ' + (trackPos('k=500;d=,1,2,0,0,0|', 5) === null));
EOJ
  check "trackPos: interpolasi tengah tick dan tahan titik terakhir, data rusak -> null" '[ "$(node "$T/tp.js")" = "-7.100 110.200 -7.000 true true" ]'
  for jk in cfg sys net bt trk; do
    check "kunci WebUI SK.$jk cocok dengan tag($jk)" '[ "$(tag "$jk")" = "$(sed -n "s/^const SK = .*[{ ,]$jk: '"'"'\([0-9a-f]*\)'"'"'.*/\1/p" "$WJ")" ]'
  done
  check "SALT WebUI sama dengan SALT di Config.java" 'grep -q "^const SALT = .$SALT.;" "$WJ" && grep -q "SALT = \"$SALT\"" "$TD/../zygote/src/main/java/io/gpsz/zygote/Config.java"'
  check "readCfg: perintah shell membaca cfg, trk, dan uptime hanya saat tk=1" 'grep -q "SK.trk} 2>/dev/null; read U" "$WJ"'
else
  bad "tes webui dilewati: node tidak ada"
fi



echo
echo "lulus: $PASS  gagal: $FAIL"
[ "$FAIL" = 0 ]

