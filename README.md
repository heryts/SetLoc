# SetLoc Zygisk

Pemalsu lokasi berbasis Zygisk: modul meng-hook `system_server` sehingga lokasi palsu berlaku untuk seluruh sistem, dan app SetLoc dipakai untuk memilih titik atau memutar rute.

## Struktur
- `app/` app Android (peta, favorit, rute, pengaturan)
- `module/` modul Zygisk (hook Java, `bridge.sh` daemon, `gpsz.sh` CLI, WebUI)
- `module/test/run.sh` uji sandbox: sintaks skrip, `bridge.sh`, `gpsz.sh`, dan `Config` (butuh JDK untuk tes Java)

## Build
```
MAPS_API_KEY=... sh build-all.sh
```
Butuh JDK 17 dan JDK 21. Hasilnya zip modul yang di-flash lewat manajer root. `SKIP_TESTS=1` melewati unit test app. `make-keystore.sh` membuat keystore rilis, `update.sh` menyalin source dari zip ke repo.

## CLI (root)
```
sh /data/adb/modules/setloc_zygisk/gpsz.sh doctor     # diagnosa lengkap
sh /data/adb/modules/setloc_zygisk/gpsz.sh set <lat> <lng>
sh /data/adb/modules/setloc_zygisk/gpsz.sh on|off|toggle|show
sh /data/adb/modules/setloc_zygisk/gpsz.sh system on|heartbeat|off|status
sh /data/adb/modules/setloc_zygisk/gpsz.sh pkg add|rm|list|all <paket>
```
