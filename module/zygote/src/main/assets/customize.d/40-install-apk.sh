# Pasang app SetLoc (apk/SetLoc.apk) saat flash. Gagal/dilewati tidak membatalkan instalasi:
# bridge.sh mencoba lagi setelah boot (checksum .apk_sum).

ui_print "- Install app SetLoc"

APK_SRC="$MODPATH/apk/SetLoc.apk"
APK_TMP=/data/local/tmp/gpsz_install.apk

if [ ! -f "$APK_SRC" ]; then
  ui_print "  ! APK tidak ada di modul, dilewati"
elif ! command -v pm >/dev/null 2>&1 || [ "$(getprop sys.boot_completed)" != 1 ]; then
  ui_print "  ! Android belum berjalan penuh (recovery?), app dipasang otomatis setelah reboot"
else
  mkdir -p /data/local/tmp
  if cp "$APK_SRC" "$APK_TMP" && chmod 644 "$APK_TMP"; then
    APK_OUT=$(pm install -r -d "$APK_TMP" 2>&1)
    rm -f "$APK_TMP"
    case "$APK_OUT" in
      *Success*)
        md5sum "$APK_SRC" 2>/dev/null | cut -d' ' -f1 > "$MODPATH/.apk_sum"
        cmd appops set com.heryts.setloc SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
        pm grant com.heryts.setloc android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1
        ui_print "  app SetLoc terpasang" ;;
      *)
        ui_print "  ! pm install gagal: $APK_OUT"
        ui_print "  ! dicoba lagi otomatis setelah reboot" ;;
    esac
  else
    ui_print "  ! gagal menyalin APK, dicoba lagi setelah reboot"
  fi
fi

unset APK_SRC APK_TMP APK_OUT
