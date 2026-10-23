# native/

Status: `configcore` dan `lochooks` sudah dikompilasi dan diuji di host (cargo test, paritas Java vs Rust, dan JNI lewat JVM). `hook/` (Fase 4) lolos cek sintaks terhadap header asli LSPlant dan ShadowHook, dan logika Java-nya diuji di JVM dengan mesin hook palsu. Fase 5 dan 6 memindahkan `LocationHooks` dan `SettingsGuard` ke jalur LSPlant, dan Fase 7 `SystemHooks` (opt-in, di `system_server`); semuanya diuji di JVM dengan mesin hook palsu yang sama. Fase 8 menyambungkan `ConfigNative` dan `LocationNative` ke `Config` dan `Fix`, dengan cadangan ke Java. Belum ada yang dikompilasi untuk target Android atau diuji di perangkat.

## hook (Fase 4)
Target: pendaftaran hook ART lewat LSPlant, dipakai pertama untuk `PackageGuard`.
- `hook/hook.cpp`: `libgpsz_hook.so`. `JNI_OnLoad` menyalakan ShadowHook (mode unique) dan `lsplant::Init`; ekspor `LsNative.nativeReady` dan `LsNative.nativeHook`. Resolver simbol `libart.so` memakai `shadowhook_dlsym`. Resolver prefiks sengaja mengembalikan null (lihat batas di bawah).
- `hook/CMakeLists.txt`: dibangun Gradle (`externalNativeBuild` di `module/zygote`), dengan `org.lsposed.lsplant:lsplant-standalone` dan `com.bytedance.android:shadowhook` lewat prefab.
- `LsNative.java`: `ensureLoaded()` memuat `gpsz_hook`. `ZygoteEntry.premain()` memanggilnya, karena folder modul hanya bisa dibaca sebelum proses di-specialize. Dilewati di app SetLoc sendiri, di `system_server`, dan kalau ada file `disable_ls` di folder modul. `premain()` juga membaca `disable_ls_loc` (lihat Fase 5).
- `LsHook.java`: membungkus `Hook` LSPlant. Callback menerima `Object[]` (indeks 0 = `this`, sama seperti indeks `EmulatedStackFrame` di kode lama) dan memanggil method asli lewat backup. Nilai kembali primitif harus dikotak (`Boolean`, `Integer`, `Long`).
- `PackageGuardLs.java`: perilaku `PackageGuard` yang sama (daftar method, pesan exception, nilai tolak) di atas `LsHook`. `PackageGuard.install()` memakainya dulu. Kalau `LsNative` tidak siap, jalur lama (`Hooks.hook`) dipakai seperti sebelumnya.
- Crate `pkgguard` dan `shim/` dihapus. LSPlant hanya punya API C++, jadi lapisan hook memang harus C++. Dua fungsi Rust-nya (`nativeIsOwn`, `nativeFilterStrings`) tidak pernah dipanggil dari Java dan lebih mahal lewat JNI daripada `String.equals`.

### Pengujian
`sh module/test/lshook/run.sh`: menjalankan `PackageGuardLs` dan `LsHook` asli dengan stub `android.content.pm.*`, `ApplicationPackageManager` palsu, dan `LsNative` palsu yang mengembalikan method asli sebagai backup. Memeriksa 21 perilaku (exception paket sendiri, filter daftar, `checkPermission`/`checkSignatures`, UID, `resolveActivity`, dan `File.exists`/`isDirectory`/`lastModified` untuk path berisi nama paket).

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
- `LegacyFrame.java`: jalur lama (`Hooks.hook` + `EmulatedStackFrame`) di balik adaptor yang sama. Hanya dimuat bila dipakai. Kalau `Hooks.hook` melempar, `Frames.legacyBroken` diset dan pemasangan jalur lama berhenti (menggantikan flag `crashed` lama); hook LSPlant tetap jalan.
- `LocationHooks.java`: perubahannya mekanis. `frame.accessor().getReference(i)` jadi `frame.get(i)`, `setValue` jadi `set`, `Transformers.invokeExactNoChecks(original, frame)` jadi `original.invoke()`, `Hooks.hook(...)` jadi `Frames.hook(...)`. Isi transformer, urutan cabang, dan `PER_OBJ`/`PARSING` tidak berubah.
- `disable_ls_loc` di folder modul: `LocationHooks` memakai jalur lama sementara `PackageGuard` tetap LSPlant. Berguna untuk mencari penyebab masalah di perangkat tanpa membangun ulang. `disable_ls` mematikan keduanya.
- `LsHook`: dua perbaikan dari Fase 4. `backup` sekarang diisi sebelum `setAccessible` (sebelumnya kalau `setAccessible` melempar, hook sudah terpasang tetapi callback menunggu selamanya), dan penantian `backup` dibatasi 2 detik lalu melempar `IllegalStateException`, bukan spin tanpa batas.

### Pengujian
`sh module/test/lochook/run.sh`: menjalankan `LocationHooks`, `Config`, `Fix`, dan `GnssFake` asli dengan stub Android dan `LsNative` palsu (55 pemeriksaan). Mencakup: 51 method terpasang lewat LSPlant pada stub uji, getter Location menghasilkan posisi palsu dengan tipe benar, `isLocationEnabled`/`isProviderEnabled`/`getProviders`, `getLastKnownLocation` (termasuk `SecurityException` dari method asli), AppOps, Wi-Fi/seluler/Bluetooth (termasuk penggantian argumen sebelum method asli), `createFromParcel`, `Location.set`, `getCurrentLocation`, serta semua jalur cadangan (`disable_ls_loc`, LSPlant tidak siap, satu method gagal, jalur lama gagal). Uji mutasi pada `coerce` dan penulisan argumen membuat tes gagal, jadi tesnya memang menangkap kesalahan itu.

### Batas dan risiko yang belum teruji
- Tes memakai `LsNative` palsu yang memanggil method asli langsung. Di perangkat, getter Location yang dipanggil dari dalam transformer (misalnya `getElapsedRealtimeNanos` di `fixSnap`) juga lewat hook; perlindungannya sama seperti jalur lama (`PARSING` dan `Mark`) tetapi belum terbukti di ART.
- Biaya per panggilan naik: tiap `getLatitude` dkk. mengalokasikan `Object[]`, mengotak nilai, dan memanggil method asli lewat `Method.invoke`. Aplikasi yang membaca lokasi ribuan kali per detik akan lebih lambat dibanding jalur lama.
- Sampai Fase 5, `SettingsGuard` masih di jalur lama sehingga LSPlant dan AndroidVMTools berjalan bersamaan; itu sudah diselesaikan di Fase 6.
- `AppOpsManager.unsafeCheckOp(String, int, String)`: indeks `op` dihitung dari parameter `int` pertama, jadi untuk overload ini yang diperiksa adalah `uid`, bukan `op`. Perilaku ini sudah ada sebelum Fase 5 dan sengaja tidak diubah.
- `SystemHooks` (di `system_server`) tidak ikut di fase ini; lihat Fase 7.

## SettingsGuard di LSPlant (Fase 6)
Target: 14 hook di `SettingsGuard` (kunci modul di `Settings.NameValueCache`, `ContentResolver`/`ContentProviderClient`/`ContentProviderProxy` query dan call, `ContentObserver.dispatchChange`, `registerContentObserver`, `ProcessBuilder.start`, `Settings.Secure.location_mode`) memakai adaptor `Frames` yang sama seperti Fase 5. Perubahan di `SettingsGuard.java` mekanis (`frame.accessor().getReference(i)` jadi `frame.get(i)`, dan seterusnya); logika dan urutan cabang tidak berubah.
- `disable_ls_set` di folder modul: `SettingsGuard` memakai jalur lama; `disable_ls_loc` tidak memengaruhinya. `Frames.hook(m, ls, tx)` menerima flag per grup.
- Method statis (`Settings.Secure.getInt/getString`): LSPlant tidak mengirim `this`, jadi `args[0]` adalah parameter pertama. Indeks di `SettingsGuard` (`1` = kunci) sudah cocok dengan itu, sama seperti pada `EmulatedStackFrame`.
- Setelah Fase 5 dan 6, `LocationHooks` dan `SettingsGuard` tidak lagi memakai AndroidVMTools kalau LSPlant siap. `SystemHooks` (di `system_server`) dan `GnssFake` tidak memakai hook ART. Jalur lama baru dimuat bila LSPlant tidak siap atau salah satu flag `disable_*` ada.

### Pengujian
`sh module/test/setguard/run.sh`: menjalankan `SettingsGuard` dan `Config` asli dengan stub (`Settings`, `ContentResolver`, `Cursor`, `Bundle`, dan lainnya) serta `LsNative` palsu: kunci di `NameValueCache` dan prefix, query (baris disaring, URI kunci jadi kursor kosong, selection berisi kunci, `Config.READING` melewatkan), call (`GET_`/`PUT_` kosong, `LIST_` dibersihkan, `ContentProviderProxy`), observer (argumen koleksi diganti sebelum method asli), `ProcessBuilder.start` (ditolak dengan `error=13`), `location_mode` untuk method statis, dan jalur cadangan.

### Batas yang belum teruji / diketahui
- `ContentProviderClient.call(method, arg, extras)` tidak punya argumen URI, jadi tidak pernah dianggap settings. Perilaku ini sama dengan jalur lama dan sengaja tidak diubah; tesnya mencatatnya.
- Pemanggilan `Settings.NameValueCache.getStringForUser` sangat sering. Overhead alokasi/refleksi LSPlant per panggilan lebih besar daripada jalur lama; belum diukur di perangkat.
- `ProcessBuilder.start` adalah method inti libcore; hook LSPlant di sana (di proses aplikasi) belum diuji di ART.

## SystemHooks di LSPlant (Fase 7)
Target: 10 hook di `system_server` (`LocationProviderManager.onReportLocation`; di `LocationManagerService`: `getLastLocation`, `injectLocation`, daftar/hapus pendengar GNSS, blokir batching dan navigation message) memakai adaptor `Frames` yang sama. Perubahan di `SystemHooks.java` mekanis (`frame.accessor().getReference(i)` jadi `frame.get(i)`, `setValue(RETURN_VALUE_IDX, x)` jadi `set(Frames.RET, x)`, `invokeExactNoChecks` jadi `original.invoke()`); logika dan urutan cabang tidak berubah. Pemasangan lewat `Frames.hook(m, LsNative.sysEnabled(), tx)`, jadi tiap method yang gagal lewat LSPlant jatuh ke jalur lama sendiri.

Default MATI. Tanpa file `enable_ls_system`, `system_server` berjalan persis seperti sebelum fase ini (AndroidVMTools, LSPlant tidak dimuat).

### Pengaman boot
Semua penanda ada di folder modul. `SysGuard.arm` dipanggil di `premain()` (sebelum specialize), sebelum `libgpsz_hook.so` dimuat:
1. `enable_ls_system` tidak ada: tidak ada yang disentuh, jalur lama.
2. `ls_system_blocked` ada: jalur lama.
3. `.ls_sys_pending` ada (percobaan sebelumnya tidak pernah dikonfirmasi): `ls_system_blocked` dibuat, jalur lama. Sesudahnya semua boot tetap jalur lama sampai `gpsz.sh ls-system reset` atau `on`.
4. Selain itu `.ls_sys_pending` dibuat dulu, baru LSPlant dimuat. Kalau penanda tidak bisa ditulis (misalnya SELinux), LSPlant tidak dimuat (gagal tertutup).

Konfirmasi ada di daemon Rust (`daemon/src/lsguard.rs`, dijalankan dari `service.rs` hanya bila `enable_ls_system` ada): setelah `sys.boot_completed`, bila `.ls_sys_pending` ada dan PID `system_server` tidak berubah selama 60 detik, penanda dihapus. PID berubah (system_server mati dan dimulai ulang) menghitung ulang dari nol. Kalau `ls_system_blocked` ada, `.ls_sys_pending` dibuang tanpa menunggu.

Perintah: `gpsz.sh ls-system on|off|status|reset`. `on` menghapus blokir dan penanda lalu membuat `enable_ls_system` (perlu reboot); `off` menghapus ketiganya; `reset` hanya menghapus blokir dan penanda; `status` menampilkan `ls-system=off|on|pending|blocked`. `gpsz.sh doctor` juga mencetak barisnya. `enable_ls_system` sengaja tidak ikut dipertahankan saat pemasangan ulang modul (seperti `disable_ls*`).

### Pengujian
- `sh module/test/syshook/run.sh`: menjalankan `SystemHooks`, `Config`, `Fix`, dan `GnssFake` asli dengan kelas palsu `LocationManagerService`, `LocationProviderManager`, `LocationResult`, dan `LsNative` palsu, dalam empat proses terpisah (`ls`, `off`, `notready`, `partial`). Mode `ls` (10 hook lewat LSPlant, nol lewat jalur lama) memeriksa: semua diteruskan saat config mati; mode penuh mengganti hasil `onReportLocation` dan `getLastLocation`, mengganti argumen `injectLocation`, memblokir batching, memberi pendengar GNSS palsu (dan `android` tetap diteruskan), `SecurityException` dari method asli tetap sampai ke pemanggil; darurat dan heartbeat tidak mengganti apa pun. Mode `off` dan `notready`: 10 method ke jalur lama. Mode `partial`: satu method gagal di LSPlant dan hanya itu yang jatuh ke jalur lama. Uji mutasi (hasil tidak diganti, exception ditelan, flag `sysEnabled` selalu true, method asli selalu dipanggil) membuat tes gagal.
- `sh module/test/sysguard/run.sh`: urutan boot (tanpa opt-in, boot pertama, boot kedua tanpa dan dengan konfirmasi, reset, off) dan jalur gagal tertutup (penanda tidak bisa ditulis, jalur berupa folder, `File` null).
- `cargo test` di `daemon/`: 7 tes `lsguard` (pending hanya dihapus setelah PID sama selama 60 detik, PID berubah mengulang hitungan, `system_server` hilang tidak mengonfirmasi, blokir membuang pending, perintah `on|off|reset`).
Ketiganya dipanggil dari `module/test/run.sh` (dua pertama) dan `cargo xtask build`.

### Batas dan risiko yang belum teruji
- Belum dikompilasi untuk Android dan belum dijalankan di perangkat, sama seperti fase 4 sampai 6. Hook lewat LSPlant di `system_server` belum terbukti di ART.
- Semua yang dibaca dan ditulis `premain()` (folder modul) bergantung pada izin SELinux proses yang sedang di-fork. Pembacaan sudah dipakai fase 4 sampai 6; penulisan `.ls_sys_pending` belum. Kalau ditolak, hasilnya LSPlant tidak dimuat di `system_server` (aman, tetapi fase ini tidak akan pernah aktif). `gpsz.sh ls-system status` akan menunjukkan `on` tanpa pernah `pending`.
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

Sengaja TIDAK disambungkan: `nativeRoute`, `nativeIsSnapKind`, `nativeExtValue`, `nativeActive`, `nativeIsSpoofProvider`, `nativeMergeProviders`, `nativeSnapDecide`, `nativeIsFix`, `nativeSnapFresh`, `ConfigNative.nativeNetworkAccuracy` dan `nativeAccNow`. Isinya perbandingan integer atau satu baris aritmetika; satu panggilan JNI lebih mahal daripada pekerjaannya, dan `transformer` di `LocationHooks` dipanggil di jalur panas. `nativeNetworkAccuracy` dan `nativeAccNow` sudah tercakup `nativeApply`. Fungsinya tetap ada di Rust (diuji paritas), hanya tidak dipanggil dari Java.

### Pengaman
- Pemuatan: `ZygoteEntry.premain()` memanggil `loadRust()` (sebelum specialize, sama seperti `libgpsz_hook.so`). Di `system_server` hanya bila `enable_ls_system` aktif dan `SysGuard` memberi `ARMED`; tanpa itu `system_server` berjalan persis seperti sebelumnya. File `disable_rs` di folder modul mematikan pemuatan library Rust (`disable_ls` tidak memengaruhinya, dan sebaliknya).
- Verifikasi saat `Config` dimuat: `CODEC_NATIVE` hanya aktif bila `nativeTag("cfg")`, `nativeEnc`, dan `nativeDec` memberi hasil yang sama dengan Java; `LOC_NATIVE` hanya aktif bila `nativePhaseNs` sama dengan hitungan Java (membuktikan seed turunan salt sama). Library yang dibangun sebelum salt diputar gagal di sini dan jalur Java dipakai, tanpa crash dan tanpa perubahan hasil.
- Setiap panggilan dibungkus `try/catch Throwable`; hasil `null` atau array pendek jatuh ke Java. `UnsatisfiedLinkError` (misalnya nama method diubah R8) ikut tertangkap, jadi gejalanya hanya Rust tidak pernah dipakai. Karena itu `proguard-rules.pro` sekarang menjaga method `native` di `ConfigNative` dan `LocationNative`.
- `dec`: Rust menolak hex ganjil atau bertanda (`+f`) dan memakai UTF-8 lossy untuk byte di atas 0x7F, sedangkan Java menerimanya. Hasil Rust hanya dipakai bila bukan null dan ASCII; selain itu Java yang menghitung, sehingga perilaku lama untuk input rusak tidak berubah. `enc` memakai Rust hanya untuk input ASCII.

### Build
`cargo xtask build` sekarang punya langkah `2b/4`: `cargo test` untuk `configcore` dan `lochooks` (kecuali `SKIP_TESTS`), lalu `cargo build --release --target` per `RUST_TARGETS` dan menyalin `lib<crate>.so` ke `module/zygote/src/main/jniLibs/<abi>/` (di-ignore git). Langkah ini sesudah `rotate-salt`, karena `build.rs` `configcore` menanam isi `.salt`.

### Pengujian
`sh module/test/rswire/run.sh` (tanpa cargo): membangun dua pohon dari `Config.java` dan `Fix.java` asli. Pohon pertama memakai `ConfigNative`/`LocationNative` asli (library tidak termuat, jalur Java). Pohon kedua memakai kelas pengganti di `rswire/fake/` yang meniru kontrak Rust (port Java dari `codec.rs`, `fix.rs`, `snapshot.rs`, termasuk penolakan hex ganjil). Dibandingkan:
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
- `src/kind.rs`: konstanta `K_*`, `is_snap_kind`, `route(kind)` (urutan cabang sama dengan rantai `if` di `transformer`), `ext_value(kind, hideNet, hideBt)`.
- `src/fix.rs`: `apply` (hasil `Fix.apply` sebagai nilai, bukan menulis ke `Location`), `Stamper` (`stamp` dan `last_known`, RNG disuntik), `phase_ns`.
- `src/snap.rs`: `is_fix` dan `decide` (lewati bila `Mark` sama, clamp waktu masa depan lebih dari 5 detik).
- `src/snapshot.rs`: `renew` (posisi + jitter, clamp kutub, wrap bujur, `accJit`).
- `src/providers.rs`: `active`, `is_spoof`, `merge` daftar provider.
- `src/lib.rs`: ekspor JNI untuk `io.gpsz.zygote.LocationNative` (`LocationNative.java`; sebagian dipanggil dari `Config` dan `Fix` sejak Fase 8).

Kode `Route` (`nativeRoute`): Create 0, Set 1, Providers 2, LastKnown 3, Deliver 4, Snap 5, SnapPair 6, CellUpdate 7, CellListArg 8, Drop 9, WifiScan 10, WifiSsid 11, CellAll 12, CellLoc 13, Feed 14, GpsStatus 15, Ext 16, MockFalse 17.
Kode `ExtValue` (`nativeExtValue`): PassThrough 0, Bssid 1, Null 2, Void 3, Int0 4, True 5, False 6.
`nativeApply` mengembalikan double[13]: 0 lat, 1 lng, 2 acc, 3 flags (bit0 motion, bit1 bearing, bit2 extras), 4 alt, 5 spd, 6 vAcc, 7 sAcc, 8 brg, 9 bAcc, 10 satelit, 11 meanCn0, 12 maxCn0.
`nativeSnapshot` mengembalikan double[7]: lat, lng, at, spd, brg, alt, accJit.

### Pengujian
`sh module/test/parity/run.sh`:
1. `ParityDump.java` menjalankan `Config` dan `Fix` Java asli (dengan stub Android) dan mencetak 263 baris nilai. `examples/parity.rs` menghitung hal yang sama di Rust lalu membandingkan dengan toleransi 1e-12 (nilai turunan float: 2e-6). Hasil: 0 beda.
2. `JniCheck.java` memuat `liblochooks.so` hasil build host lewat `System.load` dan memeriksa `nativeApply`, `nativeSnapshot`, `nativeStamp`, `nativeMergeProviders`, dan lainnya terhadap Java.
Keduanya juga dipanggil dari `module/test/run.sh`.

### Yang sengaja TIDAK dipindah di Fase 3
Pendaftaran hook (`Hooks.hook`, `EmulatedStackFrame`), baca argumen dan isi return value frame ART, bagian `fixSnap` yang memanggil `Location.set`/`setTime`, `fakeWifiScan`/`newScanResult`, `GnssFake`, `feed*`, `hookCreator`, `installAppOps`. Semuanya terikat objek Android hidup dan refleksi ART. Memindahkannya butuh LSPlant yang berjalan di perangkat (Fase 4), dan itu tidak bisa diuji di sandbox.

## SettingsGuard dan SystemHooks: tidak dipindah
Keduanya dibaca penuh. Isinya didominasi oleh:
- Registrasi hook ART (`Hooks.hook`) ke method `ContentProvider`/`ApplicationPackageManager`/`LocationManagerService`/`LocationProviderManager` yang diambil lewat refleksi berdasarkan nama kelas internal `system_server`.
- Objek Android hidup yang disimpan sebagai state: instance *manager* dari `system_server` (`ConcurrentHashMap<String,Object> managers`), objek `android.location.Location`, `Cursor`, `Bundle`.
- Manipulasi stack frame ART (`EmulatedStackFrame`) untuk membaca/mengubah argumen dan return value method yang di-hook.

Di SettingsGuard, hanya 487 baris dan 2 pemanggilan `Hooks.hook(...)`; sisanya predikat kecil (`hiddenUri`, `reveals`, `isKey`) yang murni, tapi masing-masing cuma beberapa baris dan terikat erat ke tipe `Uri`/`Bundle`/`Cursor` Android, jadi memisahkannya ke Rust tidak mengurangi kerumitan apa pun.

Di SystemHooks, bagian seperti `isGnssBlockTarget`/`feedKind`/`isUnfeedTarget` murni (pencocokan nama), tapi berukuran kecil dan dikelilingi oleh logika yang sepenuhnya terikat refleksi ART dan objek `Location` hidup.

Kesimpulan: tidak ada pemindahan yang berarti di sini tanpa membawa sebagian besar ART/JNI plumbing ke Rust juga — itu menambah permukaan risiko di `system_server` tanpa manfaat nyata. Kalau kamu mau lanjut ke sini juga, perlu dibahas dulu apa targetnya, karena ini beda kategori dari Fase 1/2.

## Langkah di sisi kamu
1. Jalankan `sh module/test/run.sh` dan `sh module/test/parity/run.sh` di mesin build kamu (butuh cargo dan java).
2. Untuk Android: `cargo build --release --target aarch64-linux-android` di `native/lochooks` dan `native/configcore`, taruh `.so` di `jniLibs`, lalu uji `LocationNative.ensureLoaded()` di perangkat cadangan sebelum menyambungkan ke `LocationHooks`, `Fix`, atau `Config`.
3. Bangun modul lewat `cargo xtask build` (sekarang juga membangun `configcore` dan `lochooks` untuk Android; lihat Fase 8) (Gradle mengunduh LSPlant dan ShadowHook, NDK membangun `libgpsz_hook.so`), flash di perangkat cadangan, lalu cek `logcat` dan `gpsz.sh doctor`. Untuk mematikan library Rust tanpa membangun ulang, buat file `disable_rs` di folder modul. Untuk mematikan jalur LSPlant tanpa membangun ulang, buat file `disable_ls` di folder modul (atau `disable_ls_loc` untuk `LocationHooks` saja).
4. Untuk mencoba Fase 7 (hanya setelah fase 5 dan 6 terbukti stabil): `gpsz.sh ls-system on`, reboot, lalu `gpsz.sh ls-system status` (harus `on` setelah sekitar 60 detik, bukan `pending` atau `blocked`) dan `logcat -s GpsZ`. Kalau boot gagal, reboot sekali lagi: percobaan kedua otomatis diblokir dan jalur lama dipakai.
