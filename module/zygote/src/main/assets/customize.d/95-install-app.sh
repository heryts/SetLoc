# Pasang app SetLoc langsung saat modul di-flash (kalau gagal, bridge.sh mencoba lagi saat boot)
APP_APK="$MODPATH/apk/SetLoc.apk"
if [ -f "$APP_APK" ]; then
  ui_print "- Installing SetLoc app"
  cp "$APP_APK" /data/local/tmp/setloc_app.apk && chmod 644 /data/local/tmp/setloc_app.apk
  OUT=$(pm install -r /data/local/tmp/setloc_app.apk 2>&1)
  rm -f /data/local/tmp/setloc_app.apk
  case "$OUT" in
    *Success*) ui_print "  app installed" ;;
    *) ui_print "  ! pm install gagal: $OUT"; ui_print "  akan dicoba lagi saat boot" ;;
  esac
fi
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/bridge.sh" 0 0 0755
