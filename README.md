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

System-wide dan Heartbeat butuh reboot kalau belum aktif. Kalau terjadi bootloop, buat file `disable` di folder modul dari recovery. Untuk mematikan semua hook LSPlant di app dan `system_server` (tidak ada jalur cadangan lagi, jadi modul praktis tidak memalsukan apa pun), buat file `disable_ls` di folder modul. Untuk mematikan hook `LocationHooks` saja (penyembunyian paket dan `SettingsGuard` tetap jalan), buat file `disable_ls_loc`, dan untuk `SettingsGuard` saja `disable_ls_set`. Untuk mematikan library Rust (`configcore`, `lochooks`) dan kembali ke hitungan Java, buat file `disable_rs`. `SystemHooks` di `system_server` tidak dipasang kecuali Anda memilihnya sendiri: `gpsz.sh ls-system on`, lalu reboot (lihat `native/README.md`, Fase 7).

Saat memutar rute, daemon menulis jendela 24 detik (`gpsz_trk`) dan hook menginterpolasi posisi sendiri, jadi Settings hampir tidak ditulis selama rute berjalan. Kalau ada masalah, buat file `disable_trk` di folder modul untuk kembali ke mode lama (satu tulis per tick).

## Catatan deteksi
- Hook getter per-app bisa dilewati kalau ART meng-inline `getLatitude` dan sejenisnya di kode app yang sudah dikompilasi. Hook `Location.CREATOR`, `Location.set` dan pengiriman callback menulis nilai palsu ke field objek saat objek dibuat atau disalin, jadi pembacaan inline pun mendapat nilai palsu, tetapi ini mitigasi, bukan jaminan. Andalkan mode System-wide atau Heartbeat sebagai jalur utama.
- Paket `com.heryts.setloc` disembunyikan di app yang disuntik dari `PackageManager`: `getPackageInfo`, `getApplicationInfo`, `getActivityInfo`, `getServiceInfo`, `getReceiverInfo`, `getProviderInfo`, `getInstallSourceInfo`, `getComponentEnabledSetting`, `checkSignatures`, `checkPermission`, daftar paket terpasang, `queryIntent*`, dan `getPackagesForUid`. `File.exists`, `isDirectory`, `isFile`, `canRead`, `lastModified`, dan `length` untuk path yang memuat nama paket itu juga dijawab seolah tidak ada. Pemeriksaan lewat kode native atau Binder langsung tidak tercakup.
- Key `Settings.Global` modul (`gpsz_*`) disembunyikan di app yang disuntik (kecuali app SetLoc) dari `Settings.Global.getString` dan sejenisnya, `ContentResolver.query/call`, `ContentProviderClient.query/call`, dan `ContentProviderProxy.query/call` (akses langsung `IContentProvider`). Notifikasi `ContentObserver` untuk URI key `gpsz_*` dibuang, dan semua overload `ProcessBuilder.start` yang perintahnya memuat `gpsz_`, memanggil `settings`/`content`, atau menjalankan `cmd`/`dumpsys` dengan argumen `settings`/`content` (termasuk argumen terpisah) ditolak dengan IOException.
- Di mode Per-app dan System-wide, callback GNSS status, NMEA, pengukuran GNSS, dan info antena tetap terdaftar dan menerima data satelit palsu yang konsisten dengan lokasi palsu: `onStarted`, `onFirstFix`, lalu `onSatelliteStatusChanged`, kalimat NMEA, dan `GnssMeasurementsEvent` tiap detik. `GpsStatus.Listener` lama dan `getGpsStatus` ikut dilayani. Saat spoof berhenti, callback status, NMEA, dan pengukuran didaftarkan ulang ke sistem asli supaya app tidak kehilangan data. Navigation message dan batching tetap ditolak. Google Play Services dan komponen sistem tetap lolos.
- Log modul hanya ditulis di proses `android`, tidak di proses app.
- `SALT`, `KV`, dan nama key `Settings.Global` dibuat acak per build oleh `tools/rotate-salt` (Rust, disimpan di `.salt`), bukan konstanta yang sama untuk semua pemasang. `ROTATE_SALT=1 cargo xtask build` membuat salt baru. App dan modul harus dibangun dari salt yang sama. Key lama dihapus otomatis oleh daemon saat modul baru berjalan.
- Satelit GNSS palsu, jumlah satelit, dan offset lokasi network ditentukan dari seed salt dan `elapsedRealtime`, jadi semua proses melihat data yang sama pada saat yang sama. Hal yang sama berlaku untuk offset `random`, jitter akurasi, dan variasi ketinggian.
- Saat `random` mati, posisi tetap diberi derau halus di bawah 1 meter supaya tidak identik dari pembacaan ke pembacaan.

## Batas yang tidak bisa ditutup dari hook Java
- Mode Per-app tidak menangkap app yang membaca lokasi lewat NDK atau Binder langsung. Mode System-wide atau Heartbeat tetap jalur yang andal.
- Getter yang di-inline ART hanya tertutup kalau nilai palsu sudah tertulis di objek saat dibuat. Objek yang dibuat sebelum spoof aktif tetap asli.
- Deteksi lewat IP, zona waktu, atau menara seluler di luar hook lokasi tidak tersentuh.

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
Butuh satu JDK 21 (dipakai app dan modul), Rust (`cargo`), Android NDK, `git`, dan koneksi internet (dependensi Gradle diunduh). Semua langkah build dijalankan oleh `tools/xtask` (Rust), bukan skrip shell.

```
MAPS_API_KEY=... cargo xtask build
```

Hasilnya `module/zygote/build/outputs/magisk/release/SetLocZygisk-release.zip`.

- `MAPS_API_KEY` adalah API key Google Maps untuk peta di app.
- `SKIP_TESTS=1` melewati unit test app dan daemon.
- `ROTATE_SALT=1` membuat salt baru.
- `JAVA21_HOME` menunjuk JDK 21 tertentu kalau ada lebih dari satu JDK.
- `RUST_TARGETS` daftar target daemon (default `aarch64-linux-android`).
- `REPO=~/proj/SetLoc cargo xtask update <zip-source-baru>` menyalin source baru ke repo lalu build dan memeriksa isi zip.
- Tanpa `app/keystore.properties`, APK ditandatangani dengan debug key. Untuk rilis, buat keystore dengan `cargo xtask keystore <release-key.pem> <release-cert.pem>`.

## Struktur
- `app/` app Android (peta, favorit, rute, pengaturan)
- `module/` modul Zygisk: hook Java, stub skrip Magisk, WebUI
- `daemon/` `setlocd` (Rust): daemon, CLI `gpsz`, installer modul (`modinstall`)
- `tools/` `xtask` (build/update) dan `rotate-salt`, keduanya Rust
- `cargo xtask test [bagian...]` uji sandbox untuk skrip, daemon, `Config`, dan hook

## Kredit
Terinspirasi dari GPS Setter. Pemuatan modul memakai [ZygoteLoader](https://github.com/aerath-stuff/ZygoteLoader). Semua hook Java dan penyembunyian paket memakai [LSPlant](https://github.com/LSPosed/LSPlant) dan [ShadowHook](https://github.com/bytedance/android-inline-hook).
