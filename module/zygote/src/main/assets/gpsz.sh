#!/system/bin/sh
MODDIR=$(cd "$(dirname "$0")" && pwd)
PKGDIR="$MODDIR/packages"
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
case "$1" in sync|check|status|doctor|super|net|bt) ;; *) sh "$MODDIR/bridge.sh" sync >/dev/null 2>&1 ;; esac
RAW=$(dec "$(settings get global $KEY 2>/dev/null)")
get() { T=";$RAW;"; V=${T#*";$1="}; [ "$V" = "$T" ] && return; echo "${V%%;*}"; }
E=$(get enabled);  [ -z "$E" ] && E=0
LAT=$(get lat);    [ -z "$LAT" ] && LAT=-7.7829
LNG=$(get lng);    [ -z "$LNG" ] && LNG=110.3671
ACC=$(get acc);    [ -z "$ACC" ] && ACC=10
RND=$(get random); [ -z "$RND" ] && RND=0
SPD=$(get spd); BRG=$(get brg); ALT=$(get alt); ALTV=$(get altv); RR=$(get rr)
save() {
  X=""
  [ -n "$SPD" ] && X="$X;spd=$SPD"
  [ -n "$ALT" ] && X="$X;alt=$ALT"
  [ -n "$ALTV" ] && X="$X;altv=$ALTV"
  [ -n "$RR" ] && X="$X;rr=$RR"
  [ -n "$BRG" ] && X="$X;brg=$BRG"
  settings put global $KEY "$(enc "enabled=$E;lat=$LAT;lng=$LNG;acc=$ACC;random=$RND$X")"
  mirror
}
mirror() { sh "$MODDIR/bridge.sh" mirror >/dev/null 2>&1; }
bootts() { echo $(( $(date +%s) - $(cut -d. -f1 /proc/uptime) )); }
mtime() { stat -c %Y "$1" 2>/dev/null || echo 0; }
injected() { [ -e "$PKGDIR/android" ] && [ "$(mtime "$PKGDIR/android")" -lt "$(bootts)" ]; }
sysmode() {
  mkdir -p "$PKGDIR" || return 1
  keep=0
  if [ -f "$PKGDIR/.sysoff" ] && [ -f "$PKGDIR/.sysmt" ]; then
    [ "$(mtime "$PKGDIR/.sysoff")" -ge "$(bootts)" ] && keep=1
  fi
  rm -f "$PKGDIR/.sysoff"
  if [ ! -e "$PKGDIR/android" ]; then
    : > "$PKGDIR/android"
    [ "$keep" = 1 ] && touch -r "$PKGDIR/.sysmt" "$PKGDIR/android"
  fi
  rm -f "$PKGDIR/.sysmt"
  settings put global $SYSKEY "$(enc "$1")"
  mirror
}
isnum() { case "$1" in ''|-|.|-.|*[!0-9.-]*|*.*.*|?*-*) return 1 ;; *) return 0 ;; esac; }
inrange() {
  v=${1#-}; i=${v%%.*}; [ -n "$i" ] || i=0
  f=; case "$v" in *.*) f=${v#*.} ;; esac
  [ "$i" -lt "$2" ] 2>/dev/null && return 0
  [ "$i" -eq "$2" ] 2>/dev/null || return 1
  case "$f" in *[!0]*) return 1 ;; esac
  return 0
}
validpkg() { case "$1" in ''|.*|*[!A-Za-z0-9._]*) return 1 ;; *) return 0 ;; esac; }

case "$1" in
  on)     E=1; save; sh "$MODDIR/bridge.sh" setstart 1 >/dev/null 2>&1 ;;
  off)    E=0; save; sh "$MODDIR/bridge.sh" setstart 0 >/dev/null 2>&1 ;;
  toggle) [ "$E" = 1 ] && E=0 || E=1; save; sh "$MODDIR/bridge.sh" setstart $E >/dev/null 2>&1 ;;
  set)
    isnum "$2" && isnum "$3" || { echo "usage: set <lat> <lng> [acc]"; exit 1; }
    inrange "$2" 90 && inrange "$3" 180 || { echo "lat harus -90..90 dan lng -180..180"; exit 1; }
    LAT=$2; LNG=$3; SPD=0; BRG=""
    if [ -n "$4" ]; then isnum "$4" && [ "${4#-}" = "$4" ] || { echo "acc must be a non-negative number"; exit 1; }; ACC=$4; fi
    save
    sh "$MODDIR/bridge.sh" setpos "$LAT" "$LNG" "$4" >/dev/null 2>&1 || : ;;
  random)
    case "$2" in 0|1) RND=$2; save; sh "$MODDIR/bridge.sh" setrandom "$2" >/dev/null 2>&1 || : ;; *) echo "usage: random 0|1"; exit 1 ;; esac ;;
  bt)
    case "$2" in
      on)  settings put global $BTKEY "$(enc 1)"; mirror; echo "blokir scan Bluetooth: aktif" ;;
      off) settings put global $BTKEY "$(enc 0)"; mirror; echo "blokir scan Bluetooth: mati" ;;
      *)   [ "$(dec "$(settings get global $BTKEY 2>/dev/null)")" = 1 ] && echo "bt=aktif" || echo "bt=mati" ;;
    esac ;;
  net)
    case "$2" in
      on)  settings put global $NETKEY "$(enc 1)"; mirror; echo "sembunyikan Wi-Fi/cell: aktif" ;;
      off) settings put global $NETKEY "$(enc 0)"; mirror; echo "sembunyikan Wi-Fi/cell: mati" ;;
      *)   [ "$(dec "$(settings get global $NETKEY 2>/dev/null)")" = 0 ] && echo "net=mati" || echo "net=aktif" ;;
    esac ;;
  show)   echo "enabled=$E lat=$LAT lng=$LNG acc=$ACC random=$RND spd=${SPD:-0} brg=${BRG:--} alt=${ALT:-0} altv=${ALTV:-0} rr=${RR:-8}"
          [ -e "$PKGDIR/.all" ] && echo "packages: ALL apps (listed ones below are EXCLUDED)"
          ls -1A "$PKGDIR" 2>/dev/null | grep -v '^\.\(all\|sysoff\|sysmt\)$' | sed 's/^/  pkg: /' ;;
  pkg)
    case "$2" in
      add)  validpkg "$3" || { echo "usage: pkg add <package>"; exit 1; }
            [ "$3" = android ] && { echo "android diatur lewat: system on|heartbeat|off"; exit 1; }
            mkdir -p "$PKGDIR" && : > "$PKGDIR/$3" && am force-stop "$3" 2>/dev/null
            echo "added $3 (app restarted)" ;;
      rm)   validpkg "$3" || { echo "usage: pkg rm <package>"; exit 1; }
            [ "$3" = android ] && { echo "android diatur lewat: system on|heartbeat|off"; exit 1; }
            rm -f "$PKGDIR/$3" && am force-stop "$3" 2>/dev/null
            echo "removed $3" ;;
      list) ls -1A "$PKGDIR" 2>/dev/null ;;
      all)  mkdir -p "$PKGDIR"
            if [ "$3" = on ]; then : > "$PKGDIR/.all"; echo "injecting into ALL apps (risky; reboot or restart apps)"
            else rm -f "$PKGDIR/.all"; echo "all-apps mode off"; fi ;;
      *)    echo "pkg add|rm <package> | pkg list | pkg all on|off" ;;
    esac ;;
  system)
    case "$2" in
      on)  sysmode 1
           echo "system hook ON (mode PENUH: semua app dispoof). REBOOT dibutuhkan bila belum live. Bootloop? buat file $MODDIR/disable dari recovery." ;;
      heartbeat) sysmode 2
           echo "system hook mode HEARTBEAT: sistem hanya mengulang fix asli 1 Hz, lokasi app lain TIDAK diubah (spoof hanya app di packages/). Bila sudah live, berlaku tanpa reboot; bila belum, REBOOT." ;;
      off) if [ -e "$PKGDIR/android" ]; then
             if injected; then
               touch -r "$PKGDIR/android" "$PKGDIR/.sysmt"
               rm -f "$PKGDIR/android" "$PKGDIR/.sysoff"; : > "$PKGDIR/.sysoff"
             else
               rm -f "$PKGDIR/android" "$PKGDIR/.sysoff" "$PKGDIR/.sysmt"
               settings put global $SYSKEY "$(enc 0)"; mirror
             fi
           else
             [ -f "$PKGDIR/.sysoff" ] && [ "$(mtime "$PKGDIR/.sysoff")" -ge "$(bootts)" ] || { settings put global $SYSKEY "$(enc 0)"; mirror; }
           fi
           echo "system hook OFF. Reboot agar system_server tidak lagi di-inject." ;;
      status) [ -e "$PKGDIR/android" ] && F=ya || F=tidak
           ST=off
           SV=$(dec "$(settings get global $SYSKEY 2>/dev/null)")
           case "$SV" in
             1|2) [ "$F" = ya ] && { injected && ST=live || ST=pending; } ;;
           esac
           echo "packages/android: $F   sys=$SV   state=$ST"
           echo "log system hook (logcat -s GpsZ):"
           logcat -d -s GpsZ:I 2>/dev/null | grep -i "system hook" | tail -12 | sed 's/^/  /' ;;
      *)   echo "system on|heartbeat|off|status" ;;
    esac ;;
  sync)   sh "$MODDIR/bridge.sh" force; echo "cfg=$(dec "$(settings get global $KEY 2>/dev/null)")" ;;
  status) sh "$MODDIR/bridge.sh" status ;;
  check)
    validpkg "$2" || { echo "usage: check <package>"; exit 1; }
    [ -e "$PKGDIR/$2" ] && F=ya || F=tidak
    [ -e "$PKGDIR/.all" ] && A=ya || A=tidak
    if [ "$A" = ya ]; then [ "$F" = ya ] && W=TIDAK || W=YA; else W=$F; fi
    echo "config : $RAW"
    echo "file packages/$2 : $F   (mode .all: $A)  -> seharusnya di-inject: $W"
    echo "proses berjalan : $(pidof "$2" 2>/dev/null || echo tidak ada)"
    echo "log inject terakhir (logcat -s GpsZ):"
    logcat -d -s GpsZ:I 2>/dev/null | grep "injected into $2" | tail -3 | sed 's/^/  /'
    echo "  (kosong = proses ini TIDAK pernah ter-inject sejak buffer log terakhir)" ;;
  super)
    case "$2" in
      off) : > "$MODDIR/disable_super"; echo "supervisor dimatikan (daemon yang di-kill tidak dinyalakan lagi)" ;;
      on)  rm -f "$MODDIR/disable_super"; echo "supervisor aktif" ;;
      *)   [ -f "$MODDIR/disable_super" ] && echo "supervisor=dimatikan" || echo "supervisor=aktif" ;;
    esac ;;
  doctor)
    sh "$MODDIR/bridge.sh" status 2>&1
    [ -f "$MODDIR/disable_super" ] && echo "supervisor=dimatikan (disable_super)" || echo "supervisor=aktif"
    echo "modul=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null) code=$(sed -n 's/^versionCode=//p' "$MODDIR/module.prop" 2>/dev/null)"
    echo "sys=$(dec "$(settings get global $SYSKEY 2>/dev/null)") beat=$(dec "$(settings get global $BEATKEY 2>/dev/null)") uptime=$(cut -d' ' -f1 /proc/uptime)"
    [ -f "$MODDIR/disable_trk" ] && echo "trk=dimatikan (disable_trk)" || echo "trk=aktif trkdata=$(settings get global $TRKKEY 2>/dev/null | cut -c1-48)"
    for F in /data/system/gpsz_config /data/adb/gpsz_config; do
      [ -f "$F" ] && echo "mirror: $(ls -lZ "$F" 2>/dev/null)" || echo "mirror: tidak ada ($F)"
    done
    echo "baca mirror oleh system_server (logcat -s GpsZ):"
    logcat -d -s GpsZ:I GpsZ:W 2>/dev/null | grep -i "config file" | tail -4 | sed 's/^/  /'
    echo "--- hook sistem (logcat -s GpsZ)"
    logcat -d -s GpsZ:I 2>/dev/null | grep -i "system hook" | tail -8 | sed 's/^/  /' ;;
  *)      echo "on|off|toggle|set <lat> <lng> [acc]|random 0|1|net on|off|bt on|off|show|pkg ...|system on|heartbeat|off|status|sync|check <pkg>|super on|off|doctor" ;;
esac
