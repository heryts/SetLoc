use crate::parse::{self, BeatState, Prev};
use crate::track::{self, Track};
use jni::objects::{JClass, JDoubleArray, JFloatArray, JLongArray, JString};
use jni::sys::{jboolean, jdouble, jdoubleArray, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::ptr::null_mut;
use std::sync::Mutex;
fn out_string(env: &mut JNIEnv, v: Option<String>) -> jstring {
    v.and_then(|v| env.new_string(v).ok()).map_or(null_mut(), |s| s.into_raw())
}

fn in_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeTag(mut env: JNIEnv, _c: JClass, logical: JString) -> jstring {
    let l = in_string(&mut env, &logical);
    out_string(&mut env, Some(crate::codec::tag(&l)))
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeEnc(mut env: JNIEnv, _c: JClass, plain: JString) -> jstring {
    let p = in_string(&mut env, &plain);
    out_string(&mut env, Some(crate::codec::enc(&p)))
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeDec(mut env: JNIEnv, _c: JClass, hex: JString) -> jstring {
    let h = in_string(&mut env, &hex);
    out_string(&mut env, crate::codec::dec(&h))
}


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
        flat.extend_from_slice(&[tr.la[i], tr.lo[i], tr.al[i], tr.sp[i] as f64, tr.br[i] as f64]);
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
    if !(1..=4_000_000).contains(&n) || tk <= 0 {
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
