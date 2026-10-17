#!/bin/sh
set -e
for c in rsync unzip dos2unix; do command -v $c >/dev/null || { echo "GAGAL: butuh $c"; exit 1; }; done
ZIP=${1:?pakai: sh update.sh <zip-source-baru>}
REPO=${REPO:-$HOME/proj/SetLoc}
T=/tmp/setloc_new
rm -rf $T && mkdir $T && unzip -q "$ZIP" -d $T
Z=$(find $T -maxdepth 2 -type d -name setloc-zygisk-all | head -1)
[ -d "$Z/app" ] && [ -d "$Z/module" ] || { echo "GAGAL: bukan source SetLoc"; exit 1; }

cd "$REPO"
[ -f build-all.sh ] && [ -f version.properties ] && [ -d app ] && [ -d module ] || { echo "GAGAL: $REPO bukan repo SetLoc"; exit 1; }
[ -z "$CHECK_CLEAN" ] || [ -z "$(git status --porcelain app module 2>/dev/null)" ] \
  || { echo "GAGAL: CHECK_CLEAN=1 aktif dan ada perubahan belum di-commit di app/ atau module/"; exit 1; }
EX="--exclude=local.properties --exclude=keystore.properties --exclude=*.p12 --exclude=*.pem --exclude=*.jks --exclude=*.keystore --exclude=build/ --exclude=.gradle/ --exclude=external/ --exclude=.cxx/"
rsync -a --delete $EX "$Z/app/" app/
rsync -a --delete $EX --exclude=apk/ "$Z/module/" module/
cp "$Z/README.md" "$Z/build-all.sh" "$Z/make-keystore.sh" "$Z/.gitignore" "$Z/version.properties" .
cp "$Z/update.sh" update.sh.new && mv update.sh.new update.sh
find . -path ./module/external -prune -o \( -name '*.sh' -o -name gradlew \) -type f -print | xargs dos2unix -q
chmod +x app/gradlew

sh build-all.sh

VER=$(sed -n 's/^versionName=//p' version.properties)
OUT=module/zygote/build/outputs/magisk/release/SetLocZygisk-release.zip
[ -f "$OUT" ] || { echo "GAGAL: $OUT tidak ada"; exit 1; }
C=$(mktemp -d)
unzip -q "$OUT" -d "$C"
[ -f "$C/customize.d/40-install-apk.sh" ] && [ "$(grep -c 40-install-apk.sh "$C/customize.sh")" = 2 ] \
  && cmp -s "$C/apk/SetLoc.apk" "$(ls -t app/app/build/outputs/apk/release/*.apk | head -n 1)" || { echo "GAGAL: isi zip tidak sesuai"; exit 1; }
rm -rf "$C"
cp "$OUT" "$HOME/SetLocZygisk-v$VER.zip"
[ -d /mnt/c ] && cp "$OUT" "/mnt/c/SetLocZygisk-v$VER.zip"
echo "SELESAI: ~/SetLocZygisk-v$VER.zip"
