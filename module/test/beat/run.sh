#!/bin/sh
D="$(cd "$(dirname "$0")" && pwd)"
SRC="$D/../../zygote/src/main/java/io/gpsz/zygote/Config.java"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cp -r "$D/stubs/." "$W/"
SALT=$(tr -d '\n' < "$D/../../../.salt")
ord() { printf '%d' "'$1"; }
tag() {
  h=2166136261; s="$SALT$1"
  while [ -n "$s" ]; do
    c=${s%"${s#?}"}; s=${s#?}
    h=$(( ((h ^ $(ord "$c")) * 16777619) & 2147483647 ))
  done
  printf '%06x' $(( h & 16777215 ))
}
enc() {
  p=$1; i=0; out=
  while [ -n "$p" ]; do
    c=${p%"${p#?}"}; p=${p#?}
    k=$(ord "$(printf '%s' "$SALT" | cut -c$(( i % 12 + 1 )))")
    out="$out$(printf '%02x' $(( ($(ord "$c") + k) % 256 )))"
    i=$((i+1))
  done
  printf '%s' "$out"
}
[ -n "$WIRE_HEX" ] || { WIRE_PLAIN=48213; WIRE_HEX=$(enc "$WIRE_PLAIN"); }
[ -n "$WIRE_HEX5" ] || { WIRE_PLAIN5=12345; WIRE_HEX5=$(enc "$WIRE_PLAIN5"); }
DAEMON_KEYS=
for l in cfg sys beat trk net bt; do DAEMON_KEYS="$DAEMON_KEYS$(tag $l),"; done
export WIRE_PLAIN WIRE_HEX WIRE_PLAIN5 WIRE_HEX5 DAEMON_KEYS
cp "$SRC" "$W/io/gpsz/zygote/Config.java"
cp "$D/BeatTest.java" "$D/TrackTest.java" "$D/ParseTest.java" "$D/FileFieldsTest.java" "$W/io/gpsz/zygote/"
JC=javac; command -v javac >/dev/null 2>&1 || JC="java -m jdk.compiler/com.sun.tools.javac.Main"
( cd "$W" && $JC -d out $(find . -name '*.java') ) || exit 1
java -cp "$W/out" io.gpsz.zygote.BeatTest && java -cp "$W/out" io.gpsz.zygote.TrackTest && java -cp "$W/out" io.gpsz.zygote.ParseTest && java -cp "$W/out" io.gpsz.zygote.FileFieldsTest
