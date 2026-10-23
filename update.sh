#!/bin/sh
# Shim kompatibilitas untuk rilis.sh lama. Kerjanya sekarang di tools/xtask (Rust).
#   sh update.sh <zip-atau-folder-source>   salin source ke repo ini, lalu build dan periksa zip
#   sh update.sh                            build repo ini apa adanya
# Memakai xtask dari source baru (bukan dari repo), jadi jalan juga di repo lama yang belum punya tools/xtask.
[ -f "$HOME/.cargo/env" ] && . "$HOME/.cargo/env"
PATH="$HOME/.cargo/bin:$PATH"; export PATH
command -v cargo >/dev/null 2>&1 || { echo "GAGAL: cargo tidak ditemukan (pasang lewat https://rustup.rs)" >&2; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
if [ -z "$1" ]; then
  [ -f "$HERE/tools/xtask/Cargo.toml" ] || { echo "GAGAL: tools/xtask belum ada, jalankan dengan zip source baru" >&2; exit 1; }
  cd "$HERE" && exec cargo run --release -q --manifest-path tools/xtask/Cargo.toml -- build
fi
A="$1"
[ -e "$A" ] || { [ -e "$A.zip" ] && A="$A.zip"; }
[ -e "$A" ] || { echo "GAGAL: $1 tidak ada" >&2; exit 1; }
SRC=$(cd "$(dirname "$A")" && pwd)/$(basename "$A")
T=$(mktemp -d) || exit 1
trap 'rm -rf "$T"' EXIT INT TERM
if [ -d "$SRC" ]; then
  R=$(find "$SRC" -maxdepth 3 -type d -name setloc-zygisk-all | head -1); [ -n "$R" ] || R="$SRC"
  cp -r "$R/tools" "$T/tools"
else
  command -v unzip >/dev/null 2>&1 || { echo "GAGAL: butuh unzip" >&2; exit 1; }
  unzip -q "$SRC" '*/tools/*' -d "$T/x" || { echo "GAGAL: unzip $SRC" >&2; exit 1; }
  R=$(find "$T/x" -maxdepth 3 -type d -name setloc-zygisk-all | head -1)
  [ -n "$R" ] || { echo "GAGAL: bukan source SetLoc" >&2; exit 1; }
  cp -r "$R/tools" "$T/tools"
fi
[ -f "$T/tools/xtask/Cargo.toml" ] || { echo "GAGAL: tools/xtask tidak ada di $SRC" >&2; exit 1; }
rm -rf "$T/x"
cd "$HERE" || exit 1
REPO="${REPO:-$HERE}" CARGO_TARGET_DIR="$T/target" cargo run --release -q --manifest-path "$T/tools/xtask/Cargo.toml" -- update "$SRC"
