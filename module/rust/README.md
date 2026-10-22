# Port Rust modul zygote

## gpsz-core (tahap 1)

Logika murni yang dulu ada di `module/zygote` (Java), tanpa JNI.

| Java | Rust |
|---|---|
| `Config` (parse, jitter, track, beat, file fallback, offset jaringan, ketinggian) | `state.rs`, `track.rs`, `rng.rs`, `codec.rs` |
| `Fix` (nilai lokasi, stempel waktu, last known) | `fix.rs` |
| `GnssFake` (satelit, NMEA, pengukuran) | `gnss.rs`, `nmea.rs` |
| `LocationHooks.EnvSim` (AP Wi-Fi palsu) | `wifi.rs` |

## gpsz-jni (tahap 2, uji muat)

Pustaka `libgpsz.so` (cdylib) yang mengekspor fungsi JNI dengan tipe primitif saja, tanpa `JNIEnv`: `probe`, `hashUnit`, `networkAccuracy`.

`io.gpsz.zygote.Native.load()` memuat `/data/adb/modules/setloc_zygisk/native/libgpsz.so` saat modul masuk ke proses, lalu mencocokkan hasilnya dengan implementasi Java. Hasil ditulis ke logcat tag `GpsZ` (`native ok` atau `native fail <sebab>`) di proses `android`. Di proses app log hanya muncul bila `setprop debug.gpsz.native 1` diset sebelum app dibuka.

Selama `Native.load()` belum terbukti `ok` di perangkat, `Config`, `Fix`, dan `GnssFake` Java tetap dipakai dan tidak diubah.

Uji: `cargo test` di folder ini. Salt dibaca dari `.salt` di akar proyek lewat `build.rs`.

Belum ada: loader Zygisk, engine hook ART, hook `LocationHooks`, `SystemHooks`, `PackageGuard`, `SettingsGuard`.
