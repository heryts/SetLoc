use crate::sys::{is_self_cmd, sleep_ms, uptime_cs};
use std::fs;
use std::path::{Path, PathBuf};

pub struct Lock {
    dir: PathBuf,
    pid: u32,
    myk: i64,
}

fn pid_files(dir: &Path) -> Vec<(String, PathBuf)> {
    let mut v = Vec::new();
    if let Ok(rd) = fs::read_dir(dir) {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().into_owned();
            if !n.is_empty() && n.bytes().all(|b| b.is_ascii_digit()) {
                v.push((n, e.path()));
            }
        }
    }
    v
}

pub fn running(dir: &Path) -> bool {
    pid_files(dir).iter().any(|(n, _)| is_self_cmd(n, "daemon"))
}

pub fn is_empty(dir: &Path) -> bool {
    fs::read_dir(dir).map_or(true, |mut r| r.next().is_none())
}

impl Lock {
    pub fn new(moddir: &Path) -> Lock {
        Lock { dir: moddir.join(".daemon.lock"), pid: std::process::id(), myk: 0 }
    }

    pub fn dir(moddir: &Path) -> PathBuf {
        moddir.join(".daemon.lock")
    }

    fn own(&self) -> PathBuf {
        self.dir.join(self.pid.to_string())
    }

    fn reg(&self) -> bool {
        for _ in 0..3 {
            let _ = fs::create_dir_all(&self.dir);
            let tmp = self.dir.join(format!(".t{}", self.pid));
            if fs::write(&tmp, format!("{}\n", self.myk)).is_ok()
                && fs::rename(&tmp, self.own()).is_ok()
                && self.own().is_file()
            {
                return true;
            }
        }
        false
    }

    pub fn tidy(&self) {
        let me = self.pid.to_string();
        for (n, p) in pid_files(&self.dir) {
            if n != me && !is_self_cmd(&n, "daemon") {
                let _ = fs::remove_file(p);
            }
        }
    }

    pub fn fast(&self) -> bool {
        if !self.own().is_file() {
            self.reg();
        }
        let me = self.pid.to_string();
        for (n, p) in pid_files(&self.dir) {
            if n == me || !Path::new(&format!("/proc/{}", n)).is_dir() {
                continue;
            }
            let k: i64 = match fs::read_to_string(&p).ok().and_then(|s| s.lines().next().and_then(|l| l.trim().parse().ok())) {
                Some(k) => k,
                None => continue,
            };
            let q: u32 = n.parse().unwrap_or(0);
            if (k < self.myk || (k == self.myk && q < self.pid)) && is_self_cmd(&n, "daemon") {
                return false;
            }
        }
        true
    }

    pub fn acquire(&mut self) -> bool {
        self.myk = uptime_cs().unwrap_or(0);
        if !self.reg() {
            return false;
        }
        sleep_ms(300);
        self.tidy();
        if self.fast() {
            return true;
        }
        let _ = fs::remove_file(self.own());
        false
    }

    pub fn release(&self) {
        let _ = fs::remove_file(self.own());
        let _ = fs::remove_file(self.dir.join(format!(".t{}", self.pid)));
        let _ = fs::remove_dir(&self.dir);
    }
}
