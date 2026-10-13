#!/system/bin/sh
# Run as root:  su -c "sh /data/adb/modules/setloc_zygisk/gpsz.sh <cmd>"
#   on | off | toggle   (juga mengubah tombol Start/Stop di app SetLoc) | set <lat> <lng> [acc] | random 0|1 | show
#   pkg add <package> | pkg rm <package> | pkg list | pkg all on|off
#   system on|off|status   (hook system_server/LocationManagerService; wajib REBOOT)
#   sync | status | check <package>   (diagnosa)
MODDIR=$(cd "$(dirname "$0")" && pwd)
PKGDIR="$MODDIR/packages"
KEY=gpsz_cfg
# ambil dulu perubahan terbaru dari app SetLoc (hanya menimpa bila pengaturan di app berubah)
case "$1" in sync|check|status) ;; *) sh "$MODDIR/bridge.sh" sync >/dev/null 2>&1 ;; esac
RAW=$(settings get global $KEY 2>/dev/null)
get() { echo "$RAW" | tr ';' '\n' | sed -n "s/^$1=//p" | head -1; }
E=$(get enabled);  [ -z "$E" ] && E=0
LAT=$(get lat);    [ -z "$LAT" ] && LAT=-6.2088
LNG=$(get lng);    [ -z "$LNG" ] && LNG=106.8456
ACC=$(get acc);    [ -z "$ACC" ] && ACC=10
RND=$(get random); [ -z "$RND" ] && RND=0
# kecepatan/arah/ketinggian dari app; ikut dibawa saat save supaya tidak hilang
SPD=$(get spd); BRG=$(get brg); ALT=$(get alt); ALTV=$(get altv)
save() {
  X=""
  [ -n "$SPD" ] && X="$X;spd=$SPD"
  [ -n "$ALT" ] && X="$X;alt=$ALT"
  [ -n "$ALTV" ] && X="$X;altv=$ALTV"
  [ -n "$BRG" ] && X="$X;brg=$BRG"
  settings put global $KEY "enabled=$E;lat=$LAT;lng=$LNG;acc=$ACC;random=$RND$X"
}
isnum() { case "$1" in ''|*[!0-9.-]*) return 1 ;; *) return 0 ;; esac; }
validpkg() { case "$1" in ''|.*|*[!A-Za-z0-9._]*) return 1 ;; *) return 0 ;; esac; }

case "$1" in
  on)     E=1; save; sh "$MODDIR/bridge.sh" setstart 1 >/dev/null 2>&1 ;;
  off)    E=0; save; sh "$MODDIR/bridge.sh" setstart 0 >/dev/null 2>&1 ;;
  toggle) [ "$E" = 1 ] && E=0 || E=1; save; sh "$MODDIR/bridge.sh" setstart $E >/dev/null 2>&1 ;;
  set)
    isnum "$2" && isnum "$3" || { echo "usage: set <lat> <lng> [acc]"; exit 1; }
    LAT=$2; LNG=$3; SPD=0; BRG=""   # titik manual = diam
    if [ -n "$4" ]; then isnum "$4" || { echo "acc must be a number"; exit 1; }; ACC=$4; fi
    save ;;
  random) RND=$2; save ;;
  show)   echo "enabled=$E lat=$LAT lng=$LNG acc=$ACC random=$RND spd=${SPD:-0} brg=${BRG:--} alt=${ALT:-0} altv=${ALTV:-0}"
          [ -e "$PKGDIR/.all" ] && echo "packages: ALL apps (listed ones below are EXCLUDED)"
          ls -1A "$PKGDIR" 2>/dev/null | grep -v '^\.\(all\|sysoff\)$' | sed 's/^/  pkg: /' ;;
  pkg)
    # ZygoteLoader reads $MODDIR/packages/<name> at every app start; no rebuild needed.
    # NOTE: it enables a package when (file <name> exists) XOR (file .all exists):
    #       .all + <name> together means "all apps except <name>".
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
    # Hook system_server: modul harus di-inject ke "android" (file packages/android) -> perlu reboot.
    # Jika bootloop: dari recovery/adb buat file /data/adb/modules/setloc_zygisk/disable
    case "$2" in
      on)  mkdir -p "$PKGDIR" && rm -f "$PKGDIR/.sysoff" && : > "$PKGDIR/android" && settings put global gpsz_sys 1
           echo "system hook ON. REBOOT dibutuhkan. Bootloop? buat file $MODDIR/disable dari recovery." ;;
      off) live=0
           if [ -e "$PKGDIR/android" ]; then
             mt=$(stat -c %Y "$PKGDIR/android" 2>/dev/null || echo 0); boot=$(( $(date +%s) - $(cut -d. -f1 /proc/uptime) ))
             [ "$mt" -lt "$boot" ] && live=1
           fi
           rm -f "$PKGDIR/android" "$PKGDIR/.sysoff"; [ "$live" = 1 ] && : > "$PKGDIR/.sysoff"
           settings put global gpsz_sys 0
           echo "system hook OFF. Reboot agar system_server tidak lagi di-inject." ;;
      status) [ -e "$PKGDIR/android" ] && F=ya || F=tidak
           ST=off
           if [ "$F" = ya ] && [ "$(settings get global gpsz_sys 2>/dev/null)" = 1 ]; then
             mt=$(stat -c %Y "$PKGDIR/android" 2>/dev/null || echo 0); boot=$(( $(date +%s) - $(cut -d. -f1 /proc/uptime) ))
             [ "$mt" -lt "$boot" ] && ST=live || ST=pending
           fi
           echo "packages/android: $F   gpsz_sys=$(settings get global gpsz_sys 2>/dev/null)   state=$ST"
           echo "log system hook (logcat -s GpsZ):"
           logcat -d -s GpsZ:I 2>/dev/null | grep -i "system hook" | tail -12 | sed 's/^/  /' ;;
      *)   echo "system on|off|status" ;;
    esac ;;
  sync)   sh "$MODDIR/bridge.sh" force; echo "cfg=$(settings get global $KEY 2>/dev/null)" ;;
  status) sh "$MODDIR/bridge.sh" status ;;
  check)
    # Diagnosa: apakah <package> seharusnya/benar-benar ter-inject modul?
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
  *)      echo "on|off|toggle|set <lat> <lng> [acc]|random 0|1|show|pkg ...|system on|off|status|sync|status|check <pkg>" ;;
esac
