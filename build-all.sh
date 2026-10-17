#!/bin/sh
# Build app -> taruh APK ke modul -> build zip modul Zygisk.
#
# Prasyarat (rincian di README.md, bagian "Build di lokal"):
#   - JDK 17 untuk app dan JDK 21 untuk modul, dicari otomatis (atau set JAVA17_HOME / JAVA21_HOME).
#     JANGAN pakai JDK 22 ke atas: Kotlin 1.9 gagal di KSP ("IllegalArgumentException: 26.0.2").
#   - Android SDK (ANDROID_HOME atau sdk.dir di app/local.properties): platform 34 (app) dan platform 37 (modul).
#   - git dan jaringan (dependency Gradle; AndroidVMTools diambil dari GitHub oleh module/setup.sh).
#   - Peta Google: export MAPS_API_KEY=xxxx (atau isi app/local.properties).
# Opsi: SKIP_TESTS=1 melewati unit test app.  sh build-all.sh --jdk-info  hanya menampilkan JDK yang akan dipakai.
set -e
cd "$(dirname "$0")"

# Versi mayor sebuah biner java ("17.0.20.1" -> 17). Baris "Picked up JAVA_TOOL_OPTIONS" (bila ada) diabaikan.
java_major() { "$1" -version 2>&1 | sed -n 's/^[a-z]* version "\([0-9][0-9]*\).*/\1/p' | head -n 1; }

# Cari JDK (bukan JRE) versi mayor $1. Urutan: variabel override $2 (JAVA17_HOME/JAVA21_HOME), JAVA_HOME, java di PATH,
# lalu lokasi umum. JDK_DIRS bisa diganti untuk lokasi lain. Cetak path JDK; gagal dengan pesan jelas bila tidak ada.
JDK_DIRS=${JDK_DIRS:-"/usr/lib/jvm/* $HOME/.sdkman/candidates/java/* /Library/Java/JavaVirtualMachines/*/Contents/Home"}
find_jdk() {
  want=$1; over=$2
  if [ -n "$over" ]; then
    if [ -x "$over/bin/javac" ] && [ "$(java_major "$over/bin/java")" = "$want" ]; then echo "$over"; return 0; fi
    echo "JAVA${want}_HOME=$over bukan JDK $want" >&2; return 1
  fi
  onpath=$(command -v java 2>/dev/null || true)
  [ -n "$onpath" ] && onpath=$(dirname "$(dirname "$(readlink -f "$onpath" 2>/dev/null || echo "$onpath")")")
  for d in "$JAVA_HOME" "$onpath" $JDK_DIRS; do
    if [ -n "$d" ] && [ -x "$d/bin/javac" ] && [ "$(java_major "$d/bin/java")" = "$want" ]; then echo "$d"; return 0; fi
  done
  echo "JDK $want tidak ditemukan. Pasang (mis. sudo apt install openjdk-$want-jdk) atau set JAVA${want}_HOME." >&2
  return 1
}

# Jalankan perintah dengan JDK tertentu saja (tidak mengubah JAVA_HOME shell pemanggil).
with_jdk() { jh=$1; shift; JAVA_HOME=$jh PATH="$jh/bin:$PATH" "$@"; }

echo "== 0/3 cek JDK =="
J17=$(find_jdk 17 "$JAVA17_HOME") || exit 1
J21=$(find_jdk 21 "$JAVA21_HOME") || exit 1
echo "app   : JDK 17 di $J17"
echo "modul : JDK 21 di $J21"
[ "$1" = "--jdk-info" ] && exit 0

if [ -n "$MAPS_API_KEY" ]; then
  grep -q '^MAPS_API_KEY=' app/local.properties 2>/dev/null || echo "MAPS_API_KEY=$MAPS_API_KEY" >> app/local.properties
fi

echo "== 1/3 build app =="
if [ -n "$SKIP_TESTS" ]; then APP_TASKS="assembleRelease"; else APP_TASKS="test assembleRelease"; fi
( cd app && with_jdk "$J17" sh ./gradlew $APP_TASKS )
APK=$(ls app/app/build/outputs/apk/release/*.apk | head -1)
mkdir -p module/zygote/src/main/assets/apk
cp "$APK" module/zygote/src/main/assets/apk/SetLoc.apk

echo "== 2/3 siapkan AndroidVMTools =="
( cd module && sh setup.sh )

echo "== 3/3 build modul Zygisk =="
( cd module && with_jdk "$J21" sh ./gradlew :zygote:assembleRelease )
ls module/zygote/build/outputs/magisk/release/*.zip
