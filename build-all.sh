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

find_ndk() {
  for d in "$ANDROID_NDK_HOME" "$ANDROID_NDK_ROOT"; do
    if [ -n "$d" ] && [ -d "$d/toolchains/llvm" ]; then echo "$d"; return 0; fi
  done
  sdk=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
  [ -n "$sdk" ] || sdk=$(sed -n 's/^sdk\.dir=//p' app/local.properties 2>/dev/null | head -n 1)
  d=$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -n 1)
  if [ -n "$sdk" ] && [ -n "$d" ] && [ -d "$d/toolchains/llvm" ]; then echo "$d"; return 0; fi
  echo "NDK Android tidak ditemukan. Pasang lewat SDK Manager atau set ANDROID_NDK_HOME." >&2
  return 1
}

build_daemon() {
  [ -d "$HOME/.cargo/bin" ] && PATH="$HOME/.cargo/bin:$PATH"
  command -v cargo >/dev/null 2>&1 || { echo "GAGAL: butuh cargo. Pasang: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y"; return 1; }
  ndk=$(find_ndk) || return 1
  case "$(uname -s)" in Darwin) host=darwin-x86_64 ;; *) host=linux-x86_64 ;; esac
  bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
  [ -z "$SKIP_TESTS" ] && { ( cd daemon && cargo test --release ) || return 1; }
  for t in ${RUST_TARGETS:-aarch64-linux-android}; do
    case "$t" in
      aarch64-linux-android) abi=arm64-v8a; cc=aarch64-linux-android29-clang ;;
      armv7-linux-androideabi) abi=armeabi-v7a; cc=armv7a-linux-androideabi29-clang ;;
      x86_64-linux-android) abi=x86_64; cc=x86_64-linux-android29-clang ;;
      i686-linux-android) abi=x86; cc=i686-linux-android29-clang ;;
      *) echo "GAGAL: target Rust tidak dikenal: $t"; return 1 ;;
    esac
    if command -v rustup >/dev/null 2>&1 && ! rustup target list --installed | grep -qx "$t"; then
      rustup target add "$t" || return 1
    fi
    up=$(echo "$t" | tr 'a-z-' 'A-Z_')
    ( cd daemon && env "CARGO_TARGET_${up}_LINKER=$bin/$cc" cargo build --release --target "$t" ) || return 1
    mkdir -p "module/zygote/src/main/assets/bin/$abi"
    cp "daemon/target/$t/release/setlocd" "module/zygote/src/main/assets/bin/$abi/setlocd"
  done
}

J21=$(find_jdk 21 "$JAVA21_HOME") || exit 1

if [ -f tools/RotateSalt.java ]; then
  ROT=; [ -n "$ROTATE_SALT" ] && ROT=--new
  "$J21/bin/java" tools/RotateSalt.java $ROT || { echo "GAGAL: RotateSalt"; exit 1; }
else
  echo "PERINGATAN: tools/RotateSalt.java tidak ada di $(pwd), salt tidak diputar (memakai salt yang sudah tertanam di source)"
fi

if [ -n "$MAPS_API_KEY" ]; then
  { grep -v '^MAPS_API_KEY=' app/local.properties 2>/dev/null || true; echo "MAPS_API_KEY=$MAPS_API_KEY"; } > app/local.properties.new
  mv -f app/local.properties.new app/local.properties
fi

SETUP_LOG=$(mktemp)
trap 'rm -f "$SETUP_LOG"' EXIT
( cd module && sh setup.sh ) >"$SETUP_LOG" 2>&1 &
SETUP_PID=$!

echo "== 1/4 build app =="
TASKS="testDebugUnitTest assembleRelease"; [ -n "$SKIP_TESTS" ] && TASKS=assembleRelease
( cd app && with_jdk "$J21" sh ./gradlew $GRADLE_ARGS $TASKS )
APK=$(ls -t app/app/build/outputs/apk/release/*.apk 2>/dev/null | head -n 1)
[ -n "$APK" ] || { echo "GAGAL: APK rilis tidak ditemukan"; exit 1; }
mkdir -p module/zygote/src/main/assets/apk
cp "$APK" module/zygote/src/main/assets/apk/SetLoc.apk

echo "== 2/4 build daemon Rust =="
build_daemon || { echo "GAGAL: daemon Rust"; exit 1; }

echo "== 3/4 siapkan AndroidVMTools =="
wait "$SETUP_PID" || { cat "$SETUP_LOG"; exit 1; }
cat "$SETUP_LOG"

echo "== 4/4 build modul Zygisk =="
( cd module && with_jdk "$J21" sh ./gradlew $GRADLE_ARGS --no-configuration-cache :zygote:assembleRelease )
ls module/zygote/build/outputs/magisk/release/*.zip
