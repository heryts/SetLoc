#!/bin/sh
# Menguji penyambungan Java -> Rust (Fase 8) tanpa cargo: jalur Java murni vs kelas native palsu.
D="$(cd "$(dirname "$0")" && pwd)"
SRC="$D/../../zygote/src/main/java/io/gpsz/zygote"
SALT=$(sed -n 's/.*SALT = "\(.*\)";/\1/p' "$SRC/Config.java")
JC=javac; command -v javac >/dev/null 2>&1 || JC="java -m jdk.compiler/com.sun.tools.javac.Main"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT

build() { # $1=nama pohon, $2=sumber ConfigNative/LocationNative
  W="$T/$1"; mkdir -p "$W"
  cp -r "$D/../beat/stubs/." "$W/"
  cp -r "$D/../parity/stubs/." "$W/"
  cp "$SRC/Config.java" "$SRC/Fix.java" "$D/../parity/ParityDump.java" "$D/WireDump.java" "$D/WireCheck.java" "$W/io/gpsz/zygote/"
  cp "$2/ConfigNative.java" "$2/LocationNative.java" "$W/io/gpsz/zygote/"
  ( cd "$W" && $JC -d out $(find . -name '*.java') ) || exit 1
}
build real "$SRC"
build fake "$D/fake"

run() { java -Dfake.salt="$SALT" $3 -cp "$T/$1/out" io.gpsz.zygote.$2 $4; }
FAIL=0

echo "== codec: jalur Java vs native palsu harus identik (termasuk hex ganjil/rusak, non-ASCII)"
run real WireDump > "$T/c_real.txt" || exit 1
run fake WireDump > "$T/c_fake.txt" || exit 1
if cmp -s "$T/c_real.txt" "$T/c_fake.txt"; then echo "  ok   $(wc -l < "$T/c_real.txt") baris sama"; else echo "  FAIL codec beda"; diff "$T/c_real.txt" "$T/c_fake.txt" | head; FAIL=1; fi

run fake WireDump "-Dfake.badseed=1" > "$T/c_bad.txt" || exit 1
if cmp -s "$T/c_real.txt" "$T/c_bad.txt"; then echo "  ok   salt tidak cocok: codec jatuh ke Java, hasil sama"; else echo "  FAIL salt tidak cocok tapi hasil codec beda"; diff "$T/c_real.txt" "$T/c_bad.txt" | head -5; FAIL=1; fi

echo "== Fix.apply dan snapshot: jalur Java vs native palsu (toleransi float)"
run real ParityDump | grep -E '^(fix|snap)\|' > "$T/p_real.txt" || exit 1
run fake ParityDump | grep -E '^(fix|snap)\|' > "$T/p_fake.txt" || exit 1
python3 - "$T/p_real.txt" "$T/p_fake.txt" <<'PY' || FAIL=1
import sys
a=open(sys.argv[1]).read().split('\n'); b=open(sys.argv[2]).read().split('\n')
a=[x for x in a if x]; b=[x for x in b if x]
if len(a)!=len(b) or not a: print("  FAIL jumlah baris", len(a), len(b)); sys.exit(1)
bad=0; worst=0.0
for x,y in zip(a,b):
    fx,fy=x.split('|'),y.split('|')
    if len(fx)!=len(fy): bad+=1; print("  FAIL kolom", x, y); continue
    for u,v in zip(fx,fy):
        if u==v: continue
        try:
            d=abs(float(u)-float(v)); worst=max(worst,d)
            if d>2e-5*max(1.0,abs(float(u))): bad+=1; print("  FAIL", x, "|", y); break
        except ValueError:
            bad+=1; print("  FAIL", x, "|", y); break
print("  %s %d baris, selisih terbesar %.3g" % ("ok  " if not bad else "FAIL", len(a), worst))
sys.exit(1 if bad else 0)
PY

echo "== mode java (kelas native asli, library tidak termuat)"
run real WireCheck "" java || FAIL=1
echo "== mode native (kelas palsu, salt cocok)"
run fake WireCheck "" native || FAIL=1
echo "== mode badseed (salt/seed tidak cocok: harus jatuh ke Java)"
run fake WireCheck "-Dfake.badseed=1" badseed || FAIL=1

[ $FAIL = 0 ] && echo "SEMUA LOLOS" || { echo "ADA YANG GAGAL"; exit 1; }
