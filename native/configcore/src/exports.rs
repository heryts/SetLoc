use jni::objects::{JClass, JString};
use jni::sys::{jdouble, jfloat, jstring};
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeTag(mut env: JNIEnv, _c: JClass, logical: JString) -> jstring {
    let l: String = env.get_string(&logical).map(Into::into).unwrap_or_default();
    env.new_string(crate::codec::tag(&l)).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeEnc(mut env: JNIEnv, _c: JClass, plain: JString) -> jstring {
    let p: String = env.get_string(&plain).map(Into::into).unwrap_or_default();
    env.new_string(crate::codec::enc(&p)).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeDec(mut env: JNIEnv, _c: JClass, hex: JString) -> jstring {
    let h: String = env.get_string(&hex).map(Into::into).unwrap_or_default();
    match crate::codec::dec(&h) {
        Some(v) => env.new_string(v).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeNetworkAccuracy(
    _env: JNIEnv,
    _c: JClass,
    seed: jni::sys::jlong,
    now_ms: jni::sys::jlong,
    gps_acc: jdouble,
) -> jdouble {
    crate::jitter::network_accuracy(seed as u64, now_ms, gps_acc)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeAccNow(_env: JNIEnv, _c: JClass, acc: jfloat, acc_jit: jfloat) -> jfloat {
    crate::jitter::acc_now(acc, acc_jit)
}

use crate::parse::{self, BeatState, Prev};
use crate::track::{self, Track};
use jni::objects::{JDoubleArray, JFloatArray, JLongArray};
use jni::sys::{jboolean, jdoubleArray, jint, jlong, JNI_FALSE, JNI_TRUE};
use std::ptr::null_mut;
use std::sync::Mutex;

static BEAT: Mutex<BeatState> = Mutex::new(BeatState { seen: None, changed_at: 0, armed: false, last_poll_at: 0 });
static TRACK: Mutex<Option<(i64, Track)>> = Mutex::new(None);

fn text(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.as_raw().is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn out_doubles(env: &mut JNIEnv, v: &[f64]) -> jdoubleArray {
    let arr = match env.new_double_array(v.len() as i32) {
        Ok(a) => a,
        Err(_) => return null_mut(),
    };
    if env.set_double_array_region(&arr, 0, v).is_err() {
        return null_mut();
    }
    arr.into_raw()
}

fn flag(b: bool) -> f64 {
    if b {
        1.0
    } else {
        0.0
    }
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeParse(
    mut env: JNIEnv,
    _c: JClass,
    s: JString,
    prev_lat: jdouble,
    prev_lng: jdouble,
    prev_acc: jfloat,
) -> jdoubleArray {
    let t = match text(&mut env, &s) {
        Some(t) => t,
        None => return null_mut(),
    };
    let p = match parse::parse_strict(&t, &Prev { lat: prev_lat, lng: prev_lng, acc: prev_acc }) {
        Some(p) => p,
        None => return null_mut(),
    };
    let v = [
        flag(p.enabled),
        flag(p.jitter),
        flag(p.playing),
        flag(p.tk),
        p.lat,
        p.lng,
        p.acc as f64,
        p.spd as f64,
        p.brg as f64,
        p.alt,
        p.altv as f64,
        p.rr as f64,
    ];
    out_doubles(&mut env, &v)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeParseTrack(mut env: JNIEnv, _c: JClass, s: JString, hdr: JLongArray) -> jdoubleArray {
    let t = match text(&mut env, &s) {
        Some(t) => t,
        None => return null_mut(),
    };
    let tr = match track::parse_track(&t) {
        Some(tr) => tr,
        None => return null_mut(),
    };
    let n = tr.n();
    if n > (i32::MAX as usize) / 5 {
        return null_mut();
    }
    let mut flat = Vec::with_capacity(n * 5);
    for i in 0..n {
        flat.push(tr.la[i]);
        flat.push(tr.lo[i]);
        flat.push(tr.al[i]);
        flat.push(tr.sp[i] as f64);
        flat.push(tr.br[i] as f64);
    }
    let h = [tr.t0, tr.wrote, tr.tk, n as i64];
    if env.set_long_array_region(&hdr, 0, &h).is_err() {
        return null_mut();
    }
    out_doubles(&mut env, &flat)
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeTrackSet(
    env: JNIEnv,
    _c: JClass,
    generation: jlong,
    t0: jlong,
    tk: jint,
    n: jint,
    la: JDoubleArray,
    lo: JDoubleArray,
    al: JDoubleArray,
    sp: JFloatArray,
    br: JFloatArray,
) -> jboolean {
    if n < 1 || n > 4_000_000 || tk <= 0 {
        return JNI_FALSE;
    }
    let n = n as usize;
    let mut tr = Track { t0, wrote: t0, tk: tk as i64, la: vec![0.0; n], lo: vec![0.0; n], al: vec![0.0; n], sp: vec![0.0; n], br: vec![0.0; n] };
    let ok = env.get_double_array_region(&la, 0, &mut tr.la).is_ok()
        && env.get_double_array_region(&lo, 0, &mut tr.lo).is_ok()
        && env.get_double_array_region(&al, 0, &mut tr.al).is_ok()
        && env.get_float_array_region(&sp, 0, &mut tr.sp).is_ok()
        && env.get_float_array_region(&br, 0, &mut tr.br).is_ok();
    if !ok {
        return JNI_FALSE;
    }
    *TRACK.lock().unwrap_or_else(|e| e.into_inner()) = Some((generation, tr));
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeTrackClear(_env: JNIEnv, _c: JClass) {
    *TRACK.lock().unwrap_or_else(|e| e.into_inner()) = None;
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeApplyTrack(mut env: JNIEnv, _c: JClass, generation: jlong, now_ms: jlong, stale: jint) -> jdoubleArray {
    let a = {
        let g = TRACK.lock().unwrap_or_else(|e| e.into_inner());
        match g.as_ref() {
            Some((gen, tr)) if *gen == generation => track::apply_track(tr, now_ms, stale),
            _ => return null_mut(),
        }
    };
    out_doubles(&mut env, &[a.lat, a.lng, a.alt, a.spd as f64, a.brg as f64])
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeStaleLevel(_env: JNIEnv, _c: JClass, late_ms: jlong) -> jint {
    track::stale_level(late_ms)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeBeatCheck(mut env: JNIEnv, _c: JClass, beat: JString, playing: jboolean, now: jlong) -> jint {
    let b = if beat.as_raw().is_null() {
        None
    } else {
        match env.get_string(&beat) {
            Ok(s) => Some(String::from(s)),
            Err(_) => return -1,
        }
    };
    let mut st = BEAT.lock().unwrap_or_else(|e| e.into_inner());
    let (level, freeze) = parse::check_beat(b.as_deref(), playing != JNI_FALSE, now, &mut st);
    level | if freeze { 4 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeBeatDisarm(_env: JNIEnv, _c: JClass) {
    BEAT.lock().unwrap_or_else(|e| e.into_inner()).armed = false;
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeBeatReset(_env: JNIEnv, _c: JClass) {
    *BEAT.lock().unwrap_or_else(|e| e.into_inner()) = BeatState::default();
}
