pub mod fix;
pub mod kind;
pub mod providers;
pub mod snap;
pub mod snapshot;

use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jdouble, jdoubleArray, jfloat, jint, jlong, jlongArray, jobjectArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::sync::{Mutex, OnceLock};

use configcore::{codec::SALT, jitter};

fn seed() -> u64 {
    static SEED: OnceLock<u64> = OnceLock::new();
    *SEED.get_or_init(|| jitter::seed(SALT))
}

struct State {
    stamper: fix::Stamper,
    rng: u64,
}

fn state() -> &'static Mutex<State> {
    static STATE: OnceLock<Mutex<State>> = OnceLock::new();
    STATE.get_or_init(|| {
        let t = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0x9E37_79B9_7F4A_7C15);
        Mutex::new(State { stamper: fix::Stamper::new(), rng: t | 1 })
    })
}

fn next_int(rng: &mut u64, bound: i32) -> i32 {
    *rng ^= *rng >> 12;
    *rng ^= *rng << 25;
    *rng ^= *rng >> 27;
    let v = rng.wrapping_mul(0x2545_F491_4F6C_DD1D) >> 33;
    (v % bound.max(1) as u64) as i32
}

fn b(v: bool) -> jboolean {
    if v {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

fn opt_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.as_raw().is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn long_pair(env: &JNIEnv, a: i64, c: i64) -> jlongArray {
    match env.new_long_array(2) {
        Ok(arr) => {
            let _ = env.set_long_array_region(&arr, 0, &[a, c]);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

fn double_array(env: &JNIEnv, v: &[f64]) -> jdoubleArray {
    match env.new_double_array(v.len() as i32) {
        Ok(arr) => {
            let _ = env.set_double_array_region(&arr, 0, v);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeRoute(_e: JNIEnv, _c: JClass, kind: jint) -> jint {
    kind::route(kind).code()
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeIsSnapKind(_e: JNIEnv, _c: JClass, kind: jint) -> jboolean {
    b(kind::is_snap_kind(kind))
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeExtValue(
    _e: JNIEnv,
    _c: JClass,
    kind: jint,
    hide_net: jboolean,
    hide_bt: jboolean,
) -> jint {
    kind::ext_value(kind, hide_net != JNI_FALSE, hide_bt != JNI_FALSE).code()
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeActive(
    _e: JNIEnv,
    _c: JClass,
    parsing: jboolean,
    enabled: jboolean,
    has_pos: jboolean,
    sys_heartbeat: jboolean,
) -> jboolean {
    b(providers::active(parsing != JNI_FALSE, enabled != JNI_FALSE, has_pos != JNI_FALSE, sys_heartbeat != JNI_FALSE))
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeIsSpoofProvider(mut env: JNIEnv, _c: JClass, provider: JString) -> jboolean {
    b(opt_string(&mut env, &provider).map_or(false, |p| providers::is_spoof(&p)))
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeMergeProviders(mut env: JNIEnv, _c: JClass, items: JObjectArray) -> jobjectArray {
    let len = env.get_array_length(&items).unwrap_or(0);
    let mut existing: Vec<String> = Vec::with_capacity(len as usize);
    for i in 0..len {
        if let Ok(obj) = env.get_object_array_element(&items, i) {
            let js = JString::from(obj);
            if let Some(s) = opt_string(&mut env, &js) {
                existing.push(s);
            }
        }
    }
    let merged = providers::merge(&existing);
    let out = match env.new_object_array(merged.len() as i32, "java/lang/String", JObject::null()) {
        Ok(a) => a,
        Err(_) => return items.into_raw(),
    };
    for (i, s) in merged.iter().enumerate() {
        if let Ok(js) = env.new_string(s) {
            let _ = env.set_object_array_element(&out, i as i32, &js);
        }
    }
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeSnapDecide(
    mut env: JNIEnv,
    _c: JClass,
    ns: jlong,
    ms: jlong,
    provider: JString,
    has_mark: jboolean,
    mark_ns: jlong,
    mark_ms: jlong,
    wall_now_ms: jlong,
) -> jlong {
    let p = opt_string(&mut env, &provider);
    let mark = if has_mark != JNI_FALSE { Some(snap::Mark { ns: mark_ns, ms: mark_ms }) } else { None };
    match snap::decide(ns, ms, p.as_deref(), mark, wall_now_ms) {
        snap::Decision::Skip => -1,
        snap::Decision::Fix { ms } => ms,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeIsFix(mut env: JNIEnv, _c: JClass, ns: jlong, ms: jlong, provider: JString) -> jboolean {
    let p = opt_string(&mut env, &provider);
    b(snap::is_fix(ns, ms, p.as_deref()))
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeApply(
    mut env: JNIEnv,
    _c: JClass,
    provider: JString,
    lat: jdouble,
    lng: jdouble,
    spd: jfloat,
    brg: jfloat,
    alt: jdouble,
    acc_jit: jfloat,
    acc: jfloat,
    now_ms: jlong,
) -> jdoubleArray {
    let p = opt_string(&mut env, &provider).unwrap_or_default();
    let pt = fix::Pt { lat, lng, at: now_ms, spd, brg, alt, acc_jit };
    let a = fix::apply(&p, &pt, acc, seed(), now_ms);
    let mut out = [0.0f64; 13];
    out[0] = a.lat;
    out[1] = a.lng;
    out[2] = a.acc as f64;
    let mut flags = 0;
    if let Some(m) = a.motion {
        flags |= 1;
        out[4] = m.alt;
        out[5] = m.spd as f64;
        out[6] = m.v_acc as f64;
        out[7] = m.s_acc as f64;
        if let Some((brg, bacc)) = m.bearing {
            flags |= 2;
            out[8] = brg as f64;
            out[9] = bacc as f64;
        }
    }
    if let Some((sat, mean, third)) = a.extras {
        flags |= 4;
        out[10] = sat as f64;
        out[11] = mean as f64;
        out[12] = third as f64;
    }
    out[3] = flags as f64;
    double_array(&env, &out)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeStamp(
    mut env: JNIEnv,
    _c: JClass,
    provider: JString,
    now_ns: jlong,
    wall_ms: jlong,
) -> jlongArray {
    let p = opt_string(&mut env, &provider).unwrap_or_default();
    let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
    let State { stamper, rng } = &mut *g;
    let (ns, ms) = stamper.stamp(&p, now_ns, wall_ms, &mut |bound| next_int(rng, bound));
    drop(g);
    long_pair(&env, ns, ms)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeLastKnown(
    mut env: JNIEnv,
    _c: JClass,
    provider: JString,
    now_ns: jlong,
    wall_ms: jlong,
    phase_ns: jlong,
) -> jlongArray {
    let p = opt_string(&mut env, &provider).unwrap_or_default();
    let g = state().lock().unwrap_or_else(|e| e.into_inner());
    let (ns, ms) = g.stamper.last_known(&p, now_ns, wall_ms, phase_ns);
    drop(g);
    long_pair(&env, ns, ms)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativePhaseNs(_e: JNIEnv, _c: JClass, boot_ns: jlong) -> jlong {
    fix::phase_ns(seed(), boot_ns)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeSnapshot(
    env: JNIEnv,
    _c: JClass,
    lat: jdouble,
    lng: jdouble,
    spd: jfloat,
    brg: jfloat,
    alt: jdouble,
    alt_var: jfloat,
    random: jboolean,
    rr: jfloat,
    now_ms: jlong,
) -> jdoubleArray {
    let p = snapshot::renew(&snapshot::Inputs {
        lat,
        lng,
        spd,
        brg,
        alt,
        alt_var,
        random: random != JNI_FALSE,
        rr,
        now_ms,
        seed: seed(),
    });
    double_array(&env, &[p.lat, p.lng, p.at as f64, p.spd as f64, p.brg as f64, p.alt, p.acc_jit as f64])
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeSnapFresh(_e: JNIEnv, _c: JClass, at: jlong, now_ms: jlong) -> jboolean {
    b(snapshot::fresh(at, now_ms))
}
