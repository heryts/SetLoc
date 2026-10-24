use crate::geo::{self, Pt};
use crate::timeline::{self, Rng, Tick};
use jni::objects::{JByteArray, JClass, JDoubleArray, JFloatArray, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jdoubleArray, jfloat, jfloatArray, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
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

fn read_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.as_raw().is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativePolylineComplete(mut env: JNIEnv, _c: JClass, s: JString) -> jboolean {
    match read_string(&mut env, &s) {
        Some(t) if crate::polyline::is_complete(&t) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativePolylineDecode(mut env: JNIEnv, _c: JClass, s: JString) -> jdoubleArray {
    let t = match read_string(&mut env, &s) {
        Some(t) => t,
        None => return null_mut(),
    };
    let pts = crate::polyline::decode(&t);
    let mut flat = Vec::with_capacity(pts.len() * 2);
    for p in &pts {
        flat.push(p.0);
        flat.push(p.1);
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
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativePolylineEncode(mut env: JNIEnv, _c: JClass, pts: JDoubleArray) -> jstring {
    let raw = match read_doubles(&mut env, &pts) {
        Some(v) if v.len() % 2 == 0 => v,
        _ => return null_mut(),
    };
    let points: Vec<(f64, f64)> = raw.chunks_exact(2).map(|c| (c[0], c[1])).collect();
    env.new_string(crate::polyline::encode(&points)).map_or(null_mut(), |j| j.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeAutoSpeed(_env: JNIEnv, _c: JClass, mode: jint, distance_m: jdouble, duration_s: jdouble) -> jfloat {
    let d = if duration_s.is_nan() { None } else { Some(duration_s) };
    crate::speed::auto_kmh(mode, distance_m, d)
}

fn read_points(env: &mut JNIEnv, a: &JDoubleArray) -> Option<Vec<Pt>> {
    match read_doubles(env, a) {
        Some(v) if v.len() % 2 == 0 => Some(v.chunks_exact(2).map(|c| (c[0], c[1])).collect()),
        _ => None,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeRouteLength(mut env: JNIEnv, _c: JClass, pts: JDoubleArray) -> jdouble {
    read_points(&mut env, &pts).map_or(0.0, |p| geo::route_length(&p))
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeTooLong(mut env: JNIEnv, _c: JClass, pts: JDoubleArray, speed_kmh: jfloat) -> jboolean {
    match read_points(&mut env, &pts) {
        Some(p) if timeline::too_long(&p, speed_kmh) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeBearing(_env: JNIEnv, _c: JClass, lat1: jdouble, lng1: jdouble, lat2: jdouble, lng2: jdouble) -> jfloat {
    geo::bearing((lat1, lng1), (lat2, lng2))
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeAltitudeAt(mut env: JNIEnv, _c: JClass, elev: JFloatArray, manual: jfloat, fraction: jdouble) -> jfloat {
    let e = read_floats(&mut env, &elev);
    timeline::altitude_at(&e, manual, fraction)
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeSample(mut env: JNIEnv, _c: JClass, pts: JDoubleArray, n: jint, max: jint) -> jdoubleArray {
    let points = match read_points(&mut env, &pts) {
        Some(p) => p,
        None => return null_mut(),
    };
    let mut flat = Vec::with_capacity(points.len() * 2);
    for p in geo::sample(&points, n, max) {
        flat.push(p.0);
        flat.push(p.1);
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
pub extern "system" fn Java_com_heryts_setloc_favourite_StoreNative_nativeParse(mut env: JNIEnv, _c: JClass, kind: jint, raw: JString) -> jbyteArray {
    let text = match read_string(&mut env, &raw) {
        Some(t) => t,
        None => return null_mut(),
    };
    match crate::store::parse_blob(kind, &text) {
        Some(blob) => env.byte_array_from_slice(&blob).map_or(null_mut(), |a| a.into_raw()),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_favourite_StoreNative_nativeEncode(env: JNIEnv, _c: JClass, kind: jint, blob: JByteArray) -> jstring {
    if blob.as_raw().is_null() {
        return null_mut();
    }
    let bytes = match env.convert_byte_array(&blob) {
        Ok(b) => b,
        Err(_) => return null_mut(),
    };
    match crate::store::encode_blob(kind, &bytes) {
        Some(s) => env.new_string(s).map_or(null_mut(), |j| j.into_raw()),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeRoute(mut env: JNIEnv, _c: JClass, raw: JString) -> jbyteArray {
    let text = match read_string(&mut env, &raw) {
        Some(t) => t,
        None => return null_mut(),
    };
    match crate::net::parse_route(&text) {
        Some(blob) => env.byte_array_from_slice(&blob).map_or(null_mut(), |a| a.into_raw()),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeErrorMessage(mut env: JNIEnv, _c: JClass, raw: JString) -> jstring {
    let text = match read_string(&mut env, &raw) {
        Some(t) => t,
        None => return null_mut(),
    };
    match crate::net::error_message(&text) {
        Some(m) => env.new_string(m).map_or(null_mut(), |j| j.into_raw()),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeElevation(mut env: JNIEnv, _c: JClass, raw: JString, n: jint, google: jboolean) -> jfloatArray {
    let text = match read_string(&mut env, &raw) {
        Some(t) if n >= 0 => t,
        _ => return null_mut(),
    };
    let v = match crate::net::parse_elevation(&text, n as usize, google != JNI_FALSE) {
        Some(v) => v,
        None => return null_mut(),
    };
    let out = match env.new_float_array(v.len() as i32) {
        Ok(a) => a,
        Err(_) => return null_mut(),
    };
    if env.set_float_array_region(&out, 0, &v).is_err() {
        return null_mut();
    }
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativePlaces(mut env: JNIEnv, _c: JClass, kind: jint, raw: JString, fallback: JString) -> jbyteArray {
    let text = match read_string(&mut env, &raw) {
        Some(t) => t,
        None => return null_mut(),
    };
    let fb = read_string(&mut env, &fallback).unwrap_or_default();
    match crate::net::parse_places(kind, &text, &fb) {
        Some(blob) => env.byte_array_from_slice(&blob).map_or(null_mut(), |a| a.into_raw()),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_CodecNative_nativeTag(mut env: JNIEnv, _c: JClass, logical: JString) -> jstring {
    let text = match read_string(&mut env, &logical) {
        Some(t) => t,
        None => return null_mut(),
    };
    env.new_string(configcore::codec::tag(&text)).map_or(null_mut(), |j| j.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_CodecNative_nativeEnc(mut env: JNIEnv, _c: JClass, plain: JString) -> jstring {
    let text = match read_string(&mut env, &plain) {
        Some(t) => t,
        None => return null_mut(),
    };
    env.new_string(configcore::codec::enc(&text)).map_or(null_mut(), |j| j.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_CodecNative_nativeDec(mut env: JNIEnv, _c: JClass, hex: JString) -> jstring {
    let text = match read_string(&mut env, &hex) {
        Some(t) => t,
        None => return null_mut(),
    };
    match configcore::codec::dec(&text) {
        Some(v) => env.new_string(v).map_or(null_mut(), |j| j.into_raw()),
        None => null_mut(),
    }
}

fn flag(v: bool) -> jboolean {
    if v {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativePlan(
    env: JNIEnv,
    _c: JClass,
    wait_start_min: jfloat,
    wait_end_min: jfloat,
    n: jint,
    chars: jlong,
) -> jni::sys::jlongArray {
    let p = crate::playback::plan(wait_start_min, wait_end_min, n as i64, chars);
    let v = [p.wait_ms, p.auto_stop as i64, p.end_wait_ms, p.line_width, p.hold_end_ms];
    let out = match env.new_long_array(v.len() as i32) {
        Ok(a) => a,
        Err(_) => return null_mut(),
    };
    if env.set_long_array_region(&out, 0, &v).is_err() {
        return null_mut();
    }
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeTickAt(_env: JNIEnv, _c: JClass, now: jlong, t0: jlong, n: jint) -> jint {
    crate::playback::tick_at(now, t0, n as i64) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeSleepMs(_env: JNIEnv, _c: JClass, now: jlong, t0: jlong, k: jint) -> jlong {
    crate::playback::sleep_ms(now, t0, k as i64)
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativeEndAt(_env: JNIEnv, _c: JClass, t0: jlong, n: jint, end_wait_ms: jlong) -> jlong {
    crate::playback::end_at(t0, n as i64, end_wait_ms)
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_route_RouteNative_nativePrefDue(_env: JNIEnv, _c: JClass, thin: jboolean, counter: jint) -> jboolean {
    flag(crate::playback::pref_due(thin != JNI_FALSE, counter as i64))
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_PrefNative_nativeSpeedVar(_env: JNIEnv, _c: JClass, unit: jint, speed_kmh: jfloat, raw: jfloat) -> jfloat {
    crate::prefs::speed_var(unit, speed_kmh, raw)
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_PrefNative_nativeStateGet(mut env: JNIEnv, _c: JClass, path: JString, key: JString) -> jstring {
    let (Some(path), Some(key)) = (read_string(&mut env, &path), read_string(&mut env, &key)) else {
        return null_mut();
    };
    match crate::appstate::load(std::path::Path::new(&path)).get(&key) {
        Some(v) => jstr(&mut env, v.to_string()),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_PrefNative_nativeStatePatch(mut env: JNIEnv, _c: JClass, path: JString, patch: JString) -> jboolean {
    let (Some(path), Some(patch)) = (read_string(&mut env, &path), read_string(&mut env, &patch)) else {
        return JNI_FALSE;
    };
    flag(crate::appstate::update(std::path::Path::new(&path), &patch, &|_| {}))
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_PrefNative_nativeRouteState(mut env: JNIEnv, _c: JClass, path: JString, boot_now: jint, now_ms: jlong) -> jint {
    match read_string(&mut env, &path) {
        Some(p) => crate::appstate::load(std::path::Path::new(&p)).route_state(boot_now as i64, now_ms),
        None => crate::prefs::ROUTE_NONE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_PrefNative_nativeCfgOff(env: JNIEnv, _c: JClass, lat: jdouble, lng: jdouble, acc: jfloat) -> jstring {
    env.new_string(crate::prefs::cfg_off(lat, lng, acc)).map_or(null_mut(), |j| j.into_raw())
}

static ELEV_CACHE: std::sync::Mutex<crate::client::Lru> = std::sync::Mutex::new(crate::client::Lru::new(crate::client::ELEV_CACHE_SIZE));

fn jstr(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s).map_or(null_mut(), |j| j.into_raw())
}

fn pairs(raw: Vec<f64>) -> Option<Vec<Pt>> {
    if raw.len() % 2 != 0 {
        return None;
    }
    Some(raw.chunks_exact(2).map(|c| (c[0], c[1])).collect())
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeRouteBody(
    mut env: JNIEnv,
    _c: JClass,
    stops: JDoubleArray,
    mode: jint,
    traffic: jboolean,
) -> jstring {
    let pts = match read_doubles(&mut env, &stops).and_then(pairs) {
        Some(p) => p,
        None => return null_mut(),
    };
    match crate::client::route_body(&pts, mode, traffic != JNI_FALSE) {
        Some(b) => jstr(&mut env, b),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeElevationUrlGoogle(mut env: JNIEnv, _c: JClass, encoded: JString, key: JString) -> jstring {
    let (e, k) = match (read_string(&mut env, &encoded), read_string(&mut env, &key)) {
        (Some(e), Some(k)) => (e, k),
        _ => return null_mut(),
    };
    jstr(&mut env, crate::client::elevation_url_google(&e, &k))
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeElevationUrlOpenMeteo(mut env: JNIEnv, _c: JClass, pts: JDoubleArray) -> jstring {
    let p = match read_doubles(&mut env, &pts).and_then(pairs) {
        Some(p) if !p.is_empty() => p,
        _ => return null_mut(),
    };
    jstr(&mut env, crate::client::elevation_url_open_meteo(&p))
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeElevCacheGet(mut env: JNIEnv, _c: JClass, key: JString) -> jfloatArray {
    let k = match read_string(&mut env, &key) {
        Some(k) => k,
        None => return null_mut(),
    };
    let hit = ELEV_CACHE.lock().unwrap_or_else(|e| e.into_inner()).get(&k);
    let v = match hit {
        Some(v) => v,
        None => return null_mut(),
    };
    let out = match env.new_float_array(v.len() as i32) {
        Ok(a) => a,
        Err(_) => return null_mut(),
    };
    if env.set_float_array_region(&out, 0, &v).is_err() {
        return null_mut();
    }
    out.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeElevCachePut(mut env: JNIEnv, _c: JClass, key: JString, values: JFloatArray) {
    let k = match read_string(&mut env, &key) {
        Some(k) => k,
        None => return,
    };
    let v = read_floats(&mut env, &values);
    ELEV_CACHE.lock().unwrap_or_else(|e| e.into_inner()).put(&k, v);
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeQuery(mut env: JNIEnv, _c: JClass, input: JString) -> jbyteArray {
    let text = match read_string(&mut env, &input) {
        Some(t) => t,
        None => return null_mut(),
    };
    let blob = crate::search::encode_query(&crate::search::query(&text));
    env.byte_array_from_slice(&blob).map_or(null_mut(), |a| a.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeSearchUrl(mut env: JNIEnv, _c: JClass, kind: jint, text: JString) -> jstring {
    let t = match read_string(&mut env, &text) {
        Some(t) => t,
        None => return null_mut(),
    };
    match crate::search::url(kind, &t) {
        Some(u) => jstr(&mut env, u),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeNextSource(_env: JNIEnv, _c: JClass, kind: jint) -> jint {
    crate::search::next_source(kind)
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeFormatDistance(mut env: JNIEnv, _c: JClass, m: jdouble) -> jstring {
    jstr(&mut env, crate::text::format_distance(m))
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeFormatDuration(mut env: JNIEnv, _c: JClass, sec: jdouble) -> jstring {
    jstr(&mut env, crate::text::format_duration(sec))
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeAddress(mut env: JNIEnv, _c: JClass, line: JString, lat: jdouble, lng: jdouble) -> jstring {
    let l = read_string(&mut env, &line);
    jstr(&mut env, crate::text::address_text(l.as_deref(), lat, lng))
}

#[no_mangle]
pub extern "system" fn Java_com_heryts_setloc_utils_NetNative_nativeIsCoords(mut env: JNIEnv, _c: JClass, text: JString) -> jboolean {
    match read_string(&mut env, &text) {
        Some(t) => flag(crate::search::is_coords_shaped(&t)),
        None => JNI_FALSE,
    }
}
