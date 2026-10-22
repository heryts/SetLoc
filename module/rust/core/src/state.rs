use crate::clock::Clock;
use crate::rng::{gauss, hash_unit};
use crate::track::{self, Track};
use std::f64::consts::PI;

pub const FILE_PATHS: [&str; 2] = ["/data/system/gpsz_config", "/data/adb/gpsz_config"];
pub const TRK_FILE: &str = "/data/system/gpsz_trk";
pub const FILE_MAX: usize = 8192;
pub const TRK_FILE_MAX: usize = 16 << 20;
pub const FREEZE_MS: i64 = 10_000;
pub const OFF_MS: i64 = 60_000;
pub const TRK_GRACE_MS: i64 = 3_000;
pub const GAP_MS: i64 = 3_000;
pub const POLL_MS: i64 = 1_000;
pub const IDLE_POLL_MS: i64 = 5_000;
pub const FALLBACK_POLL_MS: i64 = 250;
pub const RETRY_MS: i64 = 5_000;
pub const SNAP_MS: i64 = 50;
pub const FILE_CHECK_MS: i64 = 2_000;
const FLOOR_RADIUS_M: f64 = 0.8;
const JIT_PERIOD_S: [f64; 4] = [7.0, 13.0, 23.0, 41.0];
const NET_GRID_MS: i64 = 30_000;

pub trait Fs {
    fn stat(&self, path: &str) -> Option<(i64, u64)>;
    fn read(&self, path: &str, max: usize) -> Option<Vec<u8>>;
}

#[derive(Clone, Copy, Debug)]
pub struct Pt {
    pub lat: f64,
    pub lng: f64,
    pub at: i64,
    pub spd: f32,
    pub brg: f32,
    pub alt: f64,
    pub acc_jit: f32,
}

#[derive(Default, Clone)]
struct FileFields {
    cfg: Option<String>,
    sys: Option<String>,
    net: Option<String>,
    bt: Option<String>,
}

pub struct RefreshIn<'a> {
    pub cfg: Option<&'a str>,
    pub trk: Option<&'a str>,
    pub beat: Option<&'a str>,
    pub net: Option<&'a str>,
    pub bt: Option<&'a str>,
    pub sys: Option<&'a str>,
    pub is_system: bool,
}

#[derive(Default, Debug, PartialEq, Eq)]
pub struct RefreshOut {
    pub wake: bool,
    pub call_ready: bool,
}

pub struct State {
    pub enabled: bool,
    pub jitter: bool,
    pub system: bool,
    pub sys_heartbeat: bool,
    pub hide_net: bool,
    pub hide_bt: bool,
    pub playing: bool,
    pub tk_mode: bool,
    pub lat: f64,
    pub lng: f64,
    pub acc: f32,
    pub spd: f32,
    pub brg: f32,
    pub alt: f64,
    pub alt_var: f32,
    pub rr: f32,
    pub trk_file: String,
    pub snap: Option<Pt>,
    jit_n: f64,
    jit_e: f64,
    track: Option<Track>,
    win_track: Option<Track>,
    file_track: Option<Track>,
    file_track_sig: Option<String>,
    file_track_at: i64,
    last_elapsed: i64,
    last_uptime: i64,
    resume_at: i64,
    trk_seen: Option<String>,
    cfg_en: bool,
    trk_stale: i32,
    trk_miss_at: i64,
    tick_at: i64,
    beat_seen: Option<String>,
    beat_changed_at: i64,
    last_poll_at: i64,
    beat_armed: bool,
    beat_stale: i32,
    last_try: i64,
    file: FileFields,
    file_seen: Option<String>,
    file_check_at: i64,
    pub settings_empty: bool,
}

fn in_range(v: f64, limit: f64, fallback: f64) -> f64 {
    if v.abs() <= limit {
        v
    } else {
        fallback
    }
}

fn pf64(s: &str) -> Option<f64> {
    s.trim().parse().ok()
}

fn pf32(s: &str) -> Option<f32> {
    s.trim().parse().ok()
}

fn tk_on(s: Option<&str>) -> bool {
    s.map_or(false, |s| s.split(';').any(|kv| kv.trim() == "tk=1"))
}

fn read_file_fields(buf: &[u8]) -> Option<FileFields> {
    let text = String::from_utf8_lossy(buf);
    let mut f = FileFields::default();
    for line in text.split('\n') {
        let Some(i) = line.find(':') else { continue };
        let k = line[..i].trim();
        let v = line[i + 1..].trim();
        if v.is_empty() || v == "null" {
            continue;
        }
        let slot = match k {
            "cfg" => &mut f.cfg,
            "sys" => &mut f.sys,
            "net" => &mut f.net,
            "bt" => &mut f.bt,
            _ => continue,
        };
        *slot = Some(v.to_string());
    }
    f.cfg.as_ref()?;
    Some(f)
}

impl Default for State {
    fn default() -> Self {
        State {
            enabled: false,
            jitter: false,
            system: false,
            sys_heartbeat: false,
            hide_net: true,
            hide_bt: false,
            playing: false,
            tk_mode: false,
            lat: f64::NAN,
            lng: f64::NAN,
            acc: 10.0,
            spd: 0.0,
            brg: f32::NAN,
            alt: 0.0,
            alt_var: 0.0,
            rr: 8.0,
            trk_file: TRK_FILE.to_string(),
            snap: None,
            jit_n: 0.0,
            jit_e: 0.0,
            track: None,
            win_track: None,
            file_track: None,
            file_track_sig: None,
            file_track_at: 0,
            last_elapsed: 0,
            last_uptime: 0,
            resume_at: 0,
            trk_seen: None,
            cfg_en: false,
            trk_stale: 0,
            trk_miss_at: 0,
            tick_at: 0,
            beat_seen: None,
            beat_changed_at: 0,
            last_poll_at: 0,
            beat_armed: false,
            beat_stale: 0,
            last_try: -RETRY_MS,
            file: FileFields::default(),
            file_seen: None,
            file_check_at: 0,
            settings_empty: false,
        }
    }
}

impl State {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn has_pos(&self) -> bool {
        !self.lat.is_nan() && !self.lng.is_nan()
    }

    pub fn live(&self) -> bool {
        self.enabled && self.has_pos()
    }

    pub fn track_stale_level(&self) -> i32 {
        self.trk_stale
    }

    pub fn next_poll_ms(&self, observed: bool, is_system: bool) -> i64 {
        if !observed || (self.settings_empty && is_system) {
            FALLBACK_POLL_MS
        } else if self.playing {
            POLL_MS
        } else {
            IDLE_POLL_MS
        }
    }

    pub fn snapshot(&mut self, clk: &dyn Clock) -> Pt {
        if let Some(p) = self.snap {
            if clk.elapsed_ms() - p.at < SNAP_MS {
                return p;
            }
        }
        self.renew_snapshot(clk)
    }

    fn renew_snapshot(&mut self, clk: &dyn Clock) -> Pt {
        self.tick(clk);
        let now = clk.elapsed_ms();
        if let Some(p) = self.snap {
            if now - p.at < SNAP_MS {
                return p;
            }
        }
        let (mut la, mut lo) = (self.lat, self.lng);
        let (s_spd, s_brg) = (self.spd, self.brg);
        let s_alt = self.altitude_now(now);
        let radius = if self.jitter { self.rr as f64 } else { FLOOR_RADIUS_M };
        self.step_jitter(now, radius);
        let mut c = la.to_radians().cos();
        if c.abs() < 1e-6 {
            c = 1e-6;
        }
        la += self.jit_n / 111_320.0;
        lo += self.jit_e / (111_320.0 * c);
        la = la.max(-90.0).min(90.0);
        if lo > 180.0 {
            lo -= 360.0;
        } else if lo < -180.0 {
            lo += 360.0;
        }
        let acc_jit = (gauss(31, now / SNAP_MS) * 0.35) as f32;
        let p = Pt { lat: la, lng: lo, at: now, spd: s_spd, brg: s_brg, alt: s_alt, acc_jit };
        self.snap = Some(p);
        p
    }

    fn wave(axis: i64, t_sec: f64, sigma: f64) -> f64 {
        let mut sum = 0.0;
        for (i, period) in JIT_PERIOD_S.iter().enumerate() {
            let phase = hash_unit(100 + axis, i as i64) * 2.0 * PI;
            sum += (2.0 * PI * t_sec / period + phase).sin();
        }
        sum * sigma / (JIT_PERIOD_S.len() as f64 / 2.0).sqrt()
    }

    fn step_jitter(&mut self, now: i64, r: f64) {
        if r <= 0.0 {
            self.jit_n = 0.0;
            self.jit_e = 0.0;
            return;
        }
        let sigma = r / 3.0;
        let t = now as f64 / 1000.0;
        self.jit_n = Self::wave(1, t, sigma);
        self.jit_e = Self::wave(2, t, sigma);
        let d = self.jit_n.hypot(self.jit_e);
        if d > r {
            self.jit_n *= r / d;
            self.jit_e *= r / d;
        }
    }

    pub fn altitude_now(&self, now_ms: i64) -> f64 {
        let v = self.alt_var;
        if v <= 0.0 {
            return self.alt;
        }
        let sec = now_ms / 1000;
        let mut n = 0.0;
        let mut w = 4.0;
        let mut tot = 0.0;
        for i in 0..3 {
            n += w * (hash_unit(41, sec - i) * 2.0 - 1.0);
            tot += w;
            w /= 2.0;
        }
        self.alt + v as f64 * n / tot
    }

    pub fn acc_now(&self, p: Option<&Pt>) -> f32 {
        (self.acc + p.map_or(0.0, |p| p.acc_jit)).max(1.0)
    }

    pub fn v_acc(&self, p: &Pt) -> f32 {
        (self.acc_now(Some(p)) * 1.3 + p.acc_jit * 1.5).max(1.0)
    }

    pub fn s_acc(p: &Pt) -> f32 {
        (0.4 + p.acc_jit.abs() * 0.5).max(0.1)
    }

    pub fn b_acc(p: &Pt) -> f32 {
        (10.0 + p.acc_jit.abs() * 8.0).max(1.0)
    }

    fn note_resume(&mut self, now: i64, up: i64) {
        if self.last_elapsed != 0 && (now - self.last_elapsed) - (up - self.last_uptime) > GAP_MS {
            self.resume_at = now;
        }
        self.last_elapsed = now;
        self.last_uptime = up;
    }

    fn track_stale(&mut self, clk: &dyn Clock) -> i32 {
        let now = clk.elapsed_ms();
        self.note_resume(now, clk.uptime_ms());
        let late = match &self.track {
            None => {
                if self.trk_miss_at == 0 {
                    self.trk_miss_at = now;
                }
                now - self.trk_miss_at.max(self.resume_at)
            }
            Some(t) => {
                self.trk_miss_at = 0;
                now - (t.t0 + (t.n as i64 - 1) * t.tk).max(t.wrote).max(self.resume_at)
            }
        };
        if late >= OFF_MS {
            2
        } else if late >= TRK_GRACE_MS {
            1
        } else {
            0
        }
    }

    pub fn tick(&mut self, clk: &dyn Clock) {
        if !self.tk_mode {
            return;
        }
        let now = clk.elapsed_ms();
        let d = now - self.tick_at;
        if (0..50).contains(&d) {
            return;
        }
        self.tick_at = now;
        self.apply_track(clk);
    }

    fn apply_track(&mut self, clk: &dyn Clock) {
        let st = self.track_stale(clk);
        self.trk_stale = st;
        self.enabled = self.cfg_en && st < 2;
        let Some(t) = &self.track else { return };
        let s = track::sample(t, clk.elapsed_ms() - t.t0, st);
        self.lat = s.lat;
        self.lng = s.lng;
        self.alt = s.alt;
        self.spd = s.spd;
        self.brg = s.brg;
    }

    pub fn parse(&mut self, s: Option<&str>) {
        let Some(s) = s.filter(|s| *s != "null") else {
            self.enabled = false;
            self.playing = false;
            self.tk_mode = false;
            self.cfg_en = false;
            self.beat_stale = 0;
            self.beat_armed = false;
            self.system = false;
            self.sys_heartbeat = false;
            self.hide_net = true;
            self.hide_bt = false;
            return;
        };
        let (mut en, mut jit, mut pl, mut tk) = (false, false, false, false);
        let (mut la, mut lo) = (self.lat, self.lng);
        let mut ac = self.acc;
        let (mut sp, mut br, mut av, mut rad) = (0f32, f32::NAN, 0f32, 8f32);
        let mut al = 0.0f64;
        for kv in s.split(';') {
            let Some(i) = kv.find('=') else { continue };
            let k = kv[..i].trim();
            let v = kv[i + 1..].trim();
            match k {
                "enabled" => en = v == "1",
                "lat" => {
                    if let Some(x) = pf64(v) {
                        la = in_range(x, 90.0, la);
                    }
                }
                "lng" => {
                    if let Some(x) = pf64(v) {
                        lo = in_range(x, 180.0, lo);
                    }
                }
                "acc" => {
                    if let Some(x) = pf32(v) {
                        ac = in_range(x as f64, 1e5, ac as f64) as f32;
                    }
                }
                "random" => jit = v == "1",
                "spd" => {
                    if let Some(x) = pf32(v) {
                        sp = (in_range(x as f64, 1e4, 0.0) as f32).max(0.0);
                    }
                }
                "brg" => {
                    if let Some(b) = pf32(v) {
                        br = if b.is_nan() || b.is_infinite() || b < 0.0 { f32::NAN } else { b % 360.0 };
                    }
                }
                "alt" => {
                    if let Some(x) = pf64(v) {
                        al = in_range(x, 1e5, 0.0);
                    }
                }
                "altv" => {
                    if let Some(x) = pf32(v) {
                        av = (in_range(x as f64, 1e4, 0.0) as f32).max(0.0);
                    }
                }
                "rr" => {
                    if let Some(x) = pf32(v) {
                        rad = (in_range(x as f64, 1e4, 8.0) as f32).max(0.0).min(100.0);
                    }
                }
                "pl" => pl = v == "1",
                "tk" => tk = v == "1",
                _ => {}
            }
        }
        self.tk_mode = tk;
        self.cfg_en = en;
        self.acc = ac;
        self.alt_var = av;
        self.rr = rad;
        self.jitter = jit;
        self.playing = pl;
        if !(tk && self.track.is_some()) {
            self.lat = la;
            self.lng = lo;
            self.spd = sp;
            self.brg = br;
            self.alt = al;
        }
        self.enabled = en && self.beat_stale < 2 && (!tk || self.trk_stale < 2);
    }

    pub fn check_beat(&mut self, clk: &dyn Clock, beat: Option<&str>) {
        let now = clk.elapsed_ms();
        let gap = self.last_poll_at != 0 && now - self.last_poll_at > GAP_MS;
        self.last_poll_at = now;
        if !self.playing {
            self.beat_armed = false;
            self.beat_stale = 0;
            return;
        }
        if !self.beat_armed || gap || beat != self.beat_seen.as_deref() {
            self.beat_armed = true;
            self.beat_seen = beat.map(str::to_string);
            self.beat_changed_at = now;
            self.beat_stale = 0;
            return;
        }
        let stale = now - self.beat_changed_at;
        if stale >= OFF_MS {
            self.beat_stale = 2;
            self.enabled = false;
        } else if stale >= FREEZE_MS {
            self.beat_stale = 1;
            self.spd = 0.0;
        }
    }

    fn apply_flags(&mut self, sv: Option<&str>, net: Option<&str>, bt: Option<&str>) {
        self.system = matches!(sv, Some("1") | Some("2"));
        self.sys_heartbeat = sv == Some("2");
        self.hide_net = net != Some("0");
        self.hide_bt = bt == Some("1");
    }

    fn reload_track_file(&mut self, now: i64, force: bool, fs: &dyn Fs) -> bool {
        if !force && now - self.file_track_at < FILE_CHECK_MS {
            return false;
        }
        self.file_track_at = now;
        let (mt, len) = fs.stat(&self.trk_file).unwrap_or((0, 0));
        let sig = format!("{}:{}", mt, len);
        if self.file_track_sig.as_deref() == Some(&sig) {
            return false;
        }
        self.file_track_sig = Some(sig);
        self.file_track = if len == 0 || len > TRK_FILE_MAX as u64 {
            None
        } else {
            fs.read(&self.trk_file, TRK_FILE_MAX)
                .filter(|b| b.len() as u64 == len)
                .and_then(|b| track::parse_file(&b))
        };
        true
    }

    fn reload_file_fields(&mut self, now: i64, fs: &dyn Fs) {
        let gap = if self.settings_empty { FALLBACK_POLL_MS } else { FILE_CHECK_MS };
        if now - self.file_check_at < gap {
            return;
        }
        self.file_check_at = now;
        for path in FILE_PATHS {
            let Some((mt, len)) = fs.stat(path) else { continue };
            if len == 0 {
                continue;
            }
            let sig = format!("{}:{}:{}", path, mt, len);
            if self.file_seen.as_deref() == Some(&sig) {
                return;
            }
            let Some(buf) = fs.read(path, FILE_MAX) else { continue };
            let Some(f) = read_file_fields(&buf) else { continue };
            self.file = f;
            self.file_seen = Some(sig);
            return;
        }
        if self.settings_empty {
            self.file = FileFields::default();
            self.file_seen = None;
        }
    }

    pub fn load_from_file(&mut self, fs: &dyn Fs) -> bool {
        for path in FILE_PATHS {
            let Some(buf) = fs.read(path, FILE_MAX) else { continue };
            let Some(f) = read_file_fields(&buf) else { continue };
            self.file = f.clone();
            self.parse(f.cfg.as_deref());
            self.apply_flags(f.sys.as_deref(), f.net.as_deref(), f.bt.as_deref());
            return true;
        }
        false
    }

    pub fn refresh(&mut self, clk: &dyn Clock, fs: &dyn Fs, inp: &RefreshIn) -> RefreshOut {
        let was_on = self.enabled && self.system;
        if inp.is_system {
            self.reload_file_fields(clk.elapsed_ms(), fs);
        }
        let use_file = inp.is_system && self.file.cfg.is_some();
        self.settings_empty = inp.cfg.is_none();
        let pick = |v: Option<&str>, f: &Option<String>| -> Option<String> {
            match v {
                Some(x) => Some(x.to_string()),
                None if use_file => f.clone(),
                None => None,
            }
        };
        let cs = pick(inp.cfg, &self.file.cfg);
        let rt = clk.elapsed_ms();
        if tk_on(cs.as_deref()) {
            let fresh = inp.trk != self.trk_seen.as_deref();
            if fresh {
                self.trk_seen = inp.trk.map(str::to_string);
                self.win_track = inp.trk.and_then(track::parse_window);
            }
            let file_new = self.reload_track_file(rt, fresh, fs);
            if fresh || file_new {
                self.track = track::merge_file(self.win_track.as_ref(), self.file_track.as_ref());
            }
            self.trk_stale = self.track_stale(clk);
        } else {
            self.track = None;
            self.win_track = None;
            self.file_track = None;
            self.file_track_sig = None;
            self.trk_seen = None;
            self.trk_miss_at = 0;
            self.trk_stale = 0;
        }
        let stale_before = self.beat_stale;
        self.parse(cs.as_deref());
        if self.tk_mode {
            self.apply_track(clk);
        } else {
            self.check_beat(clk, inp.beat);
            if self.beat_stale != stale_before {
                self.parse(cs.as_deref());
            }
        }
        let net = pick(inp.net, &self.file.net);
        let bt = pick(inp.bt, &self.file.bt);
        let sv = pick(inp.sys, &self.file.sys);
        let (was_system, was_hb) = (self.system, self.sys_heartbeat);
        self.apply_flags(sv.as_deref(), net.as_deref(), bt.as_deref());
        let tightened = self.system && (!was_system || (was_hb && !self.sys_heartbeat));
        let mut out = RefreshOut::default();
        if (!was_on && self.enabled && self.system) || tightened {
            out.wake = true;
        }
        if inp.is_system && self.system {
            let now = clk.elapsed_ms();
            if tightened || now - self.last_try >= RETRY_MS {
                self.last_try = now;
                out.call_ready = true;
            }
        }
        out
    }
}

pub fn gnss_extras(elapsed_ms: i64) -> [i32; 3] {
    let t = elapsed_ms / 1000;
    let tf = t as f64;
    let p = |i: i64| hash_unit(i, 0) * 2.0 * PI;
    let round = |x: f64| (x + 0.5).floor() as i32;
    let sat = 9 + round(2.2 * (tf / 53.0 + p(11)).sin() + 1.6 * (tf / 17.0 + p(12)).sin());
    let mean = 29 + round(3.0 * (tf / 71.0 + p(13)).sin() + 1.5 * (tf / 23.0 + p(14)).sin());
    let sat = sat.max(5).min(14);
    let mean = mean.max(22).min(36);
    [sat, mean, mean + 9 + (hash_unit(15, t) * 3.0) as i32]
}

fn net_boundary(k: i64) -> i64 {
    k * NET_GRID_MS + ((hash_unit(20, k) - 0.5) * 10_000.0) as i64
}

pub fn network_offset(elapsed_ms: i64, ref_lat: f64) -> [f64; 2] {
    let mut k = elapsed_ms / NET_GRID_MS;
    if net_boundary(k + 1) <= elapsed_ms {
        k += 1;
    } else if net_boundary(k) > elapsed_ms {
        k -= 1;
    }
    let ang = hash_unit(21, k) * 2.0 * PI;
    let dist = 35.0 + hash_unit(22, k) * 90.0;
    let mut c = ref_lat.to_radians().cos();
    if c.abs() < 1e-6 {
        c = 1e-6;
    }
    [ang.sin() * dist / 111_320.0, ang.cos() * dist / (111_320.0 * c)]
}

pub fn network_accuracy(elapsed_ms: i64, gps_acc: f32) -> f32 {
    (gps_acc * 2.5 + 20.0 + (gauss(51, elapsed_ms / 1000) * 6.0) as f32).max(25.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::ManualClock;
    use std::collections::HashMap;

    #[derive(Default)]
    struct MemFs(HashMap<String, Vec<u8>>);

    impl Fs for MemFs {
        fn stat(&self, p: &str) -> Option<(i64, u64)> {
            self.0.get(p).map(|b| (1, b.len() as u64))
        }

        fn read(&self, p: &str, max: usize) -> Option<Vec<u8>> {
            self.0.get(p).map(|b| b[..b.len().min(max)].to_vec())
        }
    }

    fn inp<'a>(cfg: Option<&'a str>) -> RefreshIn<'a> {
        RefreshIn { cfg, trk: None, beat: None, net: None, bt: None, sys: None, is_system: false }
    }

    #[test]
    fn parse_basic() {
        let mut s = State::new();
        s.parse(Some("enabled=1;lat=-7.78;lng=110.36;acc=12;random=1;spd=3.5;brg=450;alt=100;altv=2;rr=500;pl=0"));
        assert!(s.enabled && s.has_pos() && s.jitter);
        assert_eq!(s.lat, -7.78);
        assert_eq!(s.acc, 12.0);
        assert_eq!(s.brg, 90.0);
        assert_eq!(s.rr, 100.0);
        assert_eq!(s.alt_var, 2.0);
    }

    #[test]
    fn parse_out_of_range_keeps_previous() {
        let mut s = State::new();
        s.parse(Some("enabled=1;lat=10;lng=20"));
        s.parse(Some("enabled=1;lat=95;lng=200"));
        assert_eq!((s.lat, s.lng), (10.0, 20.0));
    }

    #[test]
    fn parse_null_disables() {
        let mut s = State::new();
        s.parse(Some("enabled=1;lat=1;lng=2"));
        s.parse(Some("null"));
        assert!(!s.enabled && !s.system);
        s.parse(None);
        assert!(!s.enabled);
    }

    #[test]
    fn negative_bearing_is_nan() {
        let mut s = State::new();
        s.parse(Some("enabled=1;lat=1;lng=2;brg=-1"));
        assert!(s.brg.is_nan());
    }

    #[test]
    fn snapshot_caches_within_window_and_jitters_below_floor() {
        let clk = ManualClock::new(100_000);
        let mut s = State::new();
        s.parse(Some("enabled=1;lat=-7.78;lng=110.36;random=0"));
        let a = s.snapshot(&clk);
        clk.advance(10);
        let b = s.snapshot(&clk);
        assert_eq!(a.at, b.at);
        clk.advance(100);
        let c = s.snapshot(&clk);
        assert_ne!(a.at, c.at);
        let d_lat = (c.lat - -7.78) * 111_320.0;
        let d_lng = (c.lng - 110.36) * 111_320.0 * (-7.78f64).to_radians().cos();
        assert!(d_lat.hypot(d_lng) <= 0.8 + 1e-6);
    }

    #[test]
    fn random_radius_bounds_offset() {
        let clk = ManualClock::new(5_000);
        let mut s = State::new();
        s.parse(Some("enabled=1;lat=0;lng=0;random=1;rr=20"));
        let mut max = 0.0f64;
        for _ in 0..500 {
            clk.advance(137);
            let p = s.snapshot(&clk);
            max = max.max((p.lat * 111_320.0).hypot(p.lng * 111_320.0));
        }
        assert!(max <= 20.0 + 1e-6);
        assert!(max > 1.0);
    }

    #[test]
    fn beat_freezes_then_disables() {
        let clk = ManualClock::new(1_000);
        let fs = MemFs::default();
        let mut s = State::new();
        let mut i = inp(Some("enabled=1;lat=1;lng=2;spd=5;pl=1"));
        i.beat = Some("a");
        s.refresh(&clk, &fs, &i);
        assert!(s.enabled);
        for _ in 0..12 {
            clk.advance(1_000);
            s.refresh(&clk, &fs, &i);
        }
        assert_eq!(s.spd, 0.0);
        assert!(s.enabled);
        for _ in 0..60 {
            clk.advance(1_000);
            s.refresh(&clk, &fs, &i);
        }
        assert!(!s.enabled);
        i.beat = Some("b");
        clk.advance(1_000);
        s.refresh(&clk, &fs, &i);
        assert!(s.enabled);
        assert_eq!(s.spd, 5.0);
    }

    #[test]
    fn flags_and_wake() {
        let clk = ManualClock::new(1_000);
        let fs = MemFs::default();
        let mut s = State::new();
        let mut i = inp(Some("enabled=1;lat=1;lng=2"));
        i.sys = Some("1");
        i.is_system = true;
        let out = s.refresh(&clk, &fs, &i);
        assert!(s.system && !s.sys_heartbeat);
        assert!(out.wake && out.call_ready);
        i.sys = Some("2");
        s.refresh(&clk, &fs, &i);
        assert!(s.sys_heartbeat);
        i.sys = Some("1");
        let out = s.refresh(&clk, &fs, &i);
        assert!(out.wake && out.call_ready);
    }

    #[test]
    fn file_fallback_when_settings_empty() {
        let clk = ManualClock::new(10_000);
        let mut fs = MemFs::default();
        fs.0.insert(FILE_PATHS[0].to_string(), b"cfg:enabled=1;lat=3;lng=4\nsys:1\nnet:0\n".to_vec());
        let mut s = State::new();
        let mut i = inp(None);
        i.is_system = true;
        s.refresh(&clk, &fs, &i);
        assert!(s.enabled && s.system);
        assert_eq!((s.lat, s.lng), (3.0, 4.0));
        assert!(!s.hide_net);
        assert!(s.settings_empty);
    }

    #[test]
    fn load_from_file_applies() {
        let mut fs = MemFs::default();
        fs.0.insert(FILE_PATHS[1].to_string(), b"cfg:enabled=1;lat=5;lng=6\nsys:2\nbt:1\n".to_vec());
        let mut s = State::new();
        assert!(s.load_from_file(&fs));
        assert!(s.enabled && s.sys_heartbeat && s.hide_bt);
    }

    #[test]
    fn track_mode_follows_window() {
        let clk = ManualClock::new(100_000);
        let fs = MemFs::default();
        let mut s = State::new();
        let t0 = clk.elapsed_ms();
        let win = format!("t={};a={};k=1000;d=0,0,10,90,0|0,0.001,10,90,0|0,0.002,10,90,0", t0, t0);
        let mut i = inp(Some("enabled=1;lat=0;lng=0;tk=1"));
        i.trk = Some(&win);
        s.refresh(&clk, &fs, &i);
        clk.advance(500);
        s.tick(&clk);
        assert!(s.enabled);
        assert!((s.lng - 0.0005).abs() < 1e-6);
        assert_eq!(s.spd, 10.0);
        clk.advance(70_000);
        s.refresh(&clk, &fs, &i);
        assert!(!s.enabled);
    }

    #[test]
    fn extras_within_bounds() {
        for t in (0..100_000i64).step_by(997) {
            let [sat, mean, max] = gnss_extras(t * 1000);
            assert!((5..=14).contains(&sat));
            assert!((22..=36).contains(&mean));
            assert!(max >= mean + 9 && max <= mean + 11);
        }
    }

    #[test]
    fn network_offset_is_stable_within_cell() {
        let a = network_offset(60_000 + 14_000, -7.0);
        let b = network_offset(60_000 + 14_500, -7.0);
        assert_eq!(a, b);
        let d = a[0].hypot(a[1] * (-7.0f64).to_radians().cos()) * 111_320.0;
        assert!((35.0..=125.0).contains(&d));
        assert!(network_accuracy(1000, 5.0) >= 25.0);
    }
}
