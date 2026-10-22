#!/bin/sh
D="$(cd "$(dirname "$0")" && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/io/gpsz/zygote" "$W/out"
cp "$D/../../zygote/src/main/java/io/gpsz/zygote/WeakIdentityMap.java" "$D/IdentityMapTest.java" "$W/io/gpsz/zygote/"
JC=javac; command -v javac >/dev/null 2>&1 || JC="java -m jdk.compiler/com.sun.tools.javac.Main"
( cd "$W" && $JC -Xlint:all -Werror -d out $(find . -name '*.java') ) || exit 1
java -cp "$W/out" io.gpsz.zygote.IdentityMapTest
