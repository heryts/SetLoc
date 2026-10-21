#!/bin/sh
D="$(cd "$(dirname "$0")" && pwd)"
SRC="$D/../../zygote/src/main/java/io/gpsz/zygote/Config.java"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cp -r "$D/stubs/." "$W/"
sed -n '/^KV=/p;/^enc() {/,/^}/p' "$D/../../zygote/src/main/assets/bridge.sh" > "$W/enc.sh"
[ -n "$WIRE_HEX" ] || { WIRE_PLAIN=48213; WIRE_HEX=$( . "$W/enc.sh"; enc "$WIRE_PLAIN" ); }
[ -n "$WIRE_HEX5" ] || { WIRE_PLAIN5=12345; WIRE_HEX5=$( . "$W/enc.sh"; enc "$WIRE_PLAIN5" ); }
keys() { sed -n 's/^\(KEY\|SYSKEY\|BEATKEY\|TRKKEY\|NETKEY\|BTKEY\)=//p' "$1" | tr '\n' ','; }
BRIDGE_KEYS=$(keys "$D/../../zygote/src/main/assets/bridge.sh")
GPSZ_KEYS=$(keys "$D/../../zygote/src/main/assets/gpsz.sh")
export WIRE_PLAIN WIRE_HEX WIRE_PLAIN5 WIRE_HEX5 BRIDGE_KEYS GPSZ_KEYS
cp "$SRC" "$W/io/gpsz/zygote/Config.java"
cp "$D/BeatTest.java" "$D/TrackTest.java" "$D/ParseTest.java" "$D/FileFieldsTest.java" "$W/io/gpsz/zygote/"
JC=javac; command -v javac >/dev/null 2>&1 || JC="java -m jdk.compiler/com.sun.tools.javac.Main"
( cd "$W" && $JC -d out $(find . -name '*.java') ) || exit 1
java -cp "$W/out" io.gpsz.zygote.BeatTest && java -cp "$W/out" io.gpsz.zygote.TrackTest && java -cp "$W/out" io.gpsz.zygote.ParseTest && java -cp "$W/out" io.gpsz.zygote.FileFieldsTest
