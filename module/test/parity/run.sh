#!/bin/sh
D="$(cd "$(dirname "$0")" && pwd)"
R="$D/../../.."
SRC="$D/../../zygote/src/main/java/io/gpsz/zygote"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cp -r "$D/../beat/stubs/." "$W/"
cp -r "$D/stubs/." "$W/"
cp "$SRC/Config.java" "$SRC/Fix.java" "$SRC/LocationNative.java" "$D/ParityDump.java" "$D/JniCheck.java" "$W/io/gpsz/zygote/"
JC=javac; command -v javac >/dev/null 2>&1 || JC="java -m jdk.compiler/com.sun.tools.javac.Main"
( cd "$W" && $JC -d out $(find . -name '*.java') ) || exit 1
java -cp "$W/out" io.gpsz.zygote.ParityDump > "$W/java.txt" || exit 1
cargo run --release -q --manifest-path "$R/native/lochooks/Cargo.toml" --example parity -- "$W/java.txt" || exit 1
cargo build --release -q --manifest-path "$R/native/lochooks/Cargo.toml" || exit 1
LOCHOOKS_SO="$R/native/lochooks/target/release/liblochooks.so" java -cp "$W/out" io.gpsz.zygote.JniCheck
