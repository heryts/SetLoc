# gpsz (Rust) — rencana port module/zygote

Status: **tahap 1 selesai ditulis, belum pernah dikompilasi** (sandbox tanpa cargo/NDK).
Jalankan `cargo test` di host dulu; semua test tahap 1 murni std.

## Pemetaan Java -> Rust

| Java (module/zygote) | Rust | Tahap |
|---|---|---|
| Config.tag/enc/dec, SALT | gpsz.rs `codec` (SALT konstanta, samakan dengan ../../.salt) | 1 ✔ |
| Config.hashUnit/gauss/wave/jitter/gnssExtras/network*/altitudeNow | gpsz.rs `prng` | 1 ✔ |
| Config.parse/applyFlags/readFileFields/tkOn | gpsz.rs `cfg` | 1 ✔ |
| Config.parseTrack/loadTrackFile/withFile/trackStale/applyTrack | gpsz.rs `track` | 1 ✔ |
| Config.renewSnapshot, acc/vAcc/sAcc/bAcc | gpsz.rs `snapshot` | 1 ✔ |
| Fix.apply/stamp/lastKnown (logika) | gpsz.rs `fix` | 1 ✔ |
| Config.refresh/startPolling/observe/beat/live (Settings.Global, ContentObserver, thread) | config_rt.rs (state global + thread + JNI ke Settings.Global) | 2 |
| ZygoteEntry (premain/main, ZygoteLoader.getPackageName) | zygisk.rs (pre/postAppSpecialize, preServerSpecialize) | 2 |
| Fix (penulisan ke Location, reflection setMock/removeMsl*) | loc_jni.rs | 2 |
| LocationHooks (914 baris) | hooks/location.rs | 3 |
| GnssFake (667) | hooks/gnss.rs | 3 |
| SettingsGuard (487) | hooks/settings.rs | 4 |
| PackageGuard (261) | hooks/package.rs | 4 |
| SystemHooks (584, proses `android`) | hooks/system.rs | 4 |
| WeakIdentityMap | std::collections::HashMap<usize,_> + global ref JNI | 3 |

## Kenapa tahap 2-4 masih butuh sedikit Java

Hook method Java dari native lewat LSPlant mensyaratkan sebuah objek hooker Java dengan satu method
callback. Jadi ada dex kecil (±40 baris, tanpa logika bisnis):

```java
public final class Tramp {
    public static native Object dispatch(int id, Object self, Object[] args);
    public Object callback(Object[] a) { return dispatch(id, a[0], rest(a)); }  // id diisi per hook
}
```

Seluruh logika (apa yang dipalsukan, kapan, nilai berapa) tetap di Rust. Dex ini disuntik lewat
`InMemoryDexClassLoader` dari byte yang ditanam di .so, jadi tidak ada file tambahan.

## Hal yang HARUS dicek sebelum tahap 2

1. Layout `api_table` / `module_abi` Zygisk: salin dari `zygisk.hpp` versi Magisk/KernelSU/APatch
   yang kamu targetkan (API v4+). Jangan ditebak dari ingatan.
2. LSPlant butuh inline hooker (ShadowHook atau Dobby) dan NDK r26+. Build via `cargo-ndk` + CMake
   untuk LSPlant, di-link statis ke crate.
3. Nama thread kamuflase (`pool-4-thread-N`) dan nama tag acak dipertahankan apa adanya di port.

## Build

    cd module/zygote-rs
    cargo test                       # tahap 1, host
    # tahap 2+: cargo ndk -t arm64-v8a -t armeabi-v7a build --release
