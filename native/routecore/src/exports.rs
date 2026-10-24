use crate::geo::Pt;
use crate::timeline::{self, Rng, Tick};
use jni::objects::{JClass, JDoubleArray, JFloatArray};
use jni::sys::{jdoubleArray, jfloat, jint, jlong, jstring};
use jni::JNIEnv;
use std::ptr::null_mut;

const STRIDE: usize = 6;

fn read_doubles(env: &mut JNIEnv, a: &JDoubleArray) -> Option<Vec<f64>> {
    if a.as_raw().is_null() {
        return None;
    }
    let n = env.get_array_length(a).ok()? as usize;
    let mut v = vec![0.0f64; n];
    env.get_double_array_region(a, 0, &mut v).ok()?;
    Some(v)
}

fn read_floats(env: &mut JNIEnv, a: &JFloatArray) -> Vec<f32> {
    if a.as_raw().is_null() {
        return Vec::new();
    }
    let n = match env.get_array_length(a) {
        Ok(n) => n as usize,
        Err(_) => return Vec::new(),
    };
    let mut v = vec![0.0f32; n];
    if env.get_float_array_region(a, 0, &mut v).is_err() {
        return Vec::new();
    }
    v
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeBuild(
    mut env: JNIEnv,
    _c: JClass,
    pts: JDoubleArray,
    speed_kmh: jfloat,
    var_kmh: jfloat,
    elev: JFloatArray,
    manual: jfloat,
    seed: jlong,
) -> jdoubleArray {
    let raw = match read_doubles(&mut env, &pts) {
        Some(v) if v.len() >= 4 && v.len() % 2 == 0 => v,
        _ => return null_mut(),
    };
    let points: Vec<Pt> = raw.chunks_exact(2).map(|c| (c[0], c[1])).collect();
    if !speed_kmh.is_finite() || !var_kmh.is_finite() || timeline::too_long(&points, speed_kmh) {
        return null_mut();
    }
    let e = read_floats(&mut env, &elev);
    let ticks = timeline::build(&points, speed_kmh, var_kmh, &e, manual, &mut Rng::new(seed as u64));
    let mut flat = Vec::with_capacity(ticks.len() * STRIDE);
    for t in &ticks {
        flat.extend_from_slice(&[t.lat, t.lng, t.spd as f64, t.brg as f64, t.alt as f64, t.pct as f64]);
    }
    let out = match env.new_double_array(flat.len() as i32) {
        Ok(a) => a,
        Err(_) => return null_mut(),
    };
    if env.set_double_array_region(&out, 0, &flat).is_err() {
        return null_mut();
    }
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeFormat(
    mut env: JNIEnv,
    _c: JClass,
    flat: JDoubleArray,
    from: jint,
    count: jint,
) -> jstring {
    let v = match read_doubles(&mut env, &flat) {
        Some(v) => v,
        None => return null_mut(),
    };
    let total = v.len() / STRIDE;
    if from < 0 || count < 0 || (from as usize).saturating_add(count as usize) > total {
        return null_mut();
    }
    let mut s = String::with_capacity(count as usize * timeline::LINE_WIDTH);
    for i in from as usize..(from + count) as usize {
        let r = &v[i * STRIDE..i * STRIDE + STRIDE];
        let t = Tick { lat: r[0], lng: r[1], spd: r[2] as f32, brg: r[3] as f32, alt: r[4] as f32, pct: r[5] as i32 };
        timeline::format_line(&mut s, &t);
    }
    env.new_string(s).map_or(null_mut(), |j| j.into_raw())
}
