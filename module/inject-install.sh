#!/bin/sh
# Pakai: sh inject-install.sh <zip modul> <40-install-apk.sh>
# Menyisipkan part 40 ke customize.sh di dalam zip bila belum ada (zip dari plugin sudah memuatnya).
set -e
Z=$(readlink -f "$1"); P=$(readlink -f "$2")
W=$(mktemp -d); cd "$W"
mkdir -p customize.d
cp "$P" customize.d/40-install-apk.sh
unzip -q -o "$Z" customize.sh
if grep -q '40-install-apk.sh' customize.sh; then echo "sudah ada di customize.sh, tidak diubah"; else
  L=$(grep -n '^\[ -f "\$MODPATH/customize.d/90-restore' customize.sh | cut -d: -f1)
  [ -n "$L" ] || { echo "baris 90-restore tidak ketemu"; exit 1; }
  case "$(sed -n "${L}p" customize.sh)" in
    *'\') echo "baris 90-restore menyambung ke baris berikutnya, tidak aman disisipi"; exit 1 ;;
  esac
  head -n "$L" customize.sh > c.new
  printf '%s\n' '[ -f "$MODPATH/customize.d/40-install-apk.sh" ] && . "$MODPATH/customize.d/40-install-apk.sh"' >> c.new
  tail -n +$((L+1)) customize.sh >> c.new
  mv c.new customize.sh
fi
zip -q "$Z" customize.sh customize.d/40-install-apk.sh
echo "ok: $Z"
