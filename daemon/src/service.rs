use crate::daemon::sysdefault;
use crate::sys::{self, sleep_ms, spawn_detached};
use std::fs;
use std::os::unix::fs::PermissionsExt;

pub fn run() -> i32 {
    let moddir = sys::moddir();
    for name in ["gpsz.sh", "bridge.sh", "action.sh"] {
        let p = moddir.join(name);
        if p.exists() {
            let _ = fs::set_permissions(&p, fs::Permissions::from_mode(0o755));
        }
    }
    spawn_detached(&["ensure"]);
    let marker = moddir.join(".hb_default");
    while !sys::boot_completed() {
        sleep_ms(3000);
    }
    if !marker.exists() {
        sysdefault();
        let _ = fs::write(&marker, "");
    }
    0
}
