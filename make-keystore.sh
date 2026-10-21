#!/bin/sh
set -e
cd "$(dirname "$0")"
KEY="${1:?isi path release-key.pem}"
CERT="${2:?isi path release-cert.pem}"
OUT="${3:-$(pwd)/app/release.p12}"
ALIAS=setloc
printf "Password keystore (baru): "; stty -echo 2>/dev/null || true; IFS= read -r PASS; stty echo 2>/dev/null || true; echo
[ -n "$PASS" ] || { echo "password tidak boleh kosong" >&2; exit 1; }
KS_PASS="$PASS" openssl pkcs12 -export -inkey "$KEY" -in "$CERT" -name "$ALIAS" -out "$OUT" -passout env:KS_PASS
pe() { printf '%s' "$1" | sed 's/\\/\\\\/g'; }
pp() { printf '%s' "$1" | sed 's/\\/\\\\/g;s/^[[:blank:]]/\\&/'; }
cat > app/keystore.properties <<EOP
storeFile=$(pe "$OUT")
storePassword=$(pp "$PASS")
keyAlias=$ALIAS
keyPassword=$(pp "$PASS")
EOP
chmod 600 app/keystore.properties "$OUT"
echo "OK: $OUT dan app/keystore.properties dibuat (jangan di-commit)."
echo "SHA-1 untuk pembatasan Maps API key:"
openssl x509 -in "$CERT" -noout -fingerprint -sha1
