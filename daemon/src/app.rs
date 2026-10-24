use crate::sys::{self, mtime_s, now_s, quiet, quiet_bg, run, run_all, APP};
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

pub fn app_installed() -> bool {
    run("pm", &["path", APP]).is_some_and(|r| r.1.lines().any(|l| l.starts_with("package:")))
}

pub fn grant_secure() -> bool {
    quiet("pm", &["grant", APP, "android.permission.WRITE_SECURE_SETTINGS"])
}

pub fn install_app_out(apk: &Path) -> (bool, String) {
    if !apk.is_file() {
        return (false, format!("APK tidak ditemukan: {}", apk.display()));
    }
    let tmp = sys::data().join("local/tmp/gpsz_app.apk");
    let _ = fs::copy(apk, &tmp);
    let _ = fs::set_permissions(&tmp, fs::Permissions::from_mode(0o644));
    let (_, out) = run_all("pm", &["install", "-r", "-d", &tmp.to_string_lossy()]);
    let _ = fs::remove_file(&tmp);
    let ok = out.contains("Success");
    if ok {
        grant_secure();
    }
    (ok, out)
}

pub fn install_app(apk: &Path) -> bool {
    let (ok, out) = install_app_out(apk);
    say!("{}", out);
    ok
}

pub fn keepalive(full: bool) {
    let mk = |a: &[&str]| a.iter().map(|s| s.to_string()).collect::<Vec<String>>();
    let mut cmds = vec![
        mk(&["pm", "grant", APP, "android.permission.WRITE_SECURE_SETTINGS"]),
        mk(&["cmd", "deviceidle", "whitelist", &format!("+{}", APP)]),
    ];
    if full {
        for op in ["RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND", "SYSTEM_ALERT_WINDOW"] {
            cmds.push(mk(&["cmd", "appops", "set", APP, op, "allow"]));
        }
    }
    cmds.push(mk(&["cmd", "activity", "set-standby-bucket", APP, "active"]));
    cmds.push(mk(&["am", "set-inactive", APP, "false"]));
    quiet_bg(cmds);
}

pub fn poke_check(prefc: &str) {
    let st = routecore::appstate::load(Path::new(prefc));
    let (t0, tk, nn) = match (st.int("route_t0"), st.int("route_tick"), st.int("route_n")) {
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
    if !sys::pid_running(APP) {
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
