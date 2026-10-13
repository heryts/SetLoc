#!/bin/sh
# Ubah release-key.pem + release-cert.pem jadi keystore PKCS12 untuk Gradle.
# Pakai: sh make-keystore.sh /path/release-key.pem /path/release-cert.pem [/path/output/release.p12]
# Simpan .p12 di luar folder project (atau biarkan default; *.p12 sudah di-ignore Git).
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
echo "OK: $OUT dan app/keystore.properties dibuat (keduanya jangan di-commit)."
echo "SHA-1 untuk pembatasan Maps API key:"
openssl x509 -in "$CERT" -noout -fingerprint -sha1
