#!/bin/sh
# Uji JVM murni untuk Config.checkBeat (Android diganti stub). Butuh javac + java (JDK 11+).
D="$(cd "$(dirname "$0")" && pwd)"
SRC="$D/../../zygote/src/main/java/io/gpsz/zygote/Config.java"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cp -r "$D/stubs/." "$W/"
cp "$SRC" "$W/io/gpsz/zygote/Config.java"
cp "$D/BeatTest.java" "$W/io/gpsz/zygote/BeatTest.java"
( cd "$W" && javac -d out $(find . -name '*.java') ) || exit 1
java -cp "$W/out" io.gpsz.zygote.BeatTest
