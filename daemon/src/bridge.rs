use crate::app::{app_installed, install_app};
use crate::codec::{inrange, numok, KEY};
use crate::config::fval;
use crate::engine::Core;
use crate::lock::{is_empty, running, Lock};
use crate::pref::{find_pref, pref_del, pref_set, pref_str};
use crate::sys::{self, csget, is_self_cmd, quiet, run, run_all, sleep_ms, spawn_detached, APP};
use std::fs;
use std::path::{Path, PathBuf};

pub fn super_alive(moddir: &Path) -> bool {
    let s = fs::read_to_string(moddir.join(".super.pid")).unwrap_or_default();
    let sp = s.trim();
    !sp.is_empty() && sp.bytes().all(|b| b.is_ascii_digit()) && is_self_cmd(sp, "super")
}

pub fn start_super(moddir: &Path) {
    if moddir.join("disable_super").exists() || super_alive(moddir) {
        return;
    }
    spawn_detached(&["super"]);
}

fn start_daemon(moddir: &Path) -> bool {
    let dir = Lock::dir(moddir);
    if running(&dir) {
        return true;
    }
    spawn_detached(&["daemon"]);
    sleep_ms(1000);
    running(&dir)
}

pub fn ensure(moddir: &Path, super_only: bool) -> i32 {
    let dir = Lock::dir(moddir);
    let mut up = running(&dir);
    if !up {
        if is_empty(&dir) {
            sleep_ms(2000);
        }
        up = running(&dir);
    }
    if up || start_daemon(moddir) {
        if !super_only {
            start_super(moddir);
        }
        say!("daemon=running");
        return 0;
    }
    say!("daemon=stopped");
    1
}

fn app_pref() -> Option<PathBuf> {
    let p = find_pref("", false);
    if !p.is_empty() && Path::new(&p).is_file() {
        Some(PathBuf::from(p))
    } else {
        None
    }
}

fn read(p: &Path) -> String {
    fs::read_to_string(p).unwrap_or_default()
}

pub fn set_app_start(core: &mut Core, on: bool) -> i32 {
    let v = if on { "true" } else { "false" };
    let pref = match app_pref() {
        Some(p) => p,
        None => return 1,
    };
    if fval(&read(&pref), "boolean", "start") == Some(v) {
        return 0;
    }
    quiet("am", &["force-stop", APP]);
    pref_set(&pref, "boolean", "start", v);
    core.remember();
    0
}

pub fn set_app_pos(core: &mut Core, lat: &str, lng: &str, acc: &str) -> i32 {
    if !(numok(lat) && numok(lng) && inrange(lat, 90) && inrange(lng, 180)) {
        return 1;
    }
    if !acc.is_empty() && (acc == "." || acc.matches('.').count() > 1 || acc.bytes().any(|b| !(b.is_ascii_digit() || b == b'.'))) {
        return 1;
    }
    let pref = match app_pref() {
        Some(p) => p,
        None => return 1,
    };
    quiet("am", &["force-stop", APP]);
    pref_del(&pref, "latitude_d");
    pref_del(&pref, "longitude_d");
    pref_set(&pref, "float", "latitude", lat);
    pref_set(&pref, "float", "longitude", lng);
    pref_set(&pref, "float", "speed_ms", "0");
    pref_set(&pref, "float", "bearing", "-1");
    if !acc.is_empty() {
        pref_str(&pref, "accuracy_settings", acc);
    }
    core.remember();
    0
}

pub fn set_app_random(core: &mut Core, arg: &str) -> i32 {
    let v = match arg {
        "0" => "false",
        "1" => "true",
        _ => return 1,
    };
    let pref = match app_pref() {
        Some(p) => p,
        None => return 1,
    };
    if fval(&read(&pref), "boolean", "random_position") == Some(v) {
        return 0;
    }
    quiet("am", &["force-stop", APP]);
    pref_set(&pref, "boolean", "random_position", v);
    core.remember();
    0
}

pub fn install(moddir: &Path) -> i32 {
    if install_app(&moddir.join("apk/SetLoc.apk")) {
        0
    } else {
        1
    }
}

pub fn status(moddir: &Path, core: &mut Core) {
    let yn = |b: bool, a: &str, c: &str| if b { a.to_string() } else { c.to_string() };
    say!("daemon={}", yn(running(&Lock::dir(moddir)), "running", "stopped"));
    say!("super={}", yn(super_alive(moddir), "running", "stopped"));
    say!("app={}", yn(app_installed(), "installed", "missing"));
    let granted = run_all("dumpsys", &["package", APP]).1.contains("WRITE_SECURE_SETTINGS: granted=true");
    say!("secure_settings={}", yn(granted, "granted", "missing"));
    let pref = find_pref("", true);
    if !pref.is_empty() && Path::new(&pref).is_file() {
        let px = read(Path::new(&pref));
        say!("pref=found");
        say!("pref_path={}", pref);
        say!("pref_mtime={}", run("stat", &["-c", "%y", &pref]).map(|r| r.1).unwrap_or_default());
        say!(
            "pref_start={} lat={} lng={}",
            fval(&px, "boolean", "start").unwrap_or(""),
            fval(&px, "float", "latitude").unwrap_or(""),
            fval(&px, "float", "longitude").unwrap_or("")
        );
    } else {
        say!("pref=missing");
    }
    core.fast = false;
    core.out.clear();
    core.derive();
    say!("derived={}", core.out);
    say!("cfg={}", csget(KEY).unwrap_or_default());
    for f in [core.cfgs(), core.cfgf()] {
        if f.is_file() {
            say!("mirror={}", run("ls", &["-lZ", &f.to_string_lossy()]).map(|r| r.1).unwrap_or_default());
        } else {
            say!("mirror=tidak ada ({})", f.display());
        }
    }
}
