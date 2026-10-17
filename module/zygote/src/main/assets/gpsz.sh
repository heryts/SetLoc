#!/system/bin/sh
MODDIR=$(cd "$(dirname "$0")" && pwd)
PKGDIR="$MODDIR/packages"
KEY=gpsz_cfg
case "$1" in sync|check|status|doctor|super) ;; *) sh "$MODDIR/bridge.sh" sync >/dev/null 2>&1 ;; esac
RAW=$(settings get global $KEY 2>/dev/null)
get() { echo "$RAW" | tr ';' '\n' | sed -n "s/^$1=//p" | head -1; }
E=$(get enabled);  [ -z "$E" ] && E=0
LAT=$(get lat);    [ -z "$LAT" ] && LAT=-6.2088
LNG=$(get lng);    [ -z "$LNG" ] && LNG=106.8456
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
  settings put global $KEY "enabled=$E;lat=$LAT;lng=$LNG;acc=$ACC;random=$RND$X"
}
injected() {
  [ -e "$PKGDIR/android" ] || return 1
  mt=$(stat -c %Y "$PKGDIR/android" 2>/dev/null || echo 0)
  [ "$mt" -lt $(( $(date +%s) - $(cut -d. -f1 /proc/uptime) )) ]
}
sysmode() { mkdir -p "$PKGDIR" && rm -f "$PKGDIR/.sysoff" && { [ -e "$PKGDIR/android" ] || : > "$PKGDIR/android"; } && settings put global gpsz_sys $1; }
isnum() { case "$1" in ''|*[!0-9.-]*) return 1 ;; *) return 0 ;; esac; }
validpkg() { case "$1" in ''|.*|*[!A-Za-z0-9._]*) return 1 ;; *) return 0 ;; esac; }

case "$1" in
  on)     E=1; save; sh "$MODDIR/bridge.sh" setstart 1 >/dev/null 2>&1 ;;
  off)    E=0; save; sh "$MODDIR/bridge.sh" setstart 0 >/dev/null 2>&1 ;;
  toggle) [ "$E" = 1 ] && E=0 || E=1; save; sh "$MODDIR/bridge.sh" setstart $E >/dev/null 2>&1 ;;
  set)
    isnum "$2" && isnum "$3" || { echo "usage: set <lat> <lng> [acc]"; exit 1; }
    LAT=$2; LNG=$3; SPD=0; BRG=""
    if [ -n "$4" ]; then isnum "$4" || { echo "acc must be a number"; exit 1; }; ACC=$4; fi
    save ;;
  random) RND=$2; save ;;
  show)   echo "enabled=$E lat=$LAT lng=$LNG acc=$ACC random=$RND spd=${SPD:-0} brg=${BRG:--} alt=${ALT:-0} altv=${ALTV:-0} rr=${RR:-8}"
          [ -e "$PKGDIR/.all" ] && echo "packages: ALL apps (listed ones below are EXCLUDED)"
          ls -1A "$PKGDIR" 2>/dev/null | grep -v '^\.\(all\|sysoff\)$' | sed 's/^/  pkg: /' ;;
  pkg)
    case "$2" in
      add)  validpkg "$3" || { echo "usage: pkg add <package>"; exit 1; }
            mkdir -p "$PKGDIR" && : > "$PKGDIR/$3" && am force-stop "$3" 2>/dev/null
            echo "added $3 (app restarted)" ;;
      rm)   validpkg "$3" || { echo "usage: pkg rm <package>"; exit 1; }
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
      off) injected && live=1 || live=0
           rm -f "$PKGDIR/android" "$PKGDIR/.sysoff"; [ "$live" = 1 ] && : > "$PKGDIR/.sysoff"
           settings put global gpsz_sys 0
           echo "system hook OFF. Reboot agar system_server tidak lagi di-inject." ;;
      status) [ -e "$PKGDIR/android" ] && F=ya || F=tidak
           ST=off
           case "$(settings get global gpsz_sys 2>/dev/null)" in
             1|2) [ "$F" = ya ] && { injected && ST=live || ST=pending; } ;;
           esac
           echo "packages/android: $F   gpsz_sys=$(settings get global gpsz_sys 2>/dev/null)   state=$ST"
           echo "log system hook (logcat -s GpsZ):"
           logcat -d -s GpsZ:I 2>/dev/null | grep -i "system hook" | tail -12 | sed 's/^/  /' ;;
      *)   echo "system on|heartbeat|off|status" ;;
    esac ;;
  sync)   sh "$MODDIR/bridge.sh" force; echo "cfg=$(settings get global $KEY 2>/dev/null)" ;;
  status) sh "$MODDIR/bridge.sh" status ;;
  check)
    validpkg "$2" || { echo "usage: check <package>"; exit 1; }
    [ -e "$PKGDIR/$2" ] && F=ya || F=tidak
    [ -e "$PKGDIR/.all" ] && A=ya || A=tidak
    if [ "$A" = ya ]; then [ "$F" = ya ] && W=TIDAK || W=YA; else W=$F; fi
    echo "config gpsz_cfg : $RAW"
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
    echo "gpsz_sys=$(settings get global gpsz_sys 2>/dev/null) gpsz_beat=$(settings get global gpsz_beat 2>/dev/null) uptime=$(cut -d' ' -f1 /proc/uptime)"
    echo "--- bridge.log (10 baris terakhir)"
    tail -n 10 "$MODDIR/bridge.log" 2>/dev/null ;;
  *)      echo "on|off|toggle|set <lat> <lng> [acc]|random 0|1|show|pkg ...|system on|heartbeat|off|status|sync|check <pkg>|super on|off|doctor" ;;
esac
