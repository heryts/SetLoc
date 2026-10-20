#!/bin/sh
D="$(cd "$(dirname "$0")" && pwd)"
M="$D/../.."
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
JC=javac; command -v javac >/dev/null 2>&1 || JC="java -m jdk.compiler/com.sun.tools.javac.Main"
mkdir -p "$W/stub" "$W/out" "$W/test"
$JC -Xlint:all -Werror --limit-modules java.base -d "$W/stub" $(find "$M/stub/src" -name '*.java') || exit 1
$JC -Xlint:all -Werror --limit-modules java.base -cp "$W/stub" -d "$W/out" $(find "$M/unsafe/src" -name '*.java') || exit 1
$JC -Xlint:all -Werror --limit-modules java.base -cp "$W/out" -d "$W/test" "$D/UnsafeTest.java" || exit 1
java -cp "$W/out:$W/test" com.v7878.sun.unsafe.UnsafeTest "$D/api.txt"
