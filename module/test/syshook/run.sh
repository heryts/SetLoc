#!/bin/sh
D="$(cd "$(dirname "$0")" && pwd)"
SRC="$D/../../zygote/src/main/java/io/gpsz/zygote"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cp -r "$D/../beat/stubs/." "$W/"
cp -r "$D/../lochook/stubs/." "$W/"
rm -f "$W/io/gpsz/zygote/LegacyFrame.java" "$W/io/gpsz/zygote/LsNative.java" "$W/io/gpsz/zygote/ZygoteEntry.java" \
      "$W/io/gpsz/zygote/PackageGuard.java" "$W/io/gpsz/zygote/SettingsGuard.java"
cp -r "$D/stubs/." "$W/"
cp "$SRC/Frames.java" "$SRC/LsHook.java" "$SRC/SystemHooks.java" "$SRC/GnssFake.java" \
   "$SRC/Config.java" "$SRC/ConfigNative.java" "$SRC/LocationNative.java" "$SRC/Fix.java" "$SRC/WeakIdentityMap.java" "$D/Check.java" "$W/io/gpsz/zygote/"
JC=javac; command -v javac >/dev/null 2>&1 || JC="java -m jdk.compiler/com.sun.tools.javac.Main"
( cd "$W" && $JC -d out $(find . -name '*.java') ) || exit 1
rc=0
for mode in ls off notready partial; do
  echo "<< $mode >>"
  java -cp "$W/out" io.gpsz.zygote.Check "$mode" || rc=1
done
exit $rc
