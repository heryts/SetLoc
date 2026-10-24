pub mod envsim;
pub mod feed;
pub mod fix;
pub mod gnss;
pub mod snapshot;

use jni::objects::{JClass, JDoubleArray, JLongArray, JObject, JString};
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

fn long_array(env: &JNIEnv, v: &[i64]) -> jlongArray {
    match env.new_long_array(v.len() as i32) {
        Ok(arr) => {
            let _ = env.set_long_array_region(&arr, 0, v);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

fn read_snap(env: &JNIEnv, a: &JDoubleArray) -> Option<gnss::Snap> {
    if a.as_raw().is_null() {
        return None;
    }
    let len = env.get_array_length(a).ok()?;
    if len < 1 || len as usize > 1 + gnss::MAX_SATS * gnss::STRIDE_IN {
        return None;
    }
    let mut buf = vec![0.0f64; len as usize];
    env.get_double_array_region(a, 0, &mut buf).ok()?;
    gnss::unpack_in(&buf)
}

fn gnss_pool() -> &'static Vec<gnss::SatDef> {
    static POOL: OnceLock<Vec<gnss::SatDef>> = OnceLock::new();
    POOL.get_or_init(|| gnss::build_pool(seed() as i64))
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

#[allow(clippy::too_many_arguments)]
fn apply_out(p: &str, lat: f64, lng: f64, spd: f32, brg: f32, alt: f64, acc_jit: f32, acc: f32, now_ms: i64) -> [f64; 13] {
    let pt = fix::Pt { lat, lng, at: now_ms, spd, brg, alt, acc_jit };
    let a = fix::apply(p, &pt, acc, seed(), now_ms);
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
    out
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
    let out = apply_out(&p, lat, lng, spd, brg, alt, acc_jit, acc, now_ms);
    double_array(&env, &out)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeApplyInto(
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
    out: JDoubleArray,
) -> jboolean {
    if out.as_raw().is_null() {
        return JNI_FALSE;
    }
    match env.get_array_length(&out) {
        Ok(n) if n >= 13 => {}
        _ => return JNI_FALSE,
    }
    let p = opt_string(&mut env, &provider).unwrap_or_default();
    let a = apply_out(&p, lat, lng, spd, brg, alt, acc_jit, acc, now_ms);
    if env.set_double_array_region(&out, 0, &a).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
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

fn pair_into(env: &JNIEnv, out: &JLongArray, a: i64, c: i64) -> jboolean {
    if env.set_long_array_region(out, 0, &[a, c]).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

fn pair_ok(env: &JNIEnv, out: &JLongArray) -> bool {
    if out.as_raw().is_null() {
        return false;
    }
    matches!(env.get_array_length(out), Ok(n) if n >= 2)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeStampInto(
    mut env: JNIEnv,
    _c: JClass,
    provider: JString,
    now_ns: jlong,
    wall_ms: jlong,
    out: JLongArray,
) -> jboolean {
    if !pair_ok(&env, &out) {
        return JNI_FALSE;
    }
    let p = opt_string(&mut env, &provider).unwrap_or_default();
    let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
    let State { stamper, rng } = &mut *g;
    let (ns, ms) = stamper.stamp(&p, now_ns, wall_ms, &mut |bound| next_int(rng, bound));
    drop(g);
    pair_into(&env, &out, ns, ms)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeLastKnownInto(
    mut env: JNIEnv,
    _c: JClass,
    provider: JString,
    now_ns: jlong,
    wall_ms: jlong,
    phase_ns: jlong,
    out: JLongArray,
) -> jboolean {
    if !pair_ok(&env, &out) {
        return JNI_FALSE;
    }
    let p = opt_string(&mut env, &provider).unwrap_or_default();
    let g = state().lock().unwrap_or_else(|e| e.into_inner());
    let (ns, ms) = g.stamper.last_known(&p, now_ns, wall_ms, phase_ns);
    drop(g);
    pair_into(&env, &out, ns, ms)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeHashUnit(_e: JNIEnv, _c: JClass, a: jlong, b: jlong) -> jdouble {
    jitter::hash_unit(seed(), a, b)
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeFeedPlan(env: JNIEnv, _c: JClass, kind: jint, minute: jlong) -> jlongArray {
    match feed::plan(seed(), kind, minute) {
        Some(p) => long_array(&env, &[p.initial_ms, p.period_ms, p.ttff_ms]),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeFeedStep(_e: JNIEnv, _c: JClass, kind: jint, live: jboolean, state: jint, elapsed_ms: jlong, ttff_ms: jlong) -> jlong {
    match feed::step(kind, live != JNI_FALSE, state as u32, elapsed_ms, ttff_ms) {
        Some(s) => feed::pack_step(s),
        None => -1,
    }
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
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeGnssSnapshot(env: JNIEnv, _c: JClass, now_sec: jlong, fixed: jboolean) -> jdoubleArray {
    let s = gnss::snapshot(seed(), gnss_pool(), now_sec, fixed != JNI_FALSE);
    double_array(&env, &gnss::pack_out(&s))
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeGnssNmea(
    mut env: JNIEnv,
    _c: JClass,
    snap: JDoubleArray,
    ms: jlong,
    lat: jdouble,
    lng: jdouble,
    acc: jfloat,
    has_spd: jboolean,
    spd: jfloat,
    has_brg: jboolean,
    brg: jfloat,
    has_alt: jboolean,
    alt: jdouble,
) -> jobjectArray {
    let s = match read_snap(&env, &snap) {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    let f = gnss::FixIn {
        lat,
        lng,
        acc,
        spd: if has_spd != JNI_FALSE { Some(spd) } else { None },
        brg: if has_brg != JNI_FALSE { Some(brg) } else { None },
        alt: if has_alt != JNI_FALSE { Some(alt) } else { None },
    };
    let lines = gnss::nmea(&s, &f, ms);
    let out = match env.new_object_array(lines.len() as i32, "java/lang/String", JObject::null()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, l) in lines.iter().enumerate() {
        match env.new_string(l) {
            Ok(js) => {
                if env.set_object_array_element(&out, i as i32, &js).is_err() {
                    return std::ptr::null_mut();
                }
            }
            Err(_) => return std::ptr::null_mut(),
        }
    }
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeGnssMeas(env: JNIEnv, _c: JClass, snap: JDoubleArray, now_ms: jlong, time_ns: jlong) -> jlongArray {
    match read_snap(&env, &snap) {
        Some(s) => long_array(&env, &gnss::meas(seed(), &s, now_ms, time_ns)),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LocationNative_nativeWifi(
    mut env: JNIEnv,
    _c: JClass,
    lat: jdouble,
    lng: jdouble,
    now_ms: jlong,
    meta: jni::objects::JIntArray,
) -> jobjectArray {
    let null = std::ptr::null_mut();
    if meta.as_raw().is_null() || !lat.is_finite() || !lng.is_finite() {
        return null;
    }
    match env.get_array_length(&meta) {
        Ok(n) if n as usize >= envsim::MAX_APS * 2 => {}
        _ => return null,
    }
    let aps = envsim::wifi_for(lat, lng, now_ms);
    if aps.len() > envsim::MAX_APS {
        return null;
    }
    let mut m = Vec::with_capacity(aps.len() * 2);
    for a in &aps {
        m.push(a.rssi);
        m.push(a.freq);
    }
    if env.set_int_array_region(&meta, 0, &m).is_err() {
        return null;
    }
    let out = match env.new_object_array(aps.len() as i32 * 2, "java/lang/String", JObject::null()) {
        Ok(a) => a,
        Err(_) => return null,
    };
    for (i, a) in aps.iter().enumerate() {
        for (j, s) in [&a.ssid, &a.bssid].into_iter().enumerate() {
            match env.new_string(s) {
                Ok(js) => {
                    if env.set_object_array_element(&out, (i * 2 + j) as i32, &js).is_err() {
                        return null;
                    }
                }
                Err(_) => return null,
            }
        }
    }
    out.into_raw()
}
