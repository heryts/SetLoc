use crate::codec::{BTKEY, KEY, NETKEY, SYSKEY, TRKKEY};
use crate::config::{compose, derive, fval, route_apply, static_apply, Base, Route};
use crate::pref::find_pref;
use crate::sys::{self, csget, csput, data, sget, uptime_cs};
use std::fs;
use std::io::{Read, Seek, SeekFrom};
use std::os::unix::fs::{chown, MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};

pub const TRK_W: i64 = 48;
pub const TRK_LEAD: i64 = 24;

pub struct Core {
    pub moddir: PathBuf,
    pub prefc: String,
    pub cur_bc: String,
    pub fast: bool,
    pub base: Base,
    pub route: Option<Route>,
    pub out: String,
    pub new: String,
    pub ridx: i64,
    pub trk_nap: u64,
    tmkey: String,
    tmfk: String,
    tmfat: i64,
    wkey: String,
    ws: i64,
    we: i64,
    hold_at: i64,
    pc: i64,
    kv: String,
    kv_at: i64,
}

// Cache KEY dipercaya paling lama 30 detik (cs), setelah itu diverifikasi ulang.
const KV_TTL: i64 = 3000;

pub fn tr_squeeze(b: &[u8]) -> String {
    let mut out = String::with_capacity(b.len());
    let mut last: Option<char> = None;
    for &c in b {
        let m = match c {
            b' ' => ',',
            b'\n' => '|',
            x => x as char,
        };
        if (m == ',' || m == '|') && last == Some(m) {
            continue;
        }
        out.push(m);
        last = Some(m);
    }
    out
}

pub fn sig(prefc: &str) -> String {
    if prefc.is_empty() {
        return String::new();
    }
    match fs::metadata(prefc) {
        Ok(m) => format!("{} {} {}.{} {}", prefc, m.ino(), m.mtime(), m.mtime_nsec(), m.size()),
        Err(_) => format!("{} 0 0 0", prefc),
    }
}

fn window(rt: &Route, start: i64, count: i64) -> Vec<u8> {
    let mut f = match fs::File::open(&rt.tf) {
        Ok(f) => f,
        Err(_) => return Vec::new(),
    };
    if f.seek(SeekFrom::Start((start - 1) as u64 * rt.tlw as u64)).is_err() {
        return Vec::new();
    }
    let mut buf = Vec::new();
    let _ = f.take(count as u64 * rt.tlw as u64).read_to_end(&mut buf);
    buf
}

impl Core {
    pub fn new(moddir: &Path) -> Core {
        Core {
            moddir: moddir.to_path_buf(),
            prefc: std::env::var("SETLOCD_PREFC").unwrap_or_default(),
            cur_bc: String::new(),
            fast: false,
            base: Base::default(),
            route: None,
            out: String::new(),
            new: String::new(),
            ridx: 0,
            trk_nap: 5,
            tmkey: String::new(),
            tmfk: String::new(),
            tmfat: 0,
            wkey: String::new(),
            ws: 1,
            we: 0,
            hold_at: 0,
            pc: 0,
            kv: String::new(),
            kv_at: 0,
        }
    }

    pub fn lastf(&self) -> PathBuf {
        self.moddir.join(".last_app_cfg")
    }

    pub fn stamp(&self) -> PathBuf {
        self.moddir.join(".sync_stamp")
    }

    pub fn cfgs(&self) -> PathBuf {
        data().join("system/gpsz_config")
    }

    pub fn cfgf(&self) -> PathBuf {
        data().join("adb/gpsz_config")
    }

    pub fn trkf(&self) -> PathBuf {
        data().join("system/gpsz_trk")
    }

    pub fn trk_on(&self) -> bool {
        self.route.as_ref().is_some_and(|r| r.trk)
    }

    fn apply_route(&mut self) {
        let now = uptime_cs().unwrap_or(0);
        if let Some(rt) = self.route.as_ref() {
            let a = route_apply(&self.base, rt, now);
            self.out = compose(&self.base, &a);
            self.ridx = a.idx;
        }
    }

    pub fn derive(&mut self) -> i32 {
        if self.fast && self.route.is_some() {
            self.apply_route();
            return 0;
        }
        self.fast = false;
        self.route = None;
        let pref = if !self.prefc.is_empty() && Path::new(&self.prefc).is_file() {
            self.prefc.clone()
        } else {
            find_pref("", false)
        };
        if pref.is_empty() || !Path::new(&pref).is_file() {
            return 2;
        }
        let px = fs::read_to_string(&pref).unwrap_or_default();
        if self.cur_bc.is_empty() && fval(&px, "boolean", "route_play") == Some("true") {
            self.cur_bc = sget("boot_count");
        }
        let trk_disabled = self.moddir.join("disable_trk").exists();
        match derive(&px, Path::new(&pref), &self.cur_bc, trk_disabled) {
            None => 1,
            Some(d) => {
                self.base = d.base;
                self.route = d.route;
                if self.route.is_some() {
                    self.apply_route();
                } else {
                    self.out = compose(&self.base, &static_apply(&self.base));
                    self.ridx = 0;
                }
                0
            }
        }
    }

    pub fn route_due(&self) -> bool {
        let rt = match &self.route {
            Some(r) => r,
            None => return false,
        };
        let el = (uptime_cs().unwrap_or(0) - rt.t0) * 10;
        let di = if el < 0 { 1 } else { el / rt.tk + 1 };
        if di >= rt.nn {
            if self.ridx != rt.nn {
                return true;
            }
            return rt.he > 0 && el >= (rt.nn - 1) * rt.tk + rt.he;
        }
        !rt.trk && di != self.ridx
    }

    fn write_mirror(&self, path: &Path, body: &str, system: bool) -> bool {
        let tmp = PathBuf::from(format!("{}.tmp", path.display()));
        if fs::write(&tmp, format!("{}\n", body)).is_err() {
            return false;
        }
        let _ = fs::set_permissions(&tmp, fs::Permissions::from_mode(0o600));
        if system {
            let _ = chown(&tmp, Some(1000), Some(1000));
            sys::quiet("chcon", &["u:object_r:system_data_file:s0", &tmp.to_string_lossy()]);
        }
        fs::rename(&tmp, path).is_ok()
    }

    pub fn mirror_cfg(&self, mc: &str) {
        if mc.ends_with(";pl=1") {
            return;
        }
        let (sy, ne, bt) = std::thread::scope(|s| {
            let a = s.spawn(|| csget(SYSKEY).unwrap_or_default());
            let b = s.spawn(|| csget(NETKEY).unwrap_or_default());
            let c = csget(BTKEY).unwrap_or_default();
            (a.join().unwrap_or_default(), b.join().unwrap_or_default(), c)
        });
        let body = format!("cfg:{}\nsys:{}\nnet:{}\nbt:{}", mc, sy, ne, bt);
        self.write_mirror(&self.cfgs(), &body, true);
        self.write_mirror(&self.cfgf(), &body, false);
    }

    pub fn mirror_from_settings(&self) {
        if let Some(c) = csget(KEY) {
            if !c.is_empty() {
                self.mirror_cfg(&c);
            }
        }
    }

    fn mirror_stale(&self, cur: &str) -> bool {
        if cur.ends_with(";pl=1") {
            return false;
        }
        let want = format!("cfg:{}", cur);
        !fs::read_to_string(self.cfgs()).is_ok_and(|t| t.lines().any(|l| l == want))
    }

    fn mirror_trk(&mut self, rt: &Route) -> bool {
        let mk = format!("{}:{}:{}", rt.t0, rt.nn, rt.tf.display());
        if self.tmkey == mk {
            return true;
        }
        let mnow = uptime_cs().unwrap_or(0);
        if self.tmfk == mk && mnow - self.tmfat < 3000 {
            return false;
        }
        let trkf = self.trkf();
        let tmp = PathBuf::from(format!("{}.tmp", trkf.display()));
        let mut ok = false;
        if let Ok(body) = fs::read(&rt.tf) {
            let mut data = format!("t={};k={};n={}\n", rt.t0 * 10, rt.tk, rt.nn).into_bytes();
            data.extend_from_slice(&body);
            if fs::write(&tmp, data).is_ok() {
                let _ = fs::set_permissions(&tmp, fs::Permissions::from_mode(0o600));
                let _ = chown(&tmp, Some(1000), Some(1000));
                sys::quiet("chcon", &["u:object_r:system_data_file:s0", &tmp.to_string_lossy()]);
                ok = fs::rename(&tmp, &trkf).is_ok();
            }
        }
        if !ok {
            let _ = fs::remove_file(&tmp);
            self.tmfk = mk;
            self.tmfat = mnow;
            return false;
        }
        self.tmkey = mk;
        self.tmfk.clear();
        true
    }

    fn trk_win(&mut self, rt: &Route, start: i64) -> bool {
        let mut s = start;
        let mut c = TRK_W;
        if s >= rt.nn {
            s = rt.nn;
            c = 1;
        }
        if s + c - 1 > rt.nn {
            c = rt.nn - s + 1;
        }
        let d = tr_squeeze(&window(rt, s, c));
        if d.is_empty() {
            return false;
        }
        self.mirror_trk(rt);
        let val = format!("t={};k={};a={};d={}", rt.t0 * 10 + (s - 1) * rt.tk, rt.tk, self.pc * 10, d);
        if !sys::sput(TRKKEY, &val) {
            return false;
        }
        self.wkey = format!("{}:{}:{}", rt.t0, rt.nn, rt.tf.display());
        self.ws = s;
        self.we = s + c - 1;
        self.hold_at = self.pc;
        true
    }

    pub fn trk_pump(&mut self) -> bool {
        let Some(rt) = self.route.take() else {
            return false;
        };
        let ok = self.pump(&rt);
        self.route = Some(rt);
        ok
    }

    fn pump(&mut self, rt: &Route) -> bool {
        self.pc = uptime_cs().unwrap_or(0);
        let el = (self.pc - rt.t0) * 10;
        let mut ix = if el < 0 { 1 } else { el / rt.tk + 1 };
        if ix > rt.nn {
            ix = rt.nn;
        }
        self.trk_nap = if rt.nn - ix <= 12 { 1 } else { 5 };
        let key = format!("{}:{}:{}", rt.t0, rt.nn, rt.tf.display());
        let need = if self.wkey != key || ix < self.ws || ix > self.we {
            true
        } else if self.we < rt.nn {
            ix >= self.we - TRK_LEAD
        } else {
            ix >= rt.nn && self.pc - self.hold_at >= 1000
        };
        if need {
            return self.trk_win(rt, ix);
        }
        true
    }

    pub fn sync_cfg(&mut self, force: bool) -> bool {
        let rc = self.derive();
        self.new = self.out.clone();
        if rc != 0 {
            return false;
        }
        if self.out.ends_with(";tk=1;pl=1") && !self.trk_pump() {
            if let Some(r) = self.route.as_mut() {
                r.trk = false;
            }
            let of = self.fast;
            self.fast = true;
            self.derive();
            self.fast = of;
            self.new = self.out.clone();
        }
        if !self.new.ends_with(";tk=1;pl=1") {
            let trkf = self.trkf();
            let tmp = PathBuf::from(format!("{}.tmp", trkf.display()));
            if trkf.exists() || tmp.exists() {
                let _ = fs::remove_file(&trkf);
                let _ = fs::remove_file(&tmp);
            }
            self.tmkey.clear();
            self.tmfk.clear();
        }
        let prev = fs::read_to_string(self.lastf()).ok().and_then(|s| s.lines().next().map(String::from)).unwrap_or_default();
        if !force && self.new == prev {
            let now = uptime_cs().unwrap_or(0);
            let cached = !self.kv.is_empty() && self.kv == self.new && now - self.kv_at < KV_TTL;
            let cur = if cached { self.new.clone() } else { csget(KEY).unwrap_or_default() };
            if cur == self.new {
                if !cached {
                    self.kv = cur.clone();
                    self.kv_at = now;
                }
                if self.mirror_stale(&cur) {
                    self.mirror_cfg(&cur);
                }
                return true;
            }
        }
        if !csput(KEY, &self.new) {
            self.kv.clear();
            return false;
        }
        self.kv = self.new.clone();
        self.kv_at = uptime_cs().unwrap_or(0);
        let _ = fs::write(self.lastf(), format!("{}\n", self.new));
        self.mirror_cfg(&self.new);
        true
    }

    pub fn remember(&mut self) {
        self.fast = false;
        if self.derive() == 0 {
            self.new = self.out.clone();
            let _ = fs::write(self.lastf(), format!("{}\n", self.new));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn squeeze_matches_tr() {
        assert_eq!(tr_squeeze(b"  1.0   2.0\n 3.0 4.0\n"), ",1.0,2.0|,3.0,4.0|");
        assert_eq!(tr_squeeze(b"1 2\n"), "1,2|");
    }

    #[test]
    fn sig_empty_without_pref() {
        assert_eq!(sig(""), "");
    }
}
