#!/bin/sh
D="$(cd "$(dirname "$0")" && pwd)"
SRC="$D/../../zygote/src/main/java/io/gpsz/zygote"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/io/gpsz/zygote"
cp "$SRC/SysGuard.java" "$D/Check.java" "$W/io/gpsz/zygote/"
JC=javac; command -v javac >/dev/null 2>&1 || JC="java -m jdk.compiler/com.sun.tools.javac.Main"
( cd "$W" && $JC -d out $(find . -name '*.java') ) || exit 1
java -cp "$W/out" io.gpsz.zygote.Check
