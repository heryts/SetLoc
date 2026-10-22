#!/bin/sh
set -e
PIN=6fa2d5cbbf51c96fb81a9b5d96fdd74beac4aafe
D=external/AndroidVMTools
at_pin() { [ "$(git -C "$D" rev-parse HEAD 2>/dev/null)" = "$PIN" ]; }
complete() { at_pin && [ -f "$D/gradle/libs.versions.toml" ] && [ -d "$D/src/main/java" ]; }
if complete; then echo "ok (AndroidVMTools @ $PIN, sudah ada)"; exit 0; fi
if at_pin && git -C "$D" reset -q --hard "$PIN" 2>/dev/null && complete; then
  echo "ok (AndroidVMTools @ $PIN, dipulihkan)"; exit 0
fi
if [ ! -d "$D/.git" ]; then
  mkdir -p "$D"
  git -C "$D" init -q
  git -C "$D" remote add origin https://github.com/aerath-stuff/AndroidVMTools
fi
git -C "$D" fetch --depth 1 origin "$PIN"
git -C "$D" checkout -q -f FETCH_HEAD
complete || { echo "GAGAL: AndroidVMTools tidak lengkap di $D"; exit 1; }
echo "ok (AndroidVMTools @ $PIN)"
