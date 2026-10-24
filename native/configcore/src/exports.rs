use crate::state::{self, Core, Inputs, OUT_LEN};
use crate::track::{self, Track};
use jni::objects::{JClass, JDoubleArray, JLongArray, JString};
use jni::sys::{jboolean, jdouble, jdoubleArray, jfloat, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::ptr::null_mut;
use std::sync::{Mutex, MutexGuard};

static CORE: Mutex<Core> = Mutex::new(Core::new());

fn core() -> MutexGuard<'static, Core> {
    CORE.lock().unwrap_or_else(|e| e.into_inner())
}

fn out_string(env: &mut JNIEnv, v: Option<String>) -> jstring {
    v.and_then(|v| env.new_string(v).ok()).map_or(null_mut(), |s| s.into_raw())
}

fn in_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

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

fn put(env: &mut JNIEnv, out: &JDoubleArray, v: &[f64; OUT_LEN]) -> jboolean {
    if env.set_double_array_region(out, 0, v).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

fn put_opt(env: &mut JNIEnv, out: &JDoubleArray, v: Option<[f64; OUT_LEN]>) -> jboolean {
    match v {
        Some(v) => put(env, out, &v),
        None => JNI_FALSE,
    }
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

fn out_track(env: &mut JNIEnv, tr: Track, hdr: &JLongArray) -> jdoubleArray {
    let n = tr.n();
    if n > (i32::MAX as usize) / 5 {
        return null_mut();
    }
    let mut flat = Vec::with_capacity(n * 5);
    for i in 0..n {
        flat.extend_from_slice(&[tr.la[i], tr.lo[i], tr.al[i], tr.sp[i] as f64, tr.br[i] as f64]);
    }
    let h = [tr.t0, tr.wrote, tr.tk, n as i64];
    if env.set_long_array_region(hdr, 0, &h).is_err() {
        return null_mut();
    }
    out_doubles(env, &flat)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeParseTrack(mut env: JNIEnv, _c: JClass, s: JString, hdr: JLongArray) -> jdoubleArray {
    let t = match text(&mut env, &s) {
        Some(t) => t,
        None => return null_mut(),
    };
    match track::parse_track(&t) {
        Some(tr) => out_track(&mut env, tr, &hdr),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeCoreSelfTest(_env: JNIEnv, _c: JClass) -> jboolean {
    if state::self_test() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeParseCfg(
    mut env: JNIEnv,
    _c: JClass,
    s: JString,
    lat: jdouble,
    lng: jdouble,
    acc: jfloat,
    out: JDoubleArray,
) -> jboolean {
    let t = text(&mut env, &s);
    let r = core().parse_with(t.as_deref(), lat, lng, acc);
    put(&mut env, &out, &r)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeCheckBeat(mut env: JNIEnv, _c: JClass, beat: JString, now: jlong, out: JDoubleArray) -> jboolean {
    let b = text(&mut env, &beat);
    let r = core().beat_with(b.as_deref(), now);
    put(&mut env, &out, &r)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeTick(mut env: JNIEnv, _c: JClass, now: jlong, uptime: jlong, out: JDoubleArray) -> jboolean {
    let r = core().tick(now, uptime);
    put_opt(&mut env, &out, r)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeLoadFile(mut env: JNIEnv, _c: JClass, out: JDoubleArray) -> jboolean {
    let r = core().load_file();
    put_opt(&mut env, &out, r)
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_gpsz_zygote_ConfigNative_nativeRefresh(
    mut env: JNIEnv,
    _c: JClass,
    cfg: JString,
    trk: JString,
    beat: JString,
    net: JString,
    bt: JString,
    sys: JString,
    use_file: jboolean,
    now: jlong,
    uptime: jlong,
    trk_path: JString,
    out: JDoubleArray,
) -> jboolean {
    let cfg = text(&mut env, &cfg);
    let trk = text(&mut env, &trk);
    let beat = text(&mut env, &beat);
    let net = text(&mut env, &net);
    let bt = text(&mut env, &bt);
    let sys = text(&mut env, &sys);
    let path = text(&mut env, &trk_path).unwrap_or_default();
    let r = core().refresh(&Inputs {
        cfg: cfg.as_deref(),
        trk: trk.as_deref(),
        beat: beat.as_deref(),
        net: net.as_deref(),
        bt: bt.as_deref(),
        sys: sys.as_deref(),
        use_file: use_file != JNI_FALSE,
        now,
        uptime,
        trk_path: &path,
    });
    put(&mut env, &out, &r)
}
