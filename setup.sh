#!/bin/sh
# Shim kompatibilitas: alur lama memanggil `sh setup.sh`. Kerjanya sekarang di tools/xtask (Rust).
[ -f "$HOME/.cargo/env" ] && . "$HOME/.cargo/env"
PATH="$HOME/.cargo/bin:$PATH"; export PATH
cd "$(dirname "$0")" || exit 1
[ -f tools/xtask/Cargo.toml ] || { echo "GAGAL: tools/xtask belum ada, jalankan dulu: sh update.sh <zip source baru>" >&2; exit 1; }
exec cargo run --release -q --manifest-path tools/xtask/Cargo.toml -- setup
