#!/bin/sh
# Ubah release-key.pem + release-cert.pem jadi keystore PKCS12 untuk Gradle.
# Pakai: sh make-keystore.sh <release-key.pem> <release-cert.pem> [output.p12]
set -e
cd "$(dirname "$0")"
KEY="${1:?isi path release-key.pem}"
CERT="${2:?isi path release-cert.pem}"
OUT="${3:-$(pwd)/app/release.p12}"
ALIAS=setloc
printf "Password keystore (baru): "; stty -echo 2>/dev/null || true; read PASS; stty echo 2>/dev/null || true; echo
openssl pkcs12 -export -inkey "$KEY" -in "$CERT" -name "$ALIAS" -out "$OUT" -passout "pass:$PASS"
cat > app/keystore.properties <<EOP
storeFile=$OUT
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOP
chmod 600 app/keystore.properties "$OUT"
echo "OK: $OUT dan app/keystore.properties dibuat (jangan di-commit)."
echo "SHA-1 untuk pembatasan Maps API key:"
openssl x509 -in "$CERT" -noout -fingerprint -sha1
