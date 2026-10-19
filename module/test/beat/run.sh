#!/bin/sh
D="$(cd "$(dirname "$0")" && pwd)"
SRC="$D/../../zygote/src/main/java/io/gpsz/zygote/Config.java"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cp -r "$D/stubs/." "$W/"
cp "$SRC" "$W/io/gpsz/zygote/Config.java"
cp "$D/BeatTest.java" "$D/TrackTest.java" "$W/io/gpsz/zygote/"
( cd "$W" && javac -d out $(find . -name '*.java') ) || exit 1
java -cp "$W/out" io.gpsz.zygote.BeatTest && java -cp "$W/out" io.gpsz.zygote.TrackTest
