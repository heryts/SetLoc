ui_print "- Install app SetLoc"

if [ ! -f "$MODPATH/apk/SetLoc.apk" ]; then
  ui_print "  ! APK tidak ada di modul, dilewati"
elif ! command -v pm >/dev/null 2>&1 || [ "$(getprop sys.boot_completed)" != 1 ]; then
  ui_print "  ! Android belum berjalan penuh (recovery?), app dipasang otomatis setelah reboot"
elif APK_OUT=$(sh "$MODPATH/bridge.sh" install 2>&1); then
  md5sum "$MODPATH/apk/SetLoc.apk" 2>/dev/null | cut -d' ' -f1 > "$MODPATH/.apk_sum"
  cmd appops set com.heryts.setloc SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
  ui_print "  app SetLoc terpasang"
else
  ui_print "  ! pm install gagal: $APK_OUT"
  ui_print "  ! dicoba lagi otomatis setelah reboot"
fi

unset APK_OUT
