#![allow(non_snake_case)]

use crate::symbols;
use std::collections::BTreeMap;
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use std::sync::{Mutex, MutexGuard};

type HookFn = unsafe extern "C" fn(*mut c_void, *mut c_void) -> *mut c_void;
type UnhookFn = unsafe extern "C" fn(*mut c_void) -> bool;
type ResolveFn = unsafe extern "C" fn(*const c_char) -> *mut c_void;

#[repr(C)]
struct InvokeInterface {
    reserved0: *mut c_void,
    reserved1: *mut c_void,
    reserved2: *mut c_void,
    destroy_java_vm: *mut c_void,
    attach_current_thread: *mut c_void,
    detach_current_thread: *mut c_void,
    get_env: unsafe extern "system" fn(*mut JavaVm, *mut *mut c_void, i32) -> i32,
    attach_current_thread_as_daemon: *mut c_void,
}

type JavaVm = *const InvokeInterface;

const JNI_VERSION_1_6: i32 = 0x0001_0006;
const JNI_OK: i32 = 0;
const JNI_ERR: i32 = -1;
const SHADOWHOOK_MODE_UNIQUE: c_int = 1;
const ANDROID_LOG_ERROR: c_int = 6;

extern "C" {
    fn shadowhook_init(mode: c_int, debuggable: bool) -> c_int;
    fn shadowhook_hook_func_addr(func: *mut c_void, new_func: *mut c_void, orig: *mut *mut c_void) -> *mut c_void;
    fn shadowhook_unhook(stub: *mut c_void) -> c_int;
    fn shadowhook_get_errno() -> c_int;
    fn shadowhook_to_errmsg(errno: c_int) -> *const c_char;
    fn shadowhook_dlopen(lib_name: *const c_char) -> *mut c_void;
    fn shadowhook_dlsym(handle: *mut c_void, sym_name: *const c_char) -> *mut c_void;
    fn shadowhook_dlsym_symtab(handle: *mut c_void, sym_name: *const c_char) -> *mut c_void;
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    fn android_get_device_api_level() -> c_int;
    fn gpsz_ls_init(env: *mut c_void, hook: HookFn, unhook: UnhookFn, resolve: ResolveFn, prefix: ResolveFn) -> bool;
    fn gpsz_ls_hook(env: *mut c_void, target: *mut c_void, hooker: *mut c_void, callback: *mut c_void) -> *mut c_void;
}

static READY: AtomicBool = AtomicBool::new(false);
static ART: AtomicPtr<c_void> = AtomicPtr::new(null_mut());
static HOOKS: Mutex<BTreeMap<usize, usize>> = Mutex::new(BTreeMap::new());

fn hooks() -> MutexGuard<'static, BTreeMap<usize, usize>> {
    HOOKS.lock().unwrap_or_else(|e| e.into_inner())
}

fn log_e(msg: &str) {
    let text = CString::new(msg.replace('\0', "?")).unwrap_or_default();
    unsafe {
        __android_log_write(ANDROID_LOG_ERROR, b"gpsz_hook\0".as_ptr() as *const c_char, text.as_ptr());
    }
}

unsafe fn errmsg(err: c_int) -> String {
    let p = shadowhook_to_errmsg(err);
    if p.is_null() {
        String::new()
    } else {
        CStr::from_ptr(p).to_string_lossy().into_owned()
    }
}

unsafe extern "C" fn inline_hook(target: *mut c_void, hooker: *mut c_void) -> *mut c_void {
    let mut backup: *mut c_void = null_mut();
    let stub = shadowhook_hook_func_addr(target, hooker, &mut backup);
    if stub.is_null() {
        let err = shadowhook_get_errno();
        log_e(&format!("inline_hook gagal {:p} errno={} {}", target, err, errmsg(err)));
        return null_mut();
    }
    hooks().insert(target as usize, stub as usize);
    backup
}

unsafe extern "C" fn inline_unhook(target: *mut c_void) -> bool {
    let stub = match hooks().remove(&(target as usize)) {
        Some(s) => s,
        None => return false,
    };
    shadowhook_unhook(stub as *mut c_void) == 0
}

macro_rules! fixup_stub {
    ($name:ident, $n:expr) => {
        #[inline(never)]
        extern "C" fn $name() {
            let mut v: i32 = $n;
            let p: *mut i32 = &mut v;
            unsafe {
                p.write_volatile($n);
                p.write_volatile(p.read_volatile().wrapping_add(1));
                p.write_volatile(p.read_volatile().wrapping_mul(3));
                p.write_volatile(p.read_volatile().wrapping_sub(2));
                p.write_volatile(p.read_volatile() ^ 5);
                p.write_volatile(p.read_volatile().wrapping_add($n));
                p.write_volatile(p.read_volatile() | 1);
            }
        }
    };
}

fixup_stub!(fixup_stub_1, 1);
fixup_stub!(fixup_stub_2, 2);
fixup_stub!(fixup_stub_3, 3);

static FIXUP_STUBS: [extern "C" fn(); 3] = [fixup_stub_1, fixup_stub_2, fixup_stub_3];

unsafe extern "C" fn resolve(name: *const c_char) -> *mut c_void {
    let art = ART.load(Ordering::Acquire);
    let bytes = CStr::from_ptr(name).to_bytes();
    let mut p = shadowhook_dlsym(art, name);
    if p.is_null() {
        p = shadowhook_dlsym_symtab(art, name);
    }
    if p.is_null() {
        if let Some(alt) = symbols::alt_symbol(bytes) {
            let q = shadowhook_dlsym(art, alt.as_ptr() as *const c_char);
            if !q.is_null() {
                return q;
            }
        }
    }
    if p.is_null() {
        if let Some(i) = symbols::stub_index(bytes) {
            return FIXUP_STUBS[i] as usize as *mut c_void;
        }
    }
    if p.is_null() {
        log_e(&format!("simbol tidak ada {}", String::from_utf8_lossy(bytes)));
    }
    p
}

unsafe extern "C" fn resolve_prefix(prefix: *const c_char) -> *mut c_void {
    log_e(&format!("prefix diminta {}", CStr::from_ptr(prefix).to_string_lossy()));
    null_mut()
}

unsafe fn start(env: *mut c_void) -> bool {
    if shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != 0 {
        return false;
    }
    let art = shadowhook_dlopen(b"libart.so\0".as_ptr() as *const c_char);
    if art.is_null() {
        log_e("dlopen libart gagal");
        return false;
    }
    ART.store(art, Ordering::Release);
    let ok = gpsz_ls_init(env, inline_hook, inline_unhook, resolve, resolve_prefix);
    log_e(&format!("lsplant::Init={} sdk={}", ok as i32, android_get_device_api_level()));
    ok
}

#[no_mangle]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut JavaVm, _reserved: *mut c_void) -> i32 {
    let mut env: *mut c_void = null_mut();
    if ((**vm).get_env)(vm, &mut env, JNI_VERSION_1_6) != JNI_OK {
        return JNI_ERR;
    }
    READY.store(start(env), Ordering::Release);
    JNI_VERSION_1_6
}

#[no_mangle]
pub extern "system" fn Java_io_gpsz_zygote_LsNative_nativeReady(_env: *mut c_void, _class: *mut c_void) -> u8 {
    READY.load(Ordering::Acquire) as u8
}

#[no_mangle]
pub unsafe extern "system" fn Java_io_gpsz_zygote_LsNative_nativeHook(
    env: *mut c_void,
    _class: *mut c_void,
    target: *mut c_void,
    hooker: *mut c_void,
    callback: *mut c_void,
) -> *mut c_void {
    if !READY.load(Ordering::Acquire) {
        return null_mut();
    }
    gpsz_ls_hook(env, target, hooker, callback)
}
