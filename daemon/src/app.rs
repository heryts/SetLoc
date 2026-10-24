use crate::config::fval;
use crate::sys::{self, mtime_s, now_s, quiet, quiet_bg, run, run_all, APP};
use crate::codec::numok;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

pub fn app_installed() -> bool {
    run("pm", &["path", APP]).map_or(false, |r| r.1.lines().any(|l| l.starts_with("package:")))
}

pub fn grant_secure() -> bool {
    quiet("pm", &["grant", APP, "android.permission.WRITE_SECURE_SETTINGS"])
}

pub fn install_app(apk: &Path) -> bool {
    if !apk.is_file() {
        say!("APK tidak ditemukan: {}", apk.display());
        return false;
    }
    let tmp = sys::data().join("local/tmp/gpsz_app.apk");
    let _ = fs::copy(apk, &tmp);
    let _ = fs::set_permissions(&tmp, fs::Permissions::from_mode(0o644));
    let (_, out) = run_all("pm", &["install", "-r", "-d", &tmp.to_string_lossy()]);
    let _ = fs::remove_file(&tmp);
    say!("{}", out);
    if out.contains("Success") {
        grant_secure();
        true
    } else {
        false
    }
}

pub fn keepalive(full: bool) {
    let mut cmds: Vec<Vec<String>> = Vec::new();
    let mk = |a: &[&str]| a.iter().map(|s| s.to_string()).collect::<Vec<String>>();
    cmds.push(mk(&["pm", "grant", APP, "android.permission.WRITE_SECURE_SETTINGS"]));
    cmds.push(mk(&["cmd", "deviceidle", "whitelist", &format!("+{}", APP)]));
    if full {
        cmds.push(mk(&["cmd", "appops", "set", APP, "RUN_ANY_IN_BACKGROUND", "allow"]));
        cmds.push(mk(&["cmd", "appops", "set", APP, "RUN_IN_BACKGROUND", "allow"]));
        cmds.push(mk(&["cmd", "appops", "set", APP, "SYSTEM_ALERT_WINDOW", "allow"]));
    }
    cmds.push(mk(&["cmd", "activity", "set-standby-bucket", APP, "active"]));
    cmds.push(mk(&["am", "set-inactive", APP, "false"]));
    quiet_bg(cmds);
}

pub fn poke_check(prefc: &str) {
    let px = fs::read_to_string(prefc).unwrap_or_default();
    let f = |ty: &str, n: &str| fval(&px, ty, n).filter(|v| numok(v)).and_then(|v| v.parse::<i64>().ok());
    let (t0, tk, nn) = match (f("long", "route_t0"), f("long", "route_tick"), f("int", "route_n")) {
        (Some(a), Some(b), Some(c)) => (a, b, c),
        _ => return,
    };
    if tk <= 0 {
        return;
    }
    let el = (sys::uptime_cs().unwrap_or(0) - t0) * 10;
    if el < 0 || el >= (nn - 1) * tk {
        return;
    }
    if now_s() - mtime_s(Path::new(prefc)) < 15 {
        return;
    }
    if sys::pidof(APP).is_empty() {
        return;
    }
    quiet_bg(vec![vec![
        "am".into(),
        "startservice".into(),
        "-n".into(),
        format!("{}/.route.RouteService", APP),
        "-a".into(),
        format!("{}.route.POKE", APP),
    ]]);
}
