OLD_MOD=${OLD_MOD:-/data/adb/modules/${MODID:-setloc_zygisk}}
if [ "$OLD_MOD" != "$MODPATH" ] && [ -d "$OLD_MOD/packages" ]; then
  mkdir -p "$MODPATH/packages"
  if cp -a "$OLD_MOD/packages/." "$MODPATH/packages/" 2>/dev/null; then
    ui_print "- Konfigurasi lama dipertahankan"
  else
    ui_print "  ! gagal menyalin konfigurasi lama"
  fi
  for f in .hb_default disable_super disable_trk; do
    if [ -f "$OLD_MOD/$f" ]; then cp -a "$OLD_MOD/$f" "$MODPATH/$f"; fi
  done
fi

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

unset APK_OUT OLD_MOD f
