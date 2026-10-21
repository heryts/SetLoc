#!/bin/sh
set -e
PIN=6fa2d5cbbf51c96fb81a9b5d96fdd74beac4aafe
D=external/AndroidVMTools
[ "$(git -C "$D" rev-parse HEAD 2>/dev/null)" = "$PIN" ] && { echo "ok (AndroidVMTools @ $PIN, sudah ada)"; exit 0; }
if [ ! -d "$D/.git" ]; then
  mkdir -p "$D"
  git -C "$D" init -q
  git -C "$D" remote add origin https://github.com/aerath-stuff/AndroidVMTools
fi
git -C "$D" fetch --depth 1 origin "$PIN"
git -C "$D" checkout -q FETCH_HEAD
echo "ok (AndroidVMTools @ $PIN)"
