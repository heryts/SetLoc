Zygisk version of location spoofer

- Build: `sh build-all.sh` (lihat header skripnya untuk prasyarat).
- Versi: ubah `version.properties` saja; app dan modul membacanya.
- Uji skrip modul tanpa perangkat: `sh module/test/run.sh`.
- Uji unit app: `cd app && sh ./gradlew test`.
- Diagnosa di perangkat: `su -c "sh /data/adb/modules/setloc_zygisk/gpsz.sh doctor"`.
