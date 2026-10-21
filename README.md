# SetLoc Zygisk

Versi Zygisk dari pemalsu lokasi. Pilih titik atau putar rute dari app SetLoc, dan lokasi palsunya dipakai app lain.

## Syarat
- Android 13 ke atas (minSdk 33) dengan root dan Zygisk aktif
- Manajer root yang bisa memasang modul (Magisk, KernelSU, APatch, atau sejenisnya)

## Pasang
1. Flash zip modul lewat manajer root.
2. Reboot.
3. Buka app SetLoc. Modul memasangnya sendiri saat di-flash, atau otomatis setelah reboot kalau Android belum berjalan penuh.

Memperbarui versi cukup flash zip yang baru. Daftar app dan pengaturan lama dipertahankan.

## Cara pakai
- Ketuk peta untuk memilih titik, lalu aktifkan. Favorit, pencarian nama tempat, dan pengaturan akurasi, posisi acak, serta ketinggian ada di app.
- Tekan lama di peta untuk menambah titik rute, lalu putar. Rute tetap berjalan walau app dibekukan atau ditutup, karena diputar oleh daemon root.
- WebUI modul bisa dibuka dari manajer root untuk mengubah titik dan daftar app.

Mode spoof:

| Mode | Efek |
|---|---|
| Per-app | Hanya app di daftar yang mendapat lokasi palsu |
| System-wide | Semua app mendapat lokasi palsu |
| Heartbeat | Sistem mengulang lokasi asli tiap 1 detik agar app yang menunggu callback (mis. Google Maps) tetap menerima pembaruan. App di daftar tetap dipalsukan, app lain tidak |

System-wide dan Heartbeat butuh reboot kalau belum aktif. Kalau terjadi bootloop, buat file `disable` di folder modul dari recovery.

Saat memutar rute, daemon menulis jendela 24 detik (`gpsz_trk`) dan hook menginterpolasi posisi sendiri, jadi Settings hampir tidak ditulis selama rute berjalan. Kalau ada masalah, buat file `disable_trk` di folder modul untuk kembali ke mode lama (satu tulis per tick).

## Catatan deteksi
- Hook getter per-app bisa dilewati kalau ART meng-inline `getLatitude` dan sejenisnya di kode app yang sudah dikompilasi. Hook `Location.CREATOR` menulis nilai palsu ke field objek saat objek dibuat, jadi pembacaan inline pun mendapat nilai palsu, tetapi ini mitigasi, bukan jaminan. Andalkan mode System-wide atau Heartbeat sebagai jalur utama.
- Key `Settings.Global` modul (`gpsz_*`) disembunyikan di app yang disuntik (kecuali app SetLoc) dari `Settings.Global.getString` dan sejenisnya, `ContentResolver.query/call`, dan `ContentProviderClient.query/call`. Notifikasi `ContentObserver` untuk URI key `gpsz_*` dibuang, dan `ProcessBuilder.start` yang perintahnya memuat `gpsz_` atau memanggil `settings`/`content` ditolak dengan IOException.
- Di mode Per-app dan System-wide, callback GNSS status, NMEA, pengukuran GNSS, dan info antena tetap terdaftar dan menerima data satelit palsu yang konsisten dengan lokasi palsu: `onStarted`, `onFirstFix`, lalu `onSatelliteStatusChanged`, kalimat NMEA, dan `GnssMeasurementsEvent` tiap detik. Navigation message dan batching tetap ditolak. Google Play Services dan komponen sistem tetap lolos.
- Log modul hanya ditulis di proses `android`, tidak di proses app.

## Kalau tidak jalan
```
sh /data/adb/modules/setloc_zygisk/gpsz.sh doctor
```
Perintah ini mendiagnosa modul, hook, dan konfigurasi. Untuk status mode system: `gpsz.sh system status`.

## CLI (root)
```
sh /data/adb/modules/setloc_zygisk/gpsz.sh set <lat> <lng>
sh /data/adb/modules/setloc_zygisk/gpsz.sh on|off|toggle|show
sh /data/adb/modules/setloc_zygisk/gpsz.sh system on|heartbeat|off|status
sh /data/adb/modules/setloc_zygisk/gpsz.sh pkg add|rm|list <paket>
sh /data/adb/modules/setloc_zygisk/gpsz.sh pkg all on|off
```
`pkg all on` menyuntikkan modul ke semua app. Pakai hati-hati.

## Build
Butuh satu JDK 21 (dipakai app dan modul), `git`, dan koneksi internet (AndroidVMTools di-clone dan dependensi Gradle diunduh).

```
MAPS_API_KEY=... sh build-all.sh
```

Hasilnya `module/zygote/build/outputs/magisk/release/SetLocZygisk-release.zip`.

- `MAPS_API_KEY` adalah API key Google Maps untuk peta di app.
- `SKIP_TESTS=1` melewati unit test app.
- `JAVA21_HOME` menunjuk JDK 21 tertentu kalau ada lebih dari satu JDK.
- Tanpa `app/keystore.properties`, APK ditandatangani dengan debug key. Untuk rilis, buat keystore dengan `sh make-keystore.sh <release-key.pem> <release-cert.pem>`.

## Struktur
- `app/` app Android (peta, favorit, rute, pengaturan)
- `module/` modul Zygisk: hook Java, daemon `bridge.sh`, CLI `gpsz.sh`, WebUI
- `module/test/run.sh` uji sandbox untuk skrip dan `Config`

## Kredit
Terinspirasi dari GPS Setter. Hook Java tanpa native memakai [ZygoteLoader](https://github.com/aerath-stuff/ZygoteLoader) dan [AndroidVMTools](https://github.com/aerath-stuff/AndroidVMTools).
