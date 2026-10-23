case "${ARCH:-$(uname -m)}" in
  arm64|aarch64) ABI=arm64-v8a ;;
  x64|x86_64) ABI=x86_64 ;;
  arm|armv7*|armv8l) ABI=armeabi-v7a ;;
  x86|i686) ABI=x86 ;;
  *) ABI= ;;
esac
if [ -n "$ABI" ] && [ -f "$MODPATH/bin/$ABI/setlocd" ]; then
  cp "$MODPATH/bin/$ABI/setlocd" "$MODPATH/setlocd" && chmod 755 "$MODPATH/setlocd"
  ui_print "- Daemon setlocd ($ABI)"
else
  ui_print "  ! setlocd untuk ${ABI:-arsitektur ini} tidak ada di modul"
fi
rm -rf "$MODPATH/bin"

if [ -x "$MODPATH/setlocd" ]; then
  "$MODPATH/setlocd" modinstall "$MODPATH" "${OLD_MOD:-}" "${MODID:-}"
fi
unset ABI
