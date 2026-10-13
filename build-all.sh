#!/bin/sh
# Build app SetLoc -> taruh ke modul -> build zip modul Zygisk.
# Syarat: JDK 21 (modul) / JDK 17 (app), Android SDK 36 + build-tools 33.0.0 + platform 33, git, jaringan.
# Peta Google: export MAPS_API_KEY=xxxx   (atau isi app/local.properties: MAPS_API_KEY=xxxx)
set -e
cd "$(dirname "$0")"

if [ -n "$MAPS_API_KEY" ]; then
  grep -q '^MAPS_API_KEY=' app/local.properties 2>/dev/null || echo "MAPS_API_KEY=$MAPS_API_KEY" >> app/local.properties
fi

echo "== 1/3 build app =="
( cd app && sh ./gradlew assembleRelease )
APK=$(ls app/app/build/outputs/apk/release/*.apk | head -1)
mkdir -p module/zygote/src/main/assets/apk
cp "$APK" module/zygote/src/main/assets/apk/SetLoc.apk
echo "APK dimasukkan ke modul: $APK"

echo "== 2/3 siapkan AndroidVMTools =="
( cd module && sh setup.sh )

echo "== 3/3 build modul Zygisk =="
( cd module && sh ./gradlew :zygote:assembleRelease )
ls module/zygote/build/outputs/magisk/*/release/*.zip
