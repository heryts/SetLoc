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
