# native/

> Catatan: Fase 1-11 di bawah menggambarkan keadaan saat fase itu dikerjakan. Penyebutan "jalur lama", `Hooks.hook`, dan AndroidVMTools sudah usang sejak Fase 12 (lihat bagian terakhir).

Status: `configcore` dan `lochooks` sudah dikompilasi dan diuji di host (cargo test, paritas Java vs Rust, dan JNI lewat JVM). `hook/` (Fase 4) lolos cek sintaks terhadap header asli LSPlant dan ShadowHook, dan logika Java-nya diuji di JVM dengan mesin hook palsu. Fase 5 dan 6 memindahkan `LocationHooks` dan `SettingsGuard` ke jalur LSPlant, dan Fase 7 `SystemHooks` (opt-in, di `system_server`); semuanya diuji di JVM dengan mesin hook palsu yang sama. Fase 8 menyambungkan `ConfigNative` dan `LocationNative` ke `Config` dan `Fix`, dengan cadangan ke Java. Fase 9 menambah ekspor JNI `parse`, `parseTrack`, `applyTrack`, dan beat di `configcore` dan menyambungkannya ke `Config`, dengan cadangan ke Java. Fase 11 memindahkan bagian murni `GnssFake` (kumpulan satelit, snapshot, kalimat NMEA, angka measurements) ke `lochooks`, dengan cadangan ke Java. Belum ada yang dikompilasi untuk target Android atau diuji di perangkat.

## hook (Fase 4)
Target: pendaftaran hook ART lewat LSPlant, dipakai pertama untuk `PackageGuard`.
- `hook/hook.cpp`: `libgpsz_hook.so`. `JNI_OnLoad` menyalakan ShadowHook (mode unique) dan `lsplant::Init`; ekspor `LsNative.nativeReady` dan `LsNative.nativeHook`. Resolver simbol `libart.so` memakai `shadowhook_dlsym`. Resolver prefiks sengaja mengembalikan null (lihat batas di bawah).
- `hook/CMakeLists.txt`: dibangun Gradle (`externalNativeBuild` di `module/zygote`), dengan `org.lsposed.lsplant:lsplant-standalone` dan `com.bytedance.android:shadowhook` lewat prefab.
- `LsNative.java`: `ensureLoaded()` memuat `gpsz_hook`. `ZygoteEntry.premain()` memanggilnya, karena folder modul hanya bisa dibaca sebelum proses di-specialize. Dilewati di app SetLoc sendiri, di `system_server`, dan kalau ada file `disable_ls` di folder modul. `premain()` juga membaca `disable_ls_loc` (lihat Fase 5).
- `LsHook.java`: membungkus `Hook` LSPlant. Callback menerima `Object[]` (indeks 0 = `this`, sama seperti indeks `EmulatedStackFrame` di kode lama) dan memanggil method asli lewat backup. Nilai kembali primitif harus dikotak (`Boolean`, `Integer`, `Long`).
- `PackageGuardLs.java`: perilaku `PackageGuard` yang sama (daftar method, pesan exception, nilai tolak) di atas `LsHook`. `LocationHooks.install()` memanggilnya langsung. Kalau `LsNative` tidak siap, tidak ada hook (jalur lama sudah dibuang di Fase 12).
- Crate `pkgguard` dan `shim/` dihapus. LSPlant hanya punya API C++, jadi lapisan hook memang harus C++. Dua fungsi Rust-nya (`nativeIsOwn`, `nativeFilterStrings`) tidak pernah dipanggil dari Java dan lebih mahal lewat JNI daripada `String.equals`.

### Pengujian
`cargo xtask test lshook`: menjalankan `PackageGuardLs` dan `LsHook` asli dengan stub `android.content.pm.*`, `ApplicationPackageManager` palsu, dan `LsNative` palsu yang mengembalikan method asli sebagai backup. Memeriksa 21 perilaku (exception paket sendiri, filter daftar, `checkPermission`/`checkSignatures`, UID, `resolveActivity`, dan `File.exists`/`isDirectory`/`lastModified` untuk path berisi nama paket).

### Batas dan risiko yang belum teruji
- Cek sintaks `hook.cpp` memakai salinan sementara `shadowhook.h` dengan penjaga arsitektur dilonggarkan, karena header itu hanya mendefinisikan tipe CPU untuk arm dan arm64.
- Resolver prefiks null: LSPlant memakainya hanya sebagai cadangan untuk `DexFile_setTrusted`, `GetOptimizedCodeFor`, dan `GetMethodShorty`. Kalau simbol itu bernama dengan sufiks kompiler di perangkat tertentu, `Init` gagal dan jalur lama yang dipakai.
- `System.loadLibrary` dari dex yang dimuat ZygoteLoader bergantung pada jalur library yang ditambahkan `attachNativeLibs` dan pada namespace linker. Belum terbukti di perangkat.
- LSPlant (hook ART di `libart.so`) dan AndroidVMTools (patch entrypoint) hidup berdampingan di proses yang sama untuk `LocationHooks`. Interaksinya belum diuji.
- Race kecil: callback bisa terpanggil dari thread lain sebelum `backup` terisi; `LsHook` menunggu sebentar sampai terisi.
- Versi `lsplant-standalone` 6.4 dan `shadowhook` 2.0.0 dipilih dari tag repo; belum diverifikasi di Maven Central.

## LocationHooks di LSPlant (Fase 5)
Target: hook di `LocationHooks` (Location, LocationManager, AppOps, Wi-Fi, seluler, Bluetooth, `CREATOR`, `getCurrentLocation`) berjalan di LSPlant, tanpa mengubah logikanya.
- `Frames.java`: adaptor tipis. `Fr` (indeks 0 = `this`, 1.. = argumen, `Frames.RET` = nilai kembali), `Orig` (panggil method asli), `Tx` (transformer). `Frames.hook(Method, Tx)` mencoba LSPlant dulu (`LsHook`), lalu jatuh ke jalur lama per method kalau gagal. Nilai kembali dipaksa ke tipe method (`coerce`): `void` jadi null, primitif yang null atau salah tipe jadi nol/false, supaya unboxing di sisi native tidak melempar.
- `LegacyFrame.java`: (dihapus di Fase 12) dulu jalur lama `Hooks.hook` + `EmulatedStackFrame` di balik adaptor yang sama.
- `LocationHooks.java`: perubahannya mekanis. `frame.accessor().getReference(i)` jadi `frame.get(i)`, `setValue` jadi `set`, `Transformers.invokeExactNoChecks(original, frame)` jadi `original.invoke()`, `Hooks.hook(...)` jadi `Frames.hook(...)`. Isi transformer, urutan cabang, dan `PER_OBJ`/`PARSING` tidak berubah.
- `disable_ls_loc` di folder modul: `LocationHooks` tidak dipasang sama sekali (sejak Fase 12 tanpa jalur lama), `PackageGuardLs` tetap LSPlant. `disable_ls` mematikan keduanya.
- `LsHook`: dua perbaikan dari Fase 4. `backup` sekarang diisi sebelum `setAccessible` (sebelumnya kalau `setAccessible` melempar, hook sudah terpasang tetapi callback menunggu selamanya), dan penantian `backup` dibatasi 2 detik lalu melempar `IllegalStateException`, bukan spin tanpa batas.

### Pengujian
`cargo xtask test lochook`: menjalankan `LocationHooks`, `Config`, `Fix`, dan `GnssFake` asli dengan stub Android dan `LsNative` palsu (55 pemeriksaan). Mencakup: 51 method terpasang lewat LSPlant pada stub uji, getter Location menghasilkan posisi palsu dengan tipe benar, `isLocationEnabled`/`isProviderEnabled`/`getProviders`, `getLastKnownLocation` (termasuk `SecurityException` dari method asli), AppOps, Wi-Fi/seluler/Bluetooth (termasuk penggantian argumen sebelum method asli), `createFromParcel`, `Location.set`, `getCurrentLocation`, serta semua jalur cadangan (`disable_ls_loc`, LSPlant tidak siap, satu method gagal, jalur lama gagal). Uji mutasi pada `coerce` dan penulisan argumen membuat tes gagal, jadi tesnya memang menangkap kesalahan itu.

### Batas dan risiko yang belum teruji
- Tes memakai `LsNative` palsu yang memanggil method asli langsung. Di perangkat, getter Location yang dipanggil dari dalam transformer (misalnya `getElapsedRealtimeNanos` di `fixSnap`) juga lewat hook; perlindungannya sama seperti jalur lama (`PARSING` dan `Mark`) tetapi belum terbukti di ART.
- Biaya per panggilan naik: tiap `getLatitude` dkk. mengalokasikan `Object[]`, mengotak nilai, dan memanggil method asli lewat `Method.invoke`. Aplikasi yang membaca lokasi ribuan kali per detik akan lebih lambat dibanding jalur lama.
- Sampai Fase 5, `SettingsGuard` masih di jalur lama sehingga LSPlant dan AndroidVMTools berjalan bersamaan; itu sudah diselesaikan di Fase 6.
- `AppOpsManager.unsafeCheckOp(String, int, String)`: indeks `op` dihitung dari parameter `int` pertama, jadi untuk overload ini yang diperiksa adalah `uid`, bukan `op`. Perilaku ini sudah ada sebelum Fase 5 dan sengaja tidak diubah.
- `SystemHooks` (di `system_server`) tidak ikut di fase ini; lihat Fase 7.

## SettingsGuard di LSPlant (Fase 6)
Target: 14 hook di `SettingsGuard` (kunci modul di `Settings.NameValueCache`, `ContentResolver`/`ContentProviderClient`/`ContentProviderProxy` query dan call, `ContentObserver.dispatchChange`, `registerContentObserver`, `ProcessBuilder.start`, `Settings.Secure.location_mode`) memakai adaptor `Frames` yang sama seperti Fase 5. Perubahan di `SettingsGuard.java` mekanis (`frame.accessor().getReference(i)` jadi `frame.get(i)`, dan seterusnya); logika dan urutan cabang tidak berubah.
- `disable_ls_set` di folder modul: `SettingsGuard` tidak dipasang sama sekali (sejak Fase 12 tanpa jalur lama); `disable_ls_loc` tidak memengaruhinya.
- Method statis (`Settings.Secure.getInt/getString`): LSPlant tidak mengirim `this`, jadi `args[0]` adalah parameter pertama. Indeks di `SettingsGuard` (`1` = kunci) sudah cocok dengan itu, sama seperti pada `EmulatedStackFrame`.
- Setelah Fase 5 dan 6, `LocationHooks` dan `SettingsGuard` tidak lagi memakai AndroidVMTools kalau LSPlant siap. `SystemHooks` (di `system_server`) dan `GnssFake` tidak memakai hook ART. Jalur lama baru dimuat bila LSPlant tidak siap atau salah satu flag `disable_*` ada.

### Pengujian
`cargo xtask test setguard`: menjalankan `SettingsGuard` dan `Config` asli dengan stub (`Settings`, `ContentResolver`, `Cursor`, `Bundle`, dan lainnya) serta `LsNative` palsu: kunci di `NameValueCache` dan prefix, query (baris disaring, URI kunci jadi kursor kosong, selection berisi kunci, `Config.READING` melewatkan), call (`GET_`/`PUT_` kosong, `LIST_` dibersihkan, `ContentProviderProxy`), observer (argumen koleksi diganti sebelum method asli), `ProcessBuilder.start` (ditolak dengan `error=13`), `location_mode` untuk method statis, dan jalur cadangan.

### Batas yang belum teruji / diketahui
- `ContentProviderClient.call(method, arg, extras)` tidak punya argumen URI, jadi tidak pernah dianggap settings. Perilaku ini sama dengan jalur lama dan sengaja tidak diubah; tesnya mencatatnya.
- Pemanggilan `Settings.NameValueCache.getStringForUser` sangat sering. Overhead alokasi/refleksi LSPlant per panggilan lebih besar daripada jalur lama; belum diukur di perangkat.
- `ProcessBuilder.start` adalah method inti libcore; hook LSPlant di sana (di proses aplikasi) belum diuji di ART.

## SystemHooks di LSPlant (Fase 7)
Target: 10 hook di `system_server` (`LocationProviderManager.onReportLocation`; di `LocationManagerService`: `getLastLocation`, `injectLocation`, daftar/hapus pendengar GNSS, blokir batching dan navigation message) memakai adaptor `Frames` yang sama. Perubahan di `SystemHooks.java` mekanis (`frame.accessor().getReference(i)` jadi `frame.get(i)`, `setValue(RETURN_VALUE_IDX, x)` jadi `set(Frames.RET, x)`, `invokeExactNoChecks` jadi `original.invoke()`); logika dan urutan cabang tidak berubah. Pemasangan lewat `Frames.hook(m, LsNative.sysEnabled(), tx)`, jadi tiap method yang gagal lewat LSPlant jatuh ke jalur lama sendiri.

Default MATI. Tanpa file `enable_ls_system`, `system_server` berjalan persis seperti sebelum fase ini (AndroidVMTools, LSPlant tidak dimuat).

### Pengaman boot
Semua penanda ada di folder modul. Penanda ditulis oleh `post-fs-data.sh` (root, sebelum zygote), bukan oleh `system_server`, karena proses yang di-fork dari zygote tidak boleh menulis di `/data/adb` (SELinux). `premain()` hanya membaca lewat `SysGuard.arm`:
1. `post-fs-data.sh`: tanpa `enable_ls_system` tidak melakukan apa pun; `ls_system_blocked` ada: tidak melakukan apa pun; `.ls_sys_pending` ada (percobaan sebelumnya tidak pernah dikonfirmasi): membuat `ls_system_blocked` dan menghapus pending; selain itu membuat `.ls_sys_pending`.
2. `SysGuard.arm`: tanpa `enable_ls_system` OFF; `ls_system_blocked` ada BLOCKED_NOW; `.ls_sys_pending` ada ARMED (LSPlant dimuat); selain itu FAILED (LSPlant tidak dimuat).
3. `premain()` mencatat `system hook premain arm=<0..3> lsplant=<bool> err=<pesan>` ke `logcat -s GpsZ`, dan `SystemHooks` mencatat `system hook lpm=.. lms=.. gagal=..` setiap kali hasilnya berubah.

Konfirmasi ada di daemon Rust (`daemon/src/lsguard.rs`, dijalankan dari `service.rs` hanya bila `enable_ls_system` ada): setelah `sys.boot_completed`, bila `.ls_sys_pending` ada dan PID `system_server` tidak berubah selama 60 detik, penanda dihapus. PID berubah (system_server mati dan dimulai ulang) menghitung ulang dari nol. Kalau `ls_system_blocked` ada, `.ls_sys_pending` dibuang tanpa menunggu.

Perintah: `gpsz.sh ls-system on|off|status|reset`. `on` menghapus blokir dan penanda lalu membuat `enable_ls_system` (perlu reboot); `off` menghapus ketiganya; `reset` hanya menghapus blokir dan penanda; `status` menampilkan `ls-system=off|on|pending|blocked`. `gpsz.sh doctor` juga mencetak barisnya. `enable_ls_system` sengaja tidak ikut dipertahankan saat pemasangan ulang modul (seperti `disable_ls*`).

### Pengujian
- `cargo xtask test syshook`: menjalankan `SystemHooks`, `Config`, `Fix`, dan `GnssFake` asli dengan kelas palsu `LocationManagerService`, `LocationProviderManager`, `LocationResult`, dan `LsNative` palsu, dalam empat proses terpisah (`ls`, `off`, `notready`, `partial`). Mode `ls` (10 hook lewat LSPlant, nol lewat jalur lama) memeriksa: semua diteruskan saat config mati; mode penuh mengganti hasil `onReportLocation` dan `getLastLocation`, mengganti argumen `injectLocation`, memblokir batching, memberi pendengar GNSS palsu (dan `android` tetap diteruskan), `SecurityException` dari method asli tetap sampai ke pemanggil; darurat dan heartbeat tidak mengganti apa pun. Mode `off` dan `notready`: 10 method ke jalur lama. Mode `partial`: satu method gagal di LSPlant dan hanya itu yang jatuh ke jalur lama. Uji mutasi (hasil tidak diganti, exception ditelan, flag `sysEnabled` selalu true, method asli selalu dipanggil) membuat tes gagal.
- `cargo xtask test sysguard`: urutan boot (tanpa opt-in, boot pertama, boot kedua tanpa dan dengan konfirmasi, reset, off) dan jalur gagal tertutup (penanda tidak bisa ditulis, jalur berupa folder, `File` null).
- `cargo test` di `daemon/`: 7 tes `lsguard` (pending hanya dihapus setelah PID sama selama 60 detik, PID berubah mengulang hitungan, `system_server` hilang tidak mengonfirmasi, blokir membuang pending, perintah `on|off|reset`).
Ketiganya dipanggil dari `cargo xtask test` (dua pertama) dan `cargo xtask build`.

### Batas dan risiko yang belum teruji
- Belum dikompilasi untuk Android dan belum dijalankan di perangkat, sama seperti fase 4 sampai 6. Hook lewat LSPlant di `system_server` belum terbukti di ART.
- Semua yang dibaca dan ditulis `premain()` (folder modul) bergantung pada izin SELinux proses yang sedang di-fork. Pembacaan sudah dipakai fase 4 sampai 6; penulisan `.ls_sys_pending` belum. Sejak 12m penulisan dipindah ke `post-fs-data.sh`; `premain()` hanya membaca.
- Pengaman hanya menutup crash dalam 60 detik pertama sesudah `boot_completed` dan sebelum itu. Crash `system_server` yang terjadi lebih lama sesudah dinyatakan stabil tidak memblokir apa pun.
- Reboot manual dalam 60 detik pertama sesudah boot selesai meninggalkan `.ls_sys_pending`, sehingga boot berikutnya memblokir (jalur lama). Pulihkan dengan `gpsz.sh ls-system reset`.
- LSPlant dan AndroidVMTools bisa hidup bersamaan di `system_server` hanya lewat jalur cadangan per method (mode `partial`).
- `System.loadLibrary("gpsz_hook")` dari `premain()` di `system_server` bergantung pada jalur library dan namespace linker, sama dengan fase 4 tetapi untuk proses lain.
- Overhead per panggilan `onReportLocation` naik (alokasi `Object[]` dan `Method.invoke`); belum diukur.

## Java memanggil Rust (Fase 8)
Target: `Config` dan `Fix` memakai `ConfigNative` dan `LocationNative` bila library Rust termuat, dan jalur Java lama tetap ada sebagai cadangan. Tidak ada perubahan perilaku yang diharapkan.

Yang disambungkan (satu panggilan JNI per pekerjaan yang cukup besar):
- `Fix.apply` -> `nativeApply` (gantinya `networkOffset`, `networkAccuracy`, `accNow`, `vAcc`, `sAcc`, `bAcc`, `gnssExtras` sekaligus). Hasil `double[13]` dibaca di `Fix.applyNative` dengan tata letak yang tertulis di bagian lochooks.
- `Fix.stamp` -> `nativeStamp`, `Fix.lastKnown` -> `nativeLastKnown`, `PHASE_NS` -> `nativePhaseNs`. Keadaan `Stamper` (stempel terakhir per provider) hidup di Rust bila jalur native dipakai, dan di `LAST_NS` Java bila tidak. Pilihan jalur ditetapkan sekali saat `Config` dimuat, jadi keduanya tidak tercampur.
- `Config.renewSnapshot` -> `nativeSnapshot` (jitter, altitude, clamp kutub, wrap bujur).
- `Config.tag/enc/dec` -> `nativeTag/nativeEnc/nativeDec`.

### Pengaman
- Pemuatan: `ZygoteEntry.premain()` memanggil `loadRust()` (sebelum specialize, sama seperti `libgpsz_hook.so`). Di `system_server` hanya bila `enable_ls_system` aktif dan `SysGuard` memberi `ARMED`; tanpa itu `system_server` berjalan persis seperti sebelumnya. File `disable_rs` di folder modul mematikan pemuatan library Rust (`disable_ls` tidak memengaruhinya, dan sebaliknya).
- Verifikasi saat `Config` dimuat: `CODEC_NATIVE` hanya aktif bila `nativeTag("cfg")`, `nativeEnc`, dan `nativeDec` memberi hasil yang sama dengan Java; `LOC_NATIVE` hanya aktif bila `nativePhaseNs` sama dengan hitungan Java (membuktikan seed turunan salt sama). Library yang dibangun sebelum salt diputar gagal di sini dan jalur Java dipakai, tanpa crash dan tanpa perubahan hasil.
- Setiap panggilan dibungkus `try/catch Throwable`; hasil `null` atau array pendek jatuh ke Java. `UnsatisfiedLinkError` (misalnya nama method diubah R8) ikut tertangkap, jadi gejalanya hanya Rust tidak pernah dipakai. Karena itu `proguard-rules.pro` sekarang menjaga method `native` di `ConfigNative` dan `LocationNative`.
- `dec`: Rust menolak hex ganjil atau bertanda (`+f`) dan memakai UTF-8 lossy untuk byte di atas 0x7F, sedangkan Java menerimanya. Hasil Rust hanya dipakai bila bukan null dan ASCII; selain itu Java yang menghitung, sehingga perilaku lama untuk input rusak tidak berubah. `enc` memakai Rust hanya untuk input ASCII.

### Build
`cargo xtask build` sekarang punya langkah `2b/4`: `cargo test` untuk `configcore` dan `lochooks` (kecuali `SKIP_TESTS`), lalu `cargo build --release --target` per `RUST_TARGETS` dan menyalin `lib<crate>.so` ke `module/zygote/src/main/jniLibs/<abi>/` (di-ignore git). Langkah ini sesudah `rotate-salt`, karena `build.rs` `configcore` menanam isi `.salt`.

### Pengujian
`cargo xtask test rswire` (tanpa cargo): membangun dua pohon dari `Config.java` dan `Fix.java` asli. Pohon pertama memakai `ConfigNative`/`LocationNative` asli (library tidak termuat, jalur Java). Pohon kedua memakai kelas pengganti di `rswire/fake/` yang meniru kontrak Rust (port Java dari `codec.rs`, `fix.rs`, `snapshot.rs`, termasuk penolakan hex ganjil). Dibandingkan:
1. `WireDump`: 31 baris codec (enc/dec/tag, hex ganjil, bertanda, non-ASCII, null) harus identik, juga bila fake dijalankan dengan salt yang sengaja salah.
2. `ParityDump`: 108 baris `Fix.apply` (4 provider x 3 waktu x 5 variasi) dan snapshot (4 config x 12 waktu), toleransi 2e-5 relatif; selisih terbesar 3.8e-6.
3. `WireCheck` dalam mode `java`, `native`, dan `badseed`: pemilihan jalur, jumlah panggilan native, `stamp` (monoton, umur, waktu dinding), `lastKnown` (memakai stempel terakhir < 5 dtk, sesudahnya tepat di grid fase).
Uji mutasi (13 kasus: argumen tertukar, bit flag salah, indeks array salah, `Config.acc` tidak diteruskan, `PHASE_NS` tidak diteruskan, verifikasi salt/seed dibuang, guard ASCII dibuang) membuat tes gagal semua. Tes lama (`lochook`, `setguard`, `syshook`, `sysguard`, `lshook`) tetap lulus; `beat` punya 4 kegagalan yang sudah ada sebelum Fase 8 (`bridge.sh` di assets tinggal stub, jadi `enc()` yang dicari tes tidak ada).

### Batas dan risiko yang belum teruji
- Library Rust belum dikompilasi dan tidak ada yang menjalankan `liblochooks.so`/`libconfigcore.so` asli terhadap kode Java baru; yang diuji adalah sisi Java terhadap kontrak lewat fake. `module/test/parity/run.sh` (butuh cargo) tetap perlu dijalankan di mesin kamu, dan sekarang juga menyalin `ConfigNative.java`.
- Penyalinan ke `jniLibs/` dan pengemasannya oleh plugin ZygoteLoader ke zip modul belum dibuktikan. Cek dengan `unzip -l SetLocZygisk-release.zip | grep -E 'configcore|lochooks'` sebelum flash.
- `System.loadLibrary("configcore")` dan `("lochooks")` dari `premain()` bergantung pada jalur library dan namespace linker, sama seperti `gpsz_hook`. Kalau gagal, hasilnya jalur Java (aman).
- Profil rilis Rust memakai `panic = "abort"`: panic di dalam fungsi JNI mematikan proses aplikasi target, tidak bisa ditangkap `try/catch` Java. Kalau ada gejala crash di aplikasi, buat `disable_rs` dulu.
- Biaya per panggilan: `nativeApply` mengalokasikan `double[13]` dan `nativeStamp` `long[2]` per pemanggilan. Belum diukur; Java lama tanpa alokasi JNI.
- Kalau `nativeStamp`/`nativeLastKnown` mengembalikan `null` di tengah jalan (gagal alokasi array), pemanggilan itu jatuh ke jalur Java yang keadaan stempelnya terpisah; satu stempel bisa tidak monoton terhadap stempel Rust sebelumnya. Sangat jarang, dan tidak ada yang bergantung pada monoton lintas dua jalur.
- RNG berbeda: Rust memakai xorshift yang diinisialisasi dari waktu, Java `java.util.Random`. Distribusinya sama, urutannya tidak.

## parse, track, dan beat lewat JNI (Fase 9)
Target: `Config.parse`, `Config.parseTrack`, `Config.applyTrack`, dan `Config.checkBeat` memakai `configcore` bila library termuat, dengan jalur Java lama tetap ada sebagai cadangan. Tidak ada perubahan perilaku yang diharapkan.

Ekspor baru di `ConfigNative` (semua di `exports.rs`):
- `nativeParse(s, prevLat, prevLng, prevAcc)` -> `double[12]`: enabled, jitter, playing, tk (0/1), lat, lng, acc, spd, brg, alt, altv, rr. `Config.parse` tetap mengurus kasus `null`/`"null"` dan semua state (`tkMode`, `cfgEn`, `beatStale`, `pos`, dan seterusnya); hanya pembacaan field yang pindah.
- `nativeParseTrack(s, hdr)` -> `double[5*n]` (la, lo, al, sp, br per baris) dan `hdr = {t0, wrote, tk, n}`.
- `nativeTrackSet(gen, t0, tk, n, la, lo, al, sp, br)`, `nativeTrackClear()`, `nativeApplyTrack(gen, nowMs, stale)` -> `double[5]` (lat, lng, alt, spd, brg). Rust menyimpan satu salinan track. Tiap `Config.Track` punya `gen` unik; `applyTrack` hanya memanggil native bila `gen` track itu sama dengan `nativeGen` dan Rust juga mencocokkan `gen`, jadi track lama yang masih dipegang thread lain tidak pernah dihitung dengan data track baru (hasil `null`, lalu Java menghitung dari array miliknya). Track dengan lebih dari 200.000 titik tidak disalin ke Rust dan dihitung di Java.
- `nativeBeatCheck(beat, playing, now)` -> `level | (freeze ? 4 : 0)`, atau -1 bila string gagal dibaca. `nativeBeatDisarm()` (dipanggil `parse(null)`, sama seperti `beatArmed = false`) dan `nativeBeatReset()`. Keadaan beat hidup di Rust bila jalur native dipakai, dan di field Java bila tidak; pilihan ditetapkan sekali saat `Config` dimuat.
- `nativeStaleLevel(lateMs)`: diekspor dan diuji, tidak dipanggil dari Java (satu perbandingan; `trackStale` tetap di Java karena memakai `noteResume`).

### Perubahan di Rust agar sama persis dengan Java
- `parse_track`: pasangan tanpa `=` dilewati (sebelumnya membatalkan seluruh parse); kunci `a` dibaca (`wrote`, default `t0`); `t`, `a`, `k` yang rusak langsung membatalkan walau muncul lagi sesudahnya; `k` dibatasi `int`.
- `apply_track`: interpolasi kecepatan memakai selisih float lalu dikalikan double, seperti Java (sebelumnya semua float, selisih di digit terakhir).
- `parse`: kunci dan nilai dipotong dengan aturan `String.trim` Java (karakter <= spasi); `-0` pada `spd`, `altv`, dan `rr` menjadi `+0` seperti `Math.max(0f, x)`.
- Mode ketat (`parse_strict`, `parse_track`): hanya bilangan desimal biasa (`[+-]digit[.digit][e[+-]digit]`) dan string ASCII. Yang lain (`5d`, `NaN`, `Infinity`, heks float, spasi di dalam angka, digit non-ASCII, nilai kosong) dikembalikan sebagai `null` dan Java yang memutuskan, karena grammar `Double.parseDouble` lebih longgar daripada Rust. Akibatnya Rust tidak pernah memberi hasil berbeda; paling buruk ia menyerahkan kasusnya ke Java.

### Pengaman
- `Config.verifyCfg()` memanggil tiap ekspor baru dengan masukan tetap dan membandingkan hasilnya dengan nilai yang sudah diketahui (termasuk penolakan `lat=5d`, `gen` yang tidak cocok, ambang beat). Library lama tanpa ekspor ini gagal di sini (`UnsatisfiedLinkError` ditangkap) dan semuanya memakai Java.
- Setiap panggilan dibungkus `try/catch Throwable`; hasil `null` jatuh ke Java untuk panggilan itu. `nativeBeatCheck` yang mengembalikan -1 di tengah jalan memakai state beat Java yang terpisah (sangat jarang; nilai beat berupa angka ASCII).

### Pengujian
- `cargo test` di `native/configcore`: 37 tes (tata bahasa angka, `jtrim`, `-0`, kunci `a`, pasangan tanpa `=`, `k` di luar `int`, selisih float kecepatan, dan lainnya).
- `cargo xtask test cfgwire` (butuh cargo, atau `CONFIGCORE_SO` menunjuk ke `libconfigcore.so` hasil build host): memuat library asli di JVM lewat `System.loadLibrary`, menjalankan `Config` asli, dan membandingkan jalur Java dengan jalur Rust sampai bit terakhir pada `CfgDump` (6.000 string config acak, 3.000 string track acak dan rusak, 60 putaran `refresh`+`tick` dengan 4.200 titik waktu, track 200.001 titik di atas batas salinan, 4.000 langkah beat acak, dan skenario beat terarah) serta `ParseTest`, `TrackTest`, `BeatTest`, `FileFieldsTest` yang sudah ada. `CfgDump` juga memastikan Rust menerima semua masukan valid (kalau Rust salah menolak, hasil tetap benar lewat Java, jadi ini tidak terlihat dari perbandingan hasil saja). Hasil: identik.
- Uji mutasi pada Rust (9 kasus: selisih float kecepatan, tanpa `jtrim`, pasangan tanpa `=` membatalkan, ambang freeze, `lerp_angle`, batas `rr`, `nativeBeatDisarm` kosong, wrap bujur dibuang, kolom enabled/jitter tertukar): semuanya membuat tes gagal. Dua yang pertama kali lolos (pasangan tanpa `=` dan `nativeBeatDisarm`) menambah pemeriksaan penerimaan dan skenario beat terarah di `CfgDump`.
- `cargo xtask test rswire`: fake `ConfigNative` melempar di ekspor baru (meniru library lama), dan `WireCheck` memeriksa `cfgNative()` mati serta `parse` tetap benar lewat Java.
- `parity`, `lochook`, `setguard`, `syshook`, `sysguard`, `lshook`: tetap lulus (dicatat pada Fase 9). Pada `beat`, `cfgwire` menjalankan `ParseTest`, `TrackTest`, `BeatTest`, dan `FileFieldsTest` sendiri-sendiri.

### Batas dan risiko yang belum teruji
- Belum dikompilasi untuk Android dan belum dijalankan di perangkat. JNI diuji lewat JVM host (x86_64), bukan ART.
- `Float.parseFloat`/`Double.parseDouble` di ART (libcore) bisa berbeda satu ulp dari Rust untuk angka desimal sangat panjang; di JVM host hasilnya identik. Config dari aplikasi SetLoc memakai angka pendek.
- Profil rilis memakai `panic = "abort"`: panic di fungsi baru mematikan proses aplikasi target. Jalur yang bisa panic sudah dijaga (indeks interpolasi, `wrapping_sub` untuk selisih waktu, batas alokasi `nativeTrackSet`), tetapi kalau muncul gejala crash, `disable_rs` mematikan library Rust tanpa membangun ulang.
- Track yang disalin ke Rust (maksimal 200.000 titik, sekitar 6 MB) menambah memori di proses target; di bawah itu biasanya hanya beberapa KB.
- Per panggilan `applyTrack` memakai satu `double[5]` dan satu panggilan JNI (tiap 50 ms paling sering); belum diukur dibanding Java.
- `loadTrackFile`, `withFile`, `trackStale`, `refresh`, dan `tkOn` tetap di Java: file I/O, state Android, dan `noteResume`.

## configcore (Fase 2)
Target: bagian `Config.java` yang murni perhitungan, tanpa hook ART dan tanpa LSPlant.
- `src/codec.rs`: `tag`/`enc`/`dec`, algoritma sama dengan `Config.java` (FNV 32-bit).
- `src/jitter.rs`: `hashUnit`/`mix`/`gauss`/`wave`/`stepJitter`, `networkOffset`/`networkAccuracy`, `altitudeNow`, `gnssExtras`, `accNow`/`vAcc`/`sAcc`/`bAcc`.
- `src/track.rs`: `parseTrack`, interpolasi `applyTrack` + `lerpAngle`, klasifikasi stale (`0/1/2`).
- `src/parse.rs`: decode string config dan staleness heartbeat (`checkBeat`).
- `ConfigNative.java`: deklarasi `native` untuk sebagian fungsi (tag/enc/dec, networkAccuracy, accNow). Tag/enc/dec dipanggil dari `Config.java` sejak Fase 8; dua yang lain tidak (lihat Fase 8).

Perbaikan di Fase 3 untuk kode Fase 2:
- Dua unit test gagal saat akhirnya dijalankan. `acc_helpers_floor` mengharapkan `v_acc(0,0)=1.0` padahal Java menghasilkan 1.3, dan `beat_freezes_then_stops` melompat 50 detik sehingga memicu reset karena jeda. Logika Rust sudah sama dengan Java; testnya yang salah dan sudah dibetulkan, ditambah test `beat_gap_rearms`.
- `SALT` sebelumnya konstanta tetap di `codec.rs`, tidak ikut diputar `tools/rotate-salt`. Sekarang `build.rs` membaca `.salt` dan `codec::SALT` memakai `env!("GPSZ_SALT")`. Diuji dengan `rotate-salt --new`: Rust dan Java tetap cocok.
- `gnss_extras` memakai `.round()` Rust (setengah menjauhi nol) sedangkan Java `Math.round` (setengah ke atas). Diganti `java_round`.
- Crate sekarang `cdylib` + `rlib`, dan ekspor JNI ada di balik fitur `jni-exports` (default aktif), supaya `lochooks` bisa memakai `configcore` tanpa menggandakan simbol JNI.

### Yang sengaja TIDAK dipindah dari Config.java
`refresh()`, `startPolling()`, `observe()`: ini manajemen `ContentObserver`, `HandlerThread`, dan `Settings.Global`, yaitu state yang hidup di objek Android (`ContentResolver`, `Application`), bukan logika murni. Memindahkannya ke native hanya menambah round-trip JNI tanpa mengurangi risiko atau kerumitan.

## lochooks (Fase 3)
Target: logika murni dari `LocationHooks.java`, `Fix.java`, dan `Config.renewSnapshot`, tanpa LSPlant dan tanpa objek Android.
- `src/fix.rs`: `apply` (hasil `Fix.apply` sebagai nilai, bukan menulis ke `Location`), `Stamper` (`stamp` dan `last_known`, RNG disuntik), `phase_ns`.
- `src/snapshot.rs`: `renew` (posisi + jitter, clamp kutub, wrap bujur, `accJit`).
- `src/lib.rs`: ekspor JNI untuk `io.gpsz.zygote.LocationNative` (`LocationNative.java`; sebagian dipanggil dari `Config` dan `Fix` sejak Fase 8).

`nativeApply` mengembalikan double[13]: 0 lat, 1 lng, 2 acc, 3 flags (bit0 motion, bit1 bearing, bit2 extras), 4 alt, 5 spd, 6 vAcc, 7 sAcc, 8 brg, 9 bAcc, 10 satelit, 11 meanCn0, 12 maxCn0.
`nativeSnapshot` mengembalikan double[7]: lat, lng, at, spd, brg, alt, accJit.

### Pengujian
`cargo xtask test parity`:
1. `ParityDump.java` menjalankan `Config` dan `Fix` Java asli (dengan stub Android) dan mencetak 263 baris nilai. `examples/parity.rs` menghitung hal yang sama di Rust lalu membandingkan dengan toleransi 1e-12 (nilai turunan float: 2e-6). Hasil: 0 beda.
2. `JniCheck.java` memuat `liblochooks.so` hasil build host lewat `System.load` dan memeriksa `nativeApply`, `nativeSnapshot`, `nativeStamp`, dan lainnya terhadap Java.
Keduanya juga dipanggil dari `cargo xtask test`.

### Yang sengaja TIDAK dipindah di Fase 3
Pendaftaran hook (`Hooks.hook`, `EmulatedStackFrame`), baca argumen dan isi return value frame ART, bagian `fixSnap` yang memanggil `Location.set`/`setTime`, `fakeWifiScan`/`newScanResult`, `feed*`, `hookCreator`, `installAppOps`. Semuanya terikat objek Android hidup dan refleksi ART. Memindahkannya butuh LSPlant yang berjalan di perangkat (Fase 4), dan itu tidak bisa diuji di sandbox.

## SettingsGuard dan SystemHooks: tidak dipindah
Keduanya dibaca penuh. Isinya didominasi oleh:
- Registrasi hook ART (`Hooks.hook`) ke method `ContentProvider`/`ApplicationPackageManager`/`LocationManagerService`/`LocationProviderManager` yang diambil lewat refleksi berdasarkan nama kelas internal `system_server`.
- Objek Android hidup yang disimpan sebagai state: instance *manager* dari `system_server` (`ConcurrentHashMap<String,Object> managers`), objek `android.location.Location`, `Cursor`, `Bundle`.
- Manipulasi stack frame ART (`EmulatedStackFrame`) untuk membaca/mengubah argumen dan return value method yang di-hook.

Di SettingsGuard, hanya 487 baris dan 2 pemanggilan `Hooks.hook(...)`; sisanya predikat kecil (`hiddenUri`, `reveals`, `isKey`) yang murni, tapi masing-masing cuma beberapa baris dan terikat erat ke tipe `Uri`/`Bundle`/`Cursor` Android, jadi memisahkannya ke Rust tidak mengurangi kerumitan apa pun.

Di SystemHooks, bagian seperti `isGnssBlockTarget`/`feedKind`/`isUnfeedTarget` murni (pencocokan nama), tapi berukuran kecil dan dikelilingi oleh logika yang sepenuhnya terikat refleksi ART dan objek `Location` hidup.

Kesimpulan: tidak ada pemindahan yang berarti di sini tanpa membawa sebagian besar ART/JNI plumbing ke Rust juga — itu menambah permukaan risiko di `system_server` tanpa manfaat nyata. Kalau kamu mau lanjut ke sini juga, perlu dibahas dulu apa targetnya, karena ini beda kategori dari Fase 1/2.

## GnssFake: bagian murni ke Rust (Fase 11)
Target: perhitungan di `GnssFake` yang tidak menyentuh objek Android pindah ke `lochooks/src/gnss.rs`; Java tetap memegang refleksi `GnssStatus`/`GnssMeasurement`/`GnssAntennaInfo`, penjadwal (`feed*`, `schedule`, `stop`), dan `Fix.apply` pada `Location`. Tidak ada perubahan perilaku yang diharapkan.

Yang dipindah (tiga ekspor baru di `LocationNative`, satu panggilan JNI per pekerjaan yang cukup besar, sekali per detik per umpan):
- `nativeGnssSnapshot(nowSec, fixed)` -> `double[1 + 9n]`: `n`, lalu per satelit `type, svid, cn0, elev, az, carrier, used, flags, baseband`. Isinya: kumpulan 29 satelit (11 GPS, 6 GLONASS, 6 Galileo, 6 BeiDou) dari `java.util.Random(seed)` yang ditiru persis di Rust (`JRandom`: LCG 48 bit, `nextInt(bound)` dengan loop tolak, `nextBoolean`, `nextDouble`, dan `Collections.shuffle`), elevasi, urutan, pemilihan satelit terpakai, C/N0, azimut, frekuensi carrier, serta `flags` dan `baseband`. Nilai float dihitung sebagai `f32` di Rust supaya sama dengan cast Java.
- `nativeGnssNmea(snap, ms, lat, lng, acc, hasSpd, spd, hasBrg, brg, hasAlt, alt)` -> `String[]` (GGA, GSA per konstelasi, GSV per halaman, RMC). `java_fixed` meniru `String.format("%.Nf")` Java: digit terpendek (`{:e}` Rust) lalu pembulatan setengah-ke-atas pada digit berikutnya, `-0.0` tetap `-0.0`. Tanggal dan jam dihitung dari epoch (`civil_from_days`), pembulatan `Math.round` dengan `floor(x + 0.5)` eksak.
- `nativeGnssMeas(snap, nowMs, timeNs)` -> `long[2 + 2n]`: `gpsNs`, `tow`, lalu per satelit `travelNs` dan bit `double` dari `pseudorangeRate`.
Tetap di Java: `Config.gnssExtrasAt` (dipakai jalur Java; Rust memakai `jitter::gnss_extras`), `Snapshot` sebagai wadah, semua pembuatan objek Android lewat refleksi.

Perubahan kecil di Java agar bisa diuji: `Config.gnssExtras()` sekarang memanggil `gnssExtrasAt(t)` dan `GnssFake.snapshot()` membaca jam sekali (sebelumnya dua kali, `gnssExtras` dan `nowSec`). `GnssFake.snapshotJava`, `nmeaJava`, dan `measJava` adalah jalur Java lama tanpa perubahan logika, hanya dipisah dari pembacaan jam dan `Location`.

### Pengaman
- `GnssFake.NATIVE` ditetapkan sekali saat kelas dimuat: aktif hanya bila `Config.locNative()` (seed sama), lalu `snapshotNative` sama dengan `snapshotJava` di 12 kasus (6 waktu x fixed/tidak: type, svid, used, flags persis; cn0/elev/az/baseband dalam toleransi), NMEA native sama persis dengan Java di 5 masukan (termasuk `-0.04`, `0.25`, `1234.55`, akurasi 0, tanpa speed/bearing/altitude) dan `measNative` sama persis. Library lama tanpa ekspor ini (`UnsatisfiedLinkError`) atau yang hasilnya beda jatuh ke Java tanpa crash.
- Setiap panggilan dibungkus `try/catch Throwable`; hasil `null`, array salah ukuran, atau string `null` jatuh ke Java untuk panggilan itu. `read_snap` di Rust menolak array dengan `n` di luar 0..64, `n` bukan bilangan bulat, atau panjang tidak cocok (hasil `null`).
- `disable_rs` mematikan library Rust seperti sebelumnya, dan `GnssFake` otomatis memakai Java.

### Pengujian
- `cargo test` di `native/lochooks` (`gnss.rs`, 10 tes baru): vektor `java.util.Random(42)` (-1170105035, 234785527), bentuk pool, `java_fixed` (0.25, 0.15, 9.96, 99.995, 1234.55, -0.04, -0.0, NaN, Infinity), `Math.round`, tanggal, invarian snapshot, pack/unpack dan penolakan masukan rusak, flags/baseband, struktur NMEA dan checksum, tata letak measurements.
- `cargo xtask test gnssfake` (tanpa cargo): `GnssFake` asli dengan stub dan `LocationNative` palsu yang mengikuti kontrak (`gnsswire/fake`). Memeriksa 18 hal: verifikasi lolos, paritas 400 snapshot dan 600 NMEA dan 600 measurements, native dipakai (hitungan panggilan), hasil sama dengan Java, kalimat valid; serta cadangan saat native mengembalikan `null`, melempar, atau array salah ukuran; dan mode `fake.bad` (satu keluaran beda) membuat verifikasi gagal dan native tidak dipanggil lagi. Uji mutasi (`used` terbalik, indeks `flags` salah, `hasAlt` tertukar, cek `null` dibuang) membuat tes gagal.
- `cargo xtask test gnsswire` (butuh cargo atau `LOCHOOKS_SO`): `GnssWire` yang sama, tetapi dengan `liblochooks.so` asli lewat `System.loadLibrary`.
- Tes lama yang memakai `GnssFake` (`lochook`, `syshook` empat mode) dan `rswire` tetap lulus.
- Port Python dari `gnss.rs` (bukan kode Rust-nya) dibandingkan dengan keluaran Java asli: 16 snapshot, 400 kasus NMEA, 50 kasus measurements, dan 3.020 angka untuk `%.1f`/`%.2f`: 0 beda.

### Batas dan risiko yang belum teruji
- `gnss.rs` dan ekspor barunya BELUM dikompilasi: tidak ada cargo di lingkungan tempat ini ditulis. Yang terbukti hanya logikanya (port Python vs Java) dan sisi Java (lewat native palsu). Jalankan `cargo test --manifest-path native/lochooks/Cargo.toml` dan `cargo xtask test gnsswire` dulu; kalau ada galat kompilasi, kemungkinan di tanda tangan `jni` 0.21 pada `read_snap`/`nativeGnssNmea`.
- `sin` Java (`Math.sin`) dan Rust bisa beda satu ulp. Dampaknya hanya pada digit terakhir `elev`/`cn0`; urutan satelit baru berubah bila dua skor selisih kurang dari satu ulp (praktis tidak pernah). Verifikasi memakai toleransi untuk float, persis untuk integer.
- Pembulatan `%.1f` meniru `Formatter` Java (digit terpendek lalu setengah-ke-atas). Di JVM host 21 sama persis pada 3.020 angka; di ART (libcore) algoritma digit terpendeknya bisa beda pada kasus langka, dan verifikasi saat memuat hanya mencoba 5 masukan. Kalau beda, tampilan NMEA bergeser satu digit desimal, tidak lebih.
- Profil rilis `panic = "abort"`: panic di ekspor baru mematikan proses target. Jalur yang bisa panic dijaga (batas `n`, panjang array, `wrapping_*` untuk waktu, tanpa indeks di luar batas).
- Overhead: tiap detik satu `double[1+9n]` (n sampai 29) dan satu `String[]` per umpan; belum diukur di perangkat.

## Langkah di sisi kamu
1. Jalankan `cargo xtask test` dan `cargo xtask test parity` di mesin build kamu (butuh cargo dan java).
2. Untuk Android: `cargo build --release --target aarch64-linux-android` di `native/lochooks` dan `native/configcore`, taruh `.so` di `jniLibs`, lalu uji `LocationNative.ensureLoaded()` di perangkat cadangan sebelum menyambungkan ke `LocationHooks`, `Fix`, atau `Config`.
3. Bangun modul lewat `cargo xtask build` (sekarang juga membangun `configcore` dan `lochooks` untuk Android; lihat Fase 8) (Gradle mengunduh LSPlant dan ShadowHook, NDK membangun `libgpsz_hook.so`), flash di perangkat cadangan, lalu cek `logcat` dan `gpsz.sh doctor`. Untuk mematikan library Rust tanpa membangun ulang, buat file `disable_rs` di folder modul. Untuk mematikan jalur LSPlant tanpa membangun ulang, buat file `disable_ls` di folder modul (atau `disable_ls_loc` untuk `LocationHooks` saja).
4. Untuk mencoba Fase 7 (hanya setelah fase 5 dan 6 terbukti stabil): `gpsz.sh ls-system on`, reboot, lalu `gpsz.sh ls-system status` (harus `on` setelah sekitar 60 detik, bukan `pending` atau `blocked`) dan `logcat -s GpsZ`. Kalau boot gagal, reboot sekali lagi: percobaan kedua otomatis diblokir dan jalur lama dipakai.

## Fase 10: skrip dipindah ke xtask
`make-keystore.sh`, `setup.sh`, `update.sh`, dan harness tes shell (`module/test/run.sh` serta `run.sh` di tiap subfolder) diganti perintah `cargo xtask` (Rust, tanpa dependensi):
- `cargo xtask keystore <key.pem> <cert.pem> [out.p12]` membuat PKCS12 dan `app/keystore.properties`; password dibaca dari terminal tanpa echo dan diteruskan ke `openssl` lewat env.
- `cargo xtask setup` sudah dihapus di Fase 12 (AndroidVMTools tidak dipakai lagi).
- `cargo xtask update [zip-atau-folder]` seperti sebelumnya; kalau `tools/xtask` di source baru berbeda dari yang sedang berjalan, xtask baru dibangun dari source baru dulu (sama seperti shim `update.sh` lama). `make-keystore.sh`, `setup.sh`, dan `update.sh` yang masih ada di repo dihapus.
- `cargo xtask test [bagian...]` menjalankan harness: `sintaks`, `daemon`, `supervisor`, `keystore`, `install`, `service`, `kunci`, `struktur`, `webui`, `native` (cargo test configcore dan lochooks), serta suite Java `beat`, `idmap`, `lshook`, `lochook`, `setguard`, `syshook`, `sysguard`, `rswire`, `parity`, `cfgwire`. Grup `java` dan `native` menjalankan semua suite di grupnya. Binary daemon dari `SETLOCD_BIN` atau dibangun dengan `cargo build --release` di `daemon/`; `CONFIGCORE_SO` menunjuk ke `libconfigcore.so` host untuk `cfgwire`.
- Salt dan tag kunci dibaca dari `.salt` (bukan lagi ditulis ulang ke `run.sh`), jadi `rotate-salt` tidak menyentuh file tes lagi. Kunci `Config` untuk `beat` dihitung dari salt, bukan dibaca dari `bridge.sh`/`gpsz.sh` yang kini hanya stub.
- Kode xtask belum pernah dikompilasi atau dijalankan di lingkungan tempat ini ditulis (tidak ada cargo); jalankan `cargo test --manifest-path tools/xtask/Cargo.toml` dan `cargo xtask test` di mesin build.

## Fase 12: jalur Java cadangan dan AndroidVMTools dibuang
Dikerjakan setelah Rust dan LSPlant terbukti jalan di perangkat. Sejak fase ini LSPlant adalah satu-satunya mesin hook ART.

Dihapus:
- `PackageGuard.java` (hook `ApplicationPackageManager` versi AndroidVMTools) dan `LegacyFrame.java` (adaptor `Hooks.hook`/`EmulatedStackFrame`).
- Semua pemakaian `com.v7878.unsafe.Reflection` di `Fix`, `LocationHooks`, `SystemHooks`, `Config`; refleksi biasa dipakai, gagal berarti `null` seperti sebelumnya.
- Modul Gradle `module/unsafe` (SunUnsafe) dan `module/stub` (stub `sun.misc.Unsafe`), version catalog `androidvmtools`, dependensi `panama.*` dan `sun.cleaner`, serta source set `external/AndroidVMTools` di `module/zygote/build.gradle.kts`.
- `setup_vmtools()`, konstanta `PIN`/`VMTOOLS_*`, subperintah `cargo xtask setup`, dan step "siapkan AndroidVMTools" di `cargo xtask build` (kini 3 langkah).
- Suite `unsafe` di harness dan `module/test/unsafe`.

Perilaku baru:
- `Frames.hook()` hanya memakai LSPlant. Engine belum siap, flag `disable_ls*` aktif, atau satu method gagal dipasang: method itu tidak dihook, tanpa fallback. `Frames.legacyBroken` tidak ada lagi.
- `PackageGuardLs.install()` memegang guard `ZygoteEntry.isSelf()` yang dulu ada di `PackageGuard.java`, supaya app SetLoc sendiri tidak menyembunyikan paketnya dari dirinya sendiri.
- `disable_ls` mematikan semua hook LSPlant; `disable_ls_loc` mematikan `LocationHooks` (penyembunyian paket tetap jalan); `disable_ls_set` mematikan `SettingsGuard`. Tidak ada yang beralih ke jalur lama.
- `SystemHooks.install()` melaporkan hidup hanya bila hook `LocationProviderManager` terpasang; tanpa LSPlant ia melaporkan tidak hidup.

Pengujian:
- `Check.java` di `lochook`, `setguard`, `syshook` ditulis ulang untuk perilaku tanpa fallback (bagian "tanpa jalur cadangan"; `syshook` mode `off`, `notready`, `partial` kini mengharapkan nol hook untuk method yang gagal). Stub `lochook` memakai `PackageGuardLs.java`; stub `LegacyFrame` dihapus; stub `ZygoteEntry` punya `isSelf()`.
- `cargo xtask test struktur` kini memeriksa bahwa `module/unsafe`, `module/stub`, `module/external` tidak ada, gradle bebas AndroidVMTools, dan sumber zygote tidak lagi merujuk `com.v7878.unsafe`, `LegacyFrame`, atau `Hooks.hook`.
- Suite Java `lochook`, `setguard`, `syshook` (4 mode), `lshook`, `sysguard` dijalankan manual dengan javac JDK dan lolos; `beat`/`parity` dikompilasi tanpa error.

Belum diverifikasi di lingkungan ini: kode `tools/xtask` yang diedit (tidak ada cargo di sini; hanya diedit hati-hati, belum dikompilasi) dan build Gradle penuh. Jalankan `cargo xtask test` lalu `cargo xtask build` di mesin build.
