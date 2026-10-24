use crate::app::{app_installed, install_app};
use crate::codec::{inrange, numok, KEY};
use crate::engine::Core;
use crate::lock::{is_empty, running, Lock};
use crate::pref::{find_pref, pref_update};
use crate::sys::{csget, is_self_cmd, quiet, run, run_all, sleep_ms, spawn_detached, APP};
use routecore::appstate;
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

pub fn ensure_up(moddir: &Path, super_only: bool) -> bool {
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
        return true;
    }
    false
}

pub fn ensure(moddir: &Path, super_only: bool) -> i32 {
    if ensure_up(moddir, super_only) {
        say!("daemon=running");
        0
    } else {
        say!("daemon=stopped");
        1
    }
}

fn app_pref() -> Option<PathBuf> {
    let p = find_pref("", false);
    if !p.is_empty() && Path::new(&p).is_file() {
        Some(PathBuf::from(p))
    } else {
        None
    }
}

pub fn set_app_start(core: &mut Core, on: bool) -> i32 {
    let v = if on { "1" } else { "0" };
    let pref = match app_pref() {
        Some(p) => p,
        None => return 1,
    };
    if appstate::load(&pref).get("start") == Some(v) {
        return 0;
    }
    quiet("am", &["force-stop", APP]);
    let ok = pref_update(&pref, &format!("start:{}", v));
    core.remember();
    if ok { 0 } else { 1 }
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
    let mut patch = format!("lat:{}\nlng:{}\nspd:0\nbrg:-1", lat, lng);
    if !acc.is_empty() {
        patch.push_str(&format!("\nacc:{}", acc));
    }
    let ok = pref_update(&pref, &patch);
    core.remember();
    if ok { 0 } else { 1 }
}

pub fn set_app_random(core: &mut Core, arg: &str) -> i32 {
    if arg != "0" && arg != "1" {
        return 1;
    }
    let pref = match app_pref() {
        Some(p) => p,
        None => return 1,
    };
    if appstate::load(&pref).get("random") == Some(arg) {
        return 0;
    }
    quiet("am", &["force-stop", APP]);
    let ok = pref_update(&pref, &format!("random:{}", arg));
    core.remember();
    if ok { 0 } else { 1 }
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
        let st = appstate::load(Path::new(&pref));
        say!("pref=found");
        say!("pref_path={}", pref);
        say!("pref_mtime={}", run("stat", &["-c", "%y", &pref]).map(|r| r.1).unwrap_or_default());
        say!(
            "pref_start={} lat={} lng={}",
            st.get("start").unwrap_or(""),
            st.get("lat").unwrap_or(""),
            st.get("lng").unwrap_or("")
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cli_setters_edit_state_file_and_resync_core() {
        let root = std::env::temp_dir().join(format!("setlocd-bridge-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let files = root.join("data").join(APP).join("files");
        fs::create_dir_all(&files).unwrap();
        let state = files.join(routecore::appstate::FILE);
        fs::write(&state, "start:0\nlat:1.0000000\nlng:2.0000000\nacc:10\nrandom:1\nspd:5\nbrg:30\n").unwrap();
        std::env::set_var("SETLOCD_DATA", &root);
        let mut core = Core::new(&root);

        assert_eq!(set_app_start(&mut core, true), 0);
        assert_eq!(set_app_pos(&mut core, "-7.25", "110.5", "12.5"), 0);
        assert_eq!(set_app_random(&mut core, "0"), 0);
        assert_eq!(set_app_random(&mut core, "2"), 1);
        assert_eq!(set_app_pos(&mut core, "95", "110.5", ""), 1);

        let st = appstate::load(&state);
        assert!(st.flag("start"));
        assert_eq!(st.get("lat"), Some("-7.2500000"));
        assert_eq!(st.get("lng"), Some("110.5000000"));
        assert_eq!(st.get("acc"), Some("12.5"));
        assert_eq!(st.get("random"), Some("0"));
        assert_eq!(st.get("spd"), Some("0"));
        assert_eq!(st.get("brg"), Some("-1"));
        assert_eq!(core.new, "enabled=1;lat=-7.2500000;lng=110.5000000;acc=12.5;random=0;spd=0;alt=0;altv=0;rr=8;brg=-1");

        std::env::remove_var("SETLOCD_DATA");
        let _ = fs::remove_dir_all(&root);
    }
}
