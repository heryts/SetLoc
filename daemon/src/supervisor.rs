use crate::lock::{running, Lock};
use crate::sys::{self, env_u64, oom_protect, quiet, sleep_ms, stopping};
use std::fs;

pub fn run() -> i32 {
    let moddir = sys::moddir();
    let supf = moddir.join(".super.pid");
    let me = std::process::id().to_string();
    let _ = fs::write(&supf, format!("{}\n", me));
    oom_protect();
    sys::install_signals();
    let tick = env_u64("SETLOCD_SUPER_SLEEP_MS", 10000);
    loop {
        sleep_ms(tick);
        if stopping() {
            return 143;
        }
        if !fs::read_to_string(&supf).is_ok_and(|s| s.trim() == me) {
            return 0;
        }
        let exe = match std::env::current_exe() {
            Ok(e) if e.exists() => e,
            _ => return 0,
        };
        if moddir.join("disable_super").exists() {
            continue;
        }
        if !running(&Lock::dir(&moddir)) {
            quiet(&exe.to_string_lossy(), &["ensure", "super"]);
        }
    }
}
