use crate::sys::{now_s, pidof, sleep_ms};
use std::fs;
use std::path::Path;

pub const ENABLE: &str = "enable_ls_system";
pub const PENDING: &str = ".ls_sys_pending";
pub const BLOCKED: &str = "ls_system_blocked";
pub const STABLE_S: i64 = 60;
const POLL_IDLE_MS: u64 = 10_000;
const POLL_WATCH_MS: u64 = 5_000;

#[derive(Default)]
pub struct Watch {
    pid: Option<String>,
    since: i64,
}

impl Watch {
    fn reset(&mut self) {
        self.pid = None;
        self.since = 0;
    }

    pub fn step(&mut self, moddir: &Path, now: i64, pid: Option<String>) -> bool {
        let pending = moddir.join(PENDING);
        if !pending.exists() {
            self.reset();
            return false;
        }
        if moddir.join(BLOCKED).exists() {
            let _ = fs::remove_file(&pending);
            self.reset();
            return true;
        }
        let Some(p) = pid else {
            self.reset();
            return false;
        };
        if self.pid.as_deref() != Some(p.as_str()) {
            self.pid = Some(p);
            self.since = now;
            return false;
        }
        if now - self.since >= STABLE_S {
            let _ = fs::remove_file(&pending);
            self.reset();
            return true;
        }
        false
    }
}

pub fn state(moddir: &Path) -> &'static str {
    if !moddir.join(ENABLE).exists() {
        "off"
    } else if moddir.join(BLOCKED).exists() {
        "blocked"
    } else if moddir.join(PENDING).exists() {
        "pending"
    } else {
        "on"
    }
}

pub fn enable(moddir: &Path) -> bool {
    let _ = fs::remove_file(moddir.join(BLOCKED));
    let _ = fs::remove_file(moddir.join(PENDING));
    fs::write(moddir.join(ENABLE), "").is_ok()
}

pub fn disable(moddir: &Path) {
    for f in [ENABLE, PENDING, BLOCKED] {
        let _ = fs::remove_file(moddir.join(f));
    }
}

pub fn reset(moddir: &Path) {
    for f in [PENDING, BLOCKED] {
        let _ = fs::remove_file(moddir.join(f));
    }
}

pub fn watch(moddir: &Path) {
    let mut w = Watch::default();
    while moddir.join(ENABLE).exists() {
        if !moddir.join(PENDING).exists() {
            w.reset();
            sleep_ms(POLL_IDLE_MS);
            continue;
        }
        let pid = pidof("system_server").into_iter().next();
        w.step(moddir, now_s(), pid);
        sleep_ms(POLL_WATCH_MS);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("lsguard-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn touch(d: &Path, f: &str) {
        fs::write(d.join(f), "").unwrap();
    }

    #[test]
    fn state_follows_files() {
        let d = tmp("state");
        assert_eq!(state(&d), "off");
        touch(&d, PENDING);
        assert_eq!(state(&d), "off");
        touch(&d, ENABLE);
        assert_eq!(state(&d), "pending");
        fs::remove_file(d.join(PENDING)).unwrap();
        assert_eq!(state(&d), "on");
        touch(&d, BLOCKED);
        assert_eq!(state(&d), "blocked");
        touch(&d, PENDING);
        assert_eq!(state(&d), "blocked");
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn pending_cleared_only_after_stable_same_pid() {
        let d = tmp("stable");
        touch(&d, ENABLE);
        touch(&d, PENDING);
        let mut w = Watch::default();
        let p = || Some("100".to_string());
        assert!(!w.step(&d, 1000, p()));
        assert!(!w.step(&d, 1000 + STABLE_S - 1, p()));
        assert!(d.join(PENDING).exists());
        assert!(w.step(&d, 1000 + STABLE_S, p()));
        assert!(!d.join(PENDING).exists());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn pid_change_restarts_the_clock() {
        let d = tmp("pidchange");
        touch(&d, ENABLE);
        touch(&d, PENDING);
        let mut w = Watch::default();
        assert!(!w.step(&d, 0, Some("100".into())));
        assert!(!w.step(&d, 50, Some("100".into())));
        assert!(!w.step(&d, 55, Some("200".into())));
        assert!(!w.step(&d, 55 + STABLE_S - 1, Some("200".into())));
        assert!(d.join(PENDING).exists());
        assert!(w.step(&d, 55 + STABLE_S, Some("200".into())));
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn missing_system_server_never_confirms() {
        let d = tmp("nopid");
        touch(&d, ENABLE);
        touch(&d, PENDING);
        let mut w = Watch::default();
        assert!(!w.step(&d, 0, Some("100".into())));
        assert!(!w.step(&d, 30, None));
        assert!(!w.step(&d, 200, None));
        assert!(!w.step(&d, 201, Some("100".into())));
        assert!(!w.step(&d, 201 + STABLE_S - 1, Some("100".into())));
        assert!(d.join(PENDING).exists());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn blocked_drops_pending_without_waiting() {
        let d = tmp("blocked");
        touch(&d, ENABLE);
        touch(&d, PENDING);
        touch(&d, BLOCKED);
        let mut w = Watch::default();
        assert!(w.step(&d, 0, None));
        assert!(!d.join(PENDING).exists());
        assert!(d.join(BLOCKED).exists());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn no_pending_is_noop() {
        let d = tmp("noop");
        touch(&d, ENABLE);
        let mut w = Watch::default();
        assert!(!w.step(&d, 0, Some("1".into())));
        assert!(!w.step(&d, 1000, Some("1".into())));
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn enable_disable_reset() {
        let d = tmp("cmds");
        touch(&d, PENDING);
        touch(&d, BLOCKED);
        assert!(enable(&d));
        assert_eq!(state(&d), "on");
        touch(&d, BLOCKED);
        reset(&d);
        assert_eq!(state(&d), "on");
        touch(&d, PENDING);
        disable(&d);
        assert_eq!(state(&d), "off");
        assert!(!d.join(PENDING).exists() && !d.join(BLOCKED).exists() && !d.join(ENABLE).exists());
        let _ = fs::remove_dir_all(&d);
    }
}
