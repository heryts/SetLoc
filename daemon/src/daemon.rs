use crate::app::{app_installed, install_app, keepalive, poke_check};
use crate::bridge::start_super;
use crate::codec::{BEATKEY, SYSKEY};
use crate::engine::{sig, Core};
use crate::lock::Lock;
use crate::pref::find_pref;
use crate::sys::{self, csput, newer, sdel, sget, sleep_ms, stopping, uptime_cs, uptime_s, Ino};
use std::fs;
use std::path::Path;
use std::time::{Duration, Instant};

const OLDKEYS: &str = include_str!("../oldkeys.txt");

pub struct Daemon {
    core: Core,
    lock: Lock,
    ino: Option<Ino>,
    woke: bool,
    signow: bool,
    synced: String,
    last: String,
    n: u64,
    playing: bool,
    sigevery: u64,
    delay_ms: u64,
    wason: bool,
    kaat: i64,
    suat: i64,
    pkat: i64,
    lastbeat: i64,
    super_check: i64,
}

impl Daemon {
    fn new(moddir: &Path) -> Daemon {
        Daemon {
            core: Core::new(moddir),
            lock: Lock::new(moddir),
            ino: None,
            woke: false,
            signow: false,
            synced: String::new(),
            last: String::new(),
            n: 0,
            playing: false,
            sigevery: 10,
            delay_ms: 500,
            wason: false,
            kaat: 0,
            suat: 0,
            pkat: 0,
            lastbeat: -1,
            super_check: sys::env_u64("SETLOCD_SUPER_CHECK_CS", 3000) as i64,
        }
    }

    fn leave(&mut self, code: i32) -> ! {
        self.ino = None;
        self.lock.release();
        std::process::exit(code);
    }

    fn yield_lock(&mut self) {
        if stopping() {
            self.leave(143);
        }
        if !self.lock.fast() {
            self.leave(0);
        }
    }

    fn ino_active(&self) -> bool {
        self.ino.as_ref().map_or(false, |i| !i.dead)
    }

    fn ino_up(&mut self) {
        let wd = match Path::new(&self.core.prefc).parent() {
            Some(p) if !self.core.prefc.is_empty() => p.to_string_lossy().into_owned(),
            _ => {
                self.ino = None;
                return;
            }
        };
        if let Some(i) = &self.ino {
            if i.dir == wd && !i.dead {
                return;
            }
        }
        self.ino = Ino::open(&wd);
    }

    fn drain_events(&mut self) {
        if let Some(i) = self.ino.as_mut() {
            if i.drain() {
                self.woke = true;
                self.signow = true;
            }
        }
    }

    fn wait_wake(&mut self, secs: u64) {
        let deadline = Instant::now() + Duration::from_secs(secs);
        loop {
            if stopping() {
                break;
            }
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break;
            }
            let fd = self.ino.as_ref().map_or(-1, |i| i.fd());
            sys::wait_fd(fd, left.as_millis() as u64);
            self.drain_events();
            if self.woke {
                break;
            }
        }
        self.woke = false;
    }

    fn nap(&mut self) {
        let rt_ok = self.core.route.is_some();
        let trk = self.core.trk_on();
        let ino = self.ino_active();
        if self.playing && rt_ok && trk {
            if ino {
                if self.woke {
                    self.woke = false;
                    return;
                }
                let secs = self.core.trk_nap;
                self.wait_wake(secs);
            } else {
                sleep_ms(1000);
            }
            return;
        }
        if self.playing && rt_ok {
            if let Some(rt) = self.core.route.as_ref() {
                let el = (uptime_cs().unwrap_or(0) - rt.t0) * 10;
                let ms = (rt.tk - el % rt.tk).clamp(50, 500);
                sleep_ms(ms as u64);
            }
            return;
        }
        if ino && !self.playing {
            if self.woke {
                self.woke = false;
                return;
            }
            self.wait_wake(5);
            return;
        }
        sleep_ms(self.delay_ms);
    }

    fn sigdue(&mut self) -> bool {
        if self.signow {
            self.signow = false;
            return true;
        }
        if self.n % self.sigevery == 0 {
            return true;
        }
        self.ino_active() && self.core.trk_on()
    }

    fn changed(&mut self) -> bool {
        if self.core.prefc != self.synced {
            return true;
        }
        if newer(Path::new(&self.core.prefc), &self.core.stamp()) {
            return true;
        }
        self.sigdue() && sig(&self.core.prefc) != self.last
    }

    fn pulse(&mut self) {
        let s = uptime_s();
        if s != self.lastbeat {
            csput(BEATKEY, &s.to_string());
            self.lastbeat = s;
        }
    }

    fn legacy_clean(&self) {
        let keys = OLDKEYS.trim();
        if keys.is_empty() {
            return;
        }
        let done = self.core.moddir.join(".legacy_done");
        if fs::read_to_string(&done).map_or(false, |s| s.trim_end_matches('\n') == keys) {
            return;
        }
        for k in keys.split_whitespace() {
            sdel(k);
        }
        let _ = fs::write(&done, format!("{}\n", keys));
    }

    fn setup(&mut self) {
        let moddir = self.core.moddir.clone();
        let apk = moddir.join("apk/SetLoc.apk");
        let sum = sys::run("md5sum", &[&apk.to_string_lossy()])
            .and_then(|r| r.1.split_whitespace().next().map(String::from))
            .unwrap_or_default();
        let stored = fs::read_to_string(moddir.join(".apk_sum")).unwrap_or_default();
        if !app_installed() || sum != stored.trim_end_matches('\n') {
            if install_app(&apk) {
                let _ = fs::write(moddir.join(".apk_sum"), format!("{}\n", sum));
            }
        }
        self.core.prefc = find_pref("", false);
        self.core.cur_bc = sget("boot_count");
        let _ = fs::write(self.core.stamp(), "");
        self.synced = self.core.prefc.clone();
        self.ino_up();
        self.legacy_clean();
        self.core.sync_cfg(false);
        if app_installed() {
            keepalive(true);
        }
        start_super(&moddir);
        self.last = sig(&self.core.prefc);
        self.delay_ms = 500;
        self.n = 0;
        self.playing = false;
        self.sigevery = 10;
    }

    fn step_sync(&mut self) {
        let _ = fs::write(self.core.stamp(), "");
        if sig(&self.core.prefc) != self.last {
            sleep_ms(100);
        }
        let mut ok = false;
        for _ in 0..6 {
            self.core.fast = false;
            if self.core.sync_cfg(false) {
                ok = true;
                break;
            }
            sleep_ms(50);
            self.core.prefc.clear();
            self.core.prefc = find_pref("", false);
        }
        if !ok {
            self.core.fast = false;
            ok = self.core.sync_cfg(false);
        }
        if ok {
            self.last = sig(&self.core.prefc);
        }
        self.synced = self.core.prefc.clone();
    }

    fn main_loop(&mut self) -> i32 {
        loop {
            self.nap();
            self.drain_events();
            self.n += 1;
            self.yield_lock();
            if self.n % 100 == 0 {
                self.lock.tidy();
            }
            let missing = !Path::new(&self.core.prefc).is_file();
            if self.n % 20 == 0 || (missing && (!self.core.prefc.is_empty() || self.n % 5 == 0)) {
                self.core.prefc.clear();
                self.core.prefc = find_pref("", false);
            }
            if let Some(i) = self.ino.as_ref() {
                if i.dead {
                    self.ino_up();
                }
            } else if self.n % 20 == 0 {
                self.ino_up();
            }
            let parent = Path::new(&self.core.prefc).parent().map(|p| p.to_string_lossy().into_owned()).unwrap_or_default();
            if self.ino_active() && self.ino.as_ref().map_or(false, |i| i.dir != parent) {
                self.ino_up();
            }
            let rt_ok = self.core.route.is_some();
            if self.changed() {
                self.step_sync();
            } else if self.playing && rt_ok && self.core.route_due() {
                self.core.fast = true;
                self.core.sync_cfg(false);
                self.core.fast = false;
            } else if self.playing && rt_ok && self.core.trk_on() {
                if !self.core.trk_pump() {
                    if let Some(r) = self.core.route.as_mut() {
                        r.trk = false;
                    }
                    self.core.fast = true;
                    self.core.sync_cfg(false);
                    self.core.fast = false;
                }
            }
            self.playing = self.core.new.ends_with(";pl=1");
            if self.playing {
                self.sigevery = 5;
                if !self.core.trk_on() {
                    self.pulse();
                }
            } else if self.ino_active() {
                self.sigevery = 1;
            } else {
                self.sigevery = 10;
            }
            if self.playing {
                let pn = uptime_cs().unwrap_or(0);
                if pn - self.pkat >= 1200 {
                    self.pkat = pn;
                    poke_check(&self.core.prefc);
                }
            }
            if self.core.new.contains("enabled=1") {
                self.delay_ms = 300;
                if !self.wason {
                    self.wason = true;
                    keepalive(true);
                }
                let kn = uptime_cs().unwrap_or(0);
                if kn - self.kaat >= 30000 {
                    self.kaat = kn;
                    keepalive(false);
                }
            } else {
                self.delay_ms = 1000;
                self.wason = false;
            }
            let sn = uptime_cs().unwrap_or(0);
            if sn - self.suat >= self.super_check {
                self.suat = sn;
                start_super(&self.core.moddir.clone());
            }
        }
    }
}

pub fn run() -> i32 {
    let moddir = sys::moddir();
    let mut d = Daemon::new(&moddir);
    sys::install_signals();
    if !d.lock.acquire() {
        return 0;
    }
    sys::oom_protect();
    while !sys::boot_completed() {
        d.yield_lock();
        sleep_ms(1000);
    }
    for _ in 0..5 {
        sleep_ms(1000);
        d.yield_lock();
    }
    d.setup();
    d.main_loop()
}

pub fn sysdefault() {
    let v = sget(SYSKEY);
    if v.is_empty() || v == "null" {
        csput(SYSKEY, "2");
    }
}
