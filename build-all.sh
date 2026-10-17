#!/bin/sh
set -e
cd "$(dirname "$0")"

java_major() { "$1" -version 2>&1 | sed -n 's/^[a-z]* version "\([0-9][0-9]*\).*/\1/p' | head -n 1; }

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

with_jdk() { jh=$1; shift; JAVA_HOME=$jh PATH="$jh/bin:$PATH" "$@"; }

J17=$(find_jdk 17 "$JAVA17_HOME") || exit 1
J21=$(find_jdk 21 "$JAVA21_HOME") || exit 1

if [ -n "$MAPS_API_KEY" ]; then
  grep -q '^MAPS_API_KEY=' app/local.properties 2>/dev/null || echo "MAPS_API_KEY=$MAPS_API_KEY" >> app/local.properties
fi

echo "== 1/3 build app =="
TASKS="test assembleRelease"; [ -n "$SKIP_TESTS" ] && TASKS=assembleRelease
( cd app && with_jdk "$J17" sh ./gradlew $TASKS )
APK=$(ls -t app/app/build/outputs/apk/release/*.apk 2>/dev/null | head -n 1)
[ -n "$APK" ] || { echo "GAGAL: APK rilis tidak ditemukan"; exit 1; }
mkdir -p module/zygote/src/main/assets/apk
cp "$APK" module/zygote/src/main/assets/apk/SetLoc.apk

echo "== 2/3 siapkan AndroidVMTools =="
( cd module && sh setup.sh )

echo "== 3/3 build modul Zygisk =="
( cd module && with_jdk "$J21" sh ./gradlew :zygote:assembleRelease )
ls module/zygote/build/outputs/magisk/release/*.zip
