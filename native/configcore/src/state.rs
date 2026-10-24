use crate::codec;
use crate::file;
use crate::filewatch::Watch;
use crate::parse::{self, BeatState, Prev};
use crate::track::{self, Stale, Track};
use std::fs;
use std::sync::Arc;
use std::time::UNIX_EPOCH;

pub const FILE_PATHS: [&str; 2] = ["/data/system/gpsz_config", "/data/adb/gpsz_config"];
const FILE_CHECK_MS: i64 = 2_000;
const FALLBACK_POLL_MS: i64 = 250;

pub const B_ENABLED: u32 = 1;
pub const B_JITTER: u32 = 2;
pub const B_SYSTEM: u32 = 4;
pub const B_HEARTBEAT: u32 = 8;
pub const B_HIDE_NET: u32 = 16;
pub const B_HIDE_BT: u32 = 32;
pub const B_PLAYING: u32 = 64;
pub const B_TK: u32 = 128;
pub const B_EMPTY: u32 = 256;
pub const B_WAKE: u32 = 512;
pub const B_TIGHT: u32 = 1024;
pub const OUT_LEN: usize = 10;

struct Cur {
    t: Arc<Track>,
    wrote: i64,
}

impl Cur {
    fn end(&self) -> i64 {
        self.t.end_with(self.wrote)
    }
}

pub struct Inputs<'a> {
    pub cfg: Option<&'a str>,
    pub trk: Option<&'a str>,
    pub beat: Option<&'a str>,
    pub net: Option<&'a str>,
    pub bt: Option<&'a str>,
    pub sys: Option<&'a str>,
    pub use_file: bool,
    pub now: i64,
    pub uptime: i64,
    pub trk_path: &'a str,
}

pub struct Core {
    lat: f64,
    lng: f64,
    acc: f32,
    spd: f32,
    brg: f32,
    alt: f64,
    altv: f32,
    rr: f32,
    jitter: bool,
    playing: bool,
    tk_mode: bool,
    cfg_en: bool,
    enabled: bool,
    system: bool,
    heartbeat: bool,
    hide_net: bool,
    hide_bt: bool,
    settings_empty: bool,
    beat_stale: i32,
    trk_stale: i32,
    cur: Option<Cur>,
    win: Option<Arc<Track>>,
    file: Option<Arc<Track>>,
    file_sig: Option<(i64, i64)>,
    file_at: i64,
    trk_seen: Option<String>,
    beat: BeatState,
    stale: Stale,
    files: Watch,
    seq: u64,
}

fn dec_ascii(raw: Option<&str>) -> Option<String> {
    raw.and_then(codec::dec).filter(|s| s.is_ascii())
}

fn sig(path: &str) -> (i64, i64) {
    match fs::metadata(path) {
        Ok(m) => {
            let mt = m
                .modified()
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map_or(0, |d| d.as_millis() as i64);
            (mt, m.len() as i64)
        }
        Err(_) => (0, 0),
    }
}

impl Default for Core {
    fn default() -> Self {
        Core::new()
    }
}

impl Core {
    pub const fn new() -> Core {
        Core {
            lat: f64::NAN,
            lng: f64::NAN,
            acc: 10.0,
            spd: 0.0,
            brg: f32::NAN,
            alt: 0.0,
            altv: 0.0,
            rr: 8.0,
            jitter: false,
            playing: false,
            tk_mode: false,
            cfg_en: false,
            enabled: false,
            system: false,
            heartbeat: false,
            hide_net: true,
            hide_bt: false,
            settings_empty: false,
            beat_stale: 0,
            trk_stale: 0,
            cur: None,
            win: None,
            file: None,
            file_sig: None,
            file_at: 0,
            trk_seen: None,
            beat: BeatState { seen: None, changed_at: 0, armed: false, last_poll_at: 0 },
            stale: Stale::new(),
            files: Watch::new(),
            seq: 0,
        }
    }

    fn bits(&self, extra: u32) -> u32 {
        let mut b = extra;
        for (on, bit) in [
            (self.enabled, B_ENABLED),
            (self.jitter, B_JITTER),
            (self.system, B_SYSTEM),
            (self.heartbeat, B_HEARTBEAT),
            (self.hide_net, B_HIDE_NET),
            (self.hide_bt, B_HIDE_BT),
            (self.playing, B_PLAYING),
            (self.tk_mode, B_TK),
            (self.settings_empty, B_EMPTY),
        ] {
            if on {
                b |= bit;
            }
        }
        b
    }

    fn done(&mut self, extra: u32) -> [f64; OUT_LEN] {
        self.seq += 1;
        [
            self.lat,
            self.lng,
            self.acc as f64,
            self.spd as f64,
            self.brg as f64,
            self.alt,
            self.altv as f64,
            self.rr as f64,
            self.bits(extra) as f64,
            self.seq as f64,
        ]
    }

    fn set_prev(&mut self, lat: f64, lng: f64, acc: f32) {
        self.lat = lat;
        self.lng = lng;
        self.acc = acc;
    }

    fn disable(&mut self) {
        self.enabled = false;
        self.playing = false;
        self.tk_mode = false;
        self.cfg_en = false;
        self.beat_stale = 0;
        self.beat.armed = false;
        self.system = false;
        self.heartbeat = false;
        self.hide_net = true;
        self.hide_bt = false;
    }

    fn parse(&mut self, s: Option<&str>) {
        let s = match s {
            Some(v) if v != "null" && v.is_ascii() => v,
            _ => {
                self.disable();
                return;
            }
        };
        let prev = Prev { lat: self.lat, lng: self.lng, acc: self.acc };
        let p = match parse::parse_strict(s, &prev) {
            Some(p) => p,
            None => {
                self.disable();
                return;
            }
        };
        self.tk_mode = p.tk;
        self.cfg_en = p.enabled;
        self.acc = p.acc;
        self.altv = p.altv;
        self.rr = p.rr;
        self.jitter = p.jitter;
        self.playing = p.playing;
        if !(p.tk && self.cur.is_some()) {
            self.lat = p.lat;
            self.lng = p.lng;
            self.spd = p.spd;
            self.brg = p.brg;
            self.alt = p.alt;
        }
        self.enabled = p.enabled && self.beat_stale < 2 && (!p.tk || self.trk_stale < 2);
    }

    fn check_beat(&mut self, beat: Option<&str>, now: i64) {
        let (level, freeze) = parse::check_beat(beat, self.playing, now, &mut self.beat);
        if level == 2 {
            self.beat_stale = 2;
            self.enabled = false;
        } else if level == 1 && freeze {
            self.beat_stale = 1;
            self.spd = 0.0;
        } else {
            self.beat_stale = 0;
        }
    }

    fn apply(&mut self, now: i64, uptime: i64) {
        let end = self.cur.as_ref().map(Cur::end);
        let st = self.stale.level(now, uptime, end);
        self.trk_stale = st;
        self.enabled = self.cfg_en && st < 2;
        if let Some(c) = &self.cur {
            let a = track::apply_track(&c.t, now, st);
            self.lat = a.lat;
            self.lng = a.lng;
            self.alt = a.alt;
            self.spd = a.spd;
            self.brg = a.brg;
        }
    }

    fn set_flags(&mut self, f: i32) {
        self.system = (f & track::FLAG_SYSTEM) != 0;
        self.heartbeat = (f & track::FLAG_HEARTBEAT) != 0;
        self.hide_net = (f & track::FLAG_HIDE_NET) != 0;
        self.hide_bt = (f & track::FLAG_HIDE_BT) != 0;
    }

    fn reload_file(&mut self, now: i64, force: bool, path: &str) -> bool {
        if !force && now.wrapping_sub(self.file_at) < FILE_CHECK_MS {
            return false;
        }
        self.file_at = now;
        let s = sig(path);
        if self.file_sig == Some(s) {
            return false;
        }
        self.file_sig = Some(s);
        self.file = file::read_track_file(path).map(Arc::new);
        true
    }

    fn merged(&self) -> Option<Cur> {
        let w = self.win.as_ref()?;
        let wrote = w.wrote;
        if let Some(f) = &self.file {
            if f.tk == w.tk {
                let d = w.t0.wrapping_sub(f.t0);
                if d >= 0 && d % f.tk == 0 && d / f.tk < f.n() as i64 {
                    let i = (d / f.tk) as usize;
                    if (f.la[i] - w.la[0]).abs() <= 1e-5 && (f.lo[i] - w.lo[0]).abs() <= 1e-5 {
                        return Some(Cur { t: f.clone(), wrote });
                    }
                }
            }
        }
        Some(Cur { t: w.clone(), wrote })
    }

    pub fn parse_with(&mut self, s: Option<&str>, lat: f64, lng: f64, acc: f32) -> [f64; OUT_LEN] {
        self.set_prev(lat, lng, acc);
        self.parse(s);
        self.done(0)
    }

    pub fn beat_with(&mut self, beat: Option<&str>, now: i64) -> [f64; OUT_LEN] {
        self.check_beat(beat, now);
        self.done(0)
    }

    pub fn tick(&mut self, now: i64, uptime: i64) -> Option<[f64; OUT_LEN]> {
        if !self.tk_mode {
            return None;
        }
        self.apply(now, uptime);
        Some(self.done(0))
    }

    pub fn load_file(&mut self) -> Option<[f64; OUT_LEN]> {
        if !self.files.load(&FILE_PATHS) {
            return None;
        }
        let c = self.files.cfg().map(str::to_string);
        self.parse(c.as_deref());
        let f = self.files.flags(None, None, None, true);
        self.set_flags(f);
        Some(self.done(0))
    }

    pub fn refresh(&mut self, i: &Inputs<'_>) -> [f64; OUT_LEN] {
        let was_on = self.enabled && self.system;
        if i.use_file {
            let interval = if self.settings_empty { FALLBACK_POLL_MS } else { FILE_CHECK_MS };
            self.files.reload(&FILE_PATHS, i.now, interval, self.settings_empty);
        }
        let sc = dec_ascii(i.cfg);
        self.settings_empty = sc.is_none();
        let cs = self.files.or_cfg(sc, i.use_file);
        if cs.as_deref().is_some_and(track::tk_on) {
            let fresh = i.trk != self.trk_seen.as_deref();
            if fresh {
                self.trk_seen = i.trk.map(str::to_string);
                self.win = i.trk.and_then(track::parse_track).map(Arc::new);
            }
            let file_new = self.reload_file(i.now, fresh, i.trk_path);
            if fresh || file_new {
                self.cur = self.merged();
            }
            let end = self.cur.as_ref().map(Cur::end);
            self.trk_stale = self.stale.level(i.now, i.uptime, end);
        } else {
            self.cur = None;
            self.win = None;
            self.file = None;
            self.file_sig = None;
            self.trk_seen = None;
            self.stale.reset(false);
            self.trk_stale = 0;
        }
        let before = self.beat_stale;
        self.parse(cs.as_deref());
        if self.tk_mode {
            self.apply(i.now, i.uptime);
        } else {
            let b = dec_ascii(i.beat);
            self.check_beat(b.as_deref(), i.now);
            if self.beat_stale != before {
                self.parse(cs.as_deref());
            }
        }
        let net = dec_ascii(i.net);
        let bt = dec_ascii(i.bt);
        let sv = dec_ascii(i.sys);
        let was_system = self.system;
        let was_hb = self.heartbeat;
        let f = self.files.flags(sv.as_deref(), net.as_deref(), bt.as_deref(), i.use_file);
        self.set_flags(f);
        let tight = self.system && (!was_system || (was_hb && !self.heartbeat));
        let wake = (!was_on && self.enabled && self.system) || tight;
        let mut extra = 0;
        if wake {
            extra |= B_WAKE;
        }
        if tight {
            extra |= B_TIGHT;
        }
        self.done(extra)
    }
}

pub fn self_test() -> bool {
    let mut c = Core::new();
    c.set_prev(1.0, 2.0, 3.0);
    c.parse(Some("enabled=1;lat=-7.5;lng=110.5;acc=12;random=1;spd=3;brg=90;alt=5;altv=2;rr=10;pl=1;tk=1"));
    let ok = c.enabled
        && c.jitter
        && c.playing
        && c.tk_mode
        && c.lat == -7.5
        && c.lng == 110.5
        && c.acc == 12.0
        && c.spd == 3.0
        && c.brg == 90.0
        && c.alt == 5.0
        && c.altv == 2.0
        && c.rr == 10.0;
    c.parse(Some("lat=5d"));
    if !ok || c.enabled || c.tk_mode {
        return false;
    }
    let t = match track::parse_track("t=1000;a=900;k=1000;d=,1,2,3,4,5|,6,7,8,9,10|") {
        Some(t) => t,
        None => return false,
    };
    c.cur = Some(Cur { t: Arc::new(t), wrote: 900 });
    c.cfg_en = true;
    c.tk_mode = true;
    c.apply(1500, 1500);
    c.enabled && c.lat == 3.5 && c.lng == 4.5 && c.alt == 7.5 && c.spd == 5.5 && (c.brg - 6.5).abs() < 1e-4
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    const CFG: &str = "enabled=1;lat=-7.0;lng=110.0;acc=10;random=0;spd=0;alt=5;altv=0;rr=8;tk=1;pl=1";
    const ROWS: &str = ",-7.000000,110.000000,0.000,350.00,10.00|,-7.100000,110.200000,10.000,10.00,20.00|,-7.200000,110.400000,10.000,10.00,30.00|";

    fn trk(t0: i64, a: i64, k: i64, rows: &str) -> String {
        format!("t={};k={};a={};d={}", t0, k, a, rows)
    }

    fn run(c: &mut Core, now: i64, cfg: &str, trk: Option<&str>) -> [f64; OUT_LEN] {
        let e = codec::enc(cfg);
        c.refresh(&Inputs { cfg: Some(&e), trk, beat: None, net: None, bt: None, sys: None, use_file: false, now, uptime: now, trk_path: "" })
    }

    #[test]
    fn self_test_passes() {
        assert!(self_test());
    }

    #[test]
    fn window_interpolates_and_goes_stale() {
        let mut c = Core::new();
        let t0 = 100_000;
        let w = trk(t0, t0, 500, ROWS);
        run(&mut c, t0, CFG, Some(&w));
        assert!(c.tk_mode && c.enabled);
        assert!((c.lat + 7.0).abs() < 1e-9);
        c.tick(t0 + 250, t0 + 250);
        assert!((c.lat + 7.05).abs() < 1e-9);
        assert!((c.lng - 110.1).abs() < 1e-9);
        assert!((c.alt - 15.0).abs() < 1e-9);
        assert!((c.spd - 5.0).abs() < 1e-4);
        assert!(c.brg.abs() < 1e-3);
        c.tick(t0 + 1000, t0 + 1000);
        assert!((c.lat + 7.2).abs() < 1e-9);
        assert!(c.enabled);
        c.tick(t0 + 1000 + 3500, t0 + 1000 + 3500);
        assert_eq!(c.spd, 0.0);
        assert!(c.enabled);
        c.tick(t0 + 1000 + 61_000, t0 + 1000 + 61_000);
        assert!(!c.enabled);
    }

    #[test]
    fn hold_window_uses_wrote_for_staleness() {
        let mut c = Core::new();
        let h = 800_000;
        let w = trk(200_500, h - 5_000, 500, ",-8.100000,111.100000,0.000,45.00,50.00|");
        run(&mut c, h, CFG, Some(&w));
        assert!(c.enabled);
        assert!((c.lat + 8.1).abs() < 1e-9);
        assert_eq!(c.spd, 0.0);
        run(&mut c, h + 30_000, CFG, Some(&w));
        assert!(c.enabled);
        run(&mut c, h + 70_000, CFG, Some(&w));
        assert!(!c.enabled);
    }

    #[test]
    fn tk_without_window_counts_from_first_miss() {
        let mut c = Core::new();
        run(&mut c, 500_000, CFG, None);
        assert!(c.enabled);
        assert!((c.lat + 7.0).abs() < 1e-9);
        assert_eq!(c.spd, 0.0);
        run(&mut c, 561_000, CFG, None);
        assert!(!c.enabled);
    }

    #[test]
    fn cfg_without_tk_ignores_window() {
        let mut c = Core::new();
        let w = trk(1_000, 1_000, 500, ROWS);
        run(&mut c, 1_000, "enabled=1;lat=-6.5;lng=106.5;acc=10;random=0;spd=7;alt=1;altv=0;rr=8;brg=30;pl=1", Some(&w));
        assert!(!c.tk_mode);
        assert!((c.lat + 6.5).abs() < 1e-9);
        assert_eq!(c.spd, 7.0);
        assert!(c.cur.is_none());
    }

    #[test]
    fn meridian_does_not_jump_through_zero() {
        let mut c = Core::new();
        let a = 400_000;
        let rows = ",10.000000,179.900000,5.000,90.00,0.00|,10.000000,-179.900000,5.000,90.00,0.00|";
        let w = trk(a, a, 500, rows);
        run(&mut c, a, CFG, Some(&w));
        c.tick(a + 250, a + 250);
        assert!((c.lng.abs() - 180.0).abs() < 1e-6);
        c.tick(a + 125, a + 125);
        assert!((c.lng - 179.95).abs() < 1e-6);
        c.tick(a + 375, a + 375);
        assert!((c.lng + 179.95).abs() < 1e-6);
    }

    #[test]
    fn flags_wake_and_tight() {
        let mut c = Core::new();
        let cfg = codec::enc("enabled=1;lat=-7.7;lng=110.4;acc=10;random=0");
        let go = |c: &mut Core, sys: &str, now: i64| {
            let s = codec::enc(sys);
            c.refresh(&Inputs { cfg: Some(&cfg), trk: None, beat: None, net: None, bt: None, sys: Some(&s), use_file: false, now, uptime: now, trk_path: "" })[8] as u32
        };
        let b = go(&mut c, "1", 1_000);
        assert!(b & B_WAKE != 0 && b & B_TIGHT != 0 && b & B_SYSTEM != 0);
        let b = go(&mut c, "2", 2_000);
        assert!(b & B_WAKE == 0 && b & B_TIGHT == 0 && b & B_HEARTBEAT != 0);
        let b = go(&mut c, "1", 3_000);
        assert!(b & B_TIGHT != 0 && b & B_HEARTBEAT == 0);
    }

    #[test]
    fn beat_freeze_then_off() {
        let mut c = Core::new();
        let cfg = codec::enc("enabled=1;lat=-7.7;lng=110.4;acc=10;random=0;spd=5;alt=5;altv=0;rr=8;pl=1");
        let beat = codec::enc("b");
        let at = |c: &mut Core, now: i64| {
            c.refresh(&Inputs { cfg: Some(&cfg), trk: None, beat: Some(&beat), net: None, bt: None, sys: None, use_file: false, now, uptime: now, trk_path: "" });
        };
        at(&mut c, 1_000);
        assert!(c.enabled);
        assert_eq!(c.spd, 5.0);
        for t in 2..=13 {
            at(&mut c, t * 1_000);
        }
        assert!(c.enabled);
        assert_eq!(c.spd, 0.0);
        for t in 14..=62 {
            at(&mut c, t * 1_000);
        }
        assert!(!c.enabled);
    }

    #[test]
    fn file_track_replaces_window_when_aligned() {
        let p = std::env::temp_dir().join(format!("st_{}_trk", std::process::id()));
        let f0: i64 = 2_000_000;
        let mut body = format!("t={};k=500;n=200\n", f0);
        for i in 0..200 {
            body.push_str(&format!("{:11.6} {:11.6} {:7.3} {:6.2} {:8.2}\n", -7.0 - i as f64 * 0.001, 110.0 + i as f64 * 0.001, 5.0, 90.0, 10.0));
        }
        fs::File::create(&p).unwrap().write_all(body.as_bytes()).unwrap();
        let path = p.to_string_lossy().into_owned();
        let mut win = String::from(",");
        for i in 10..13 {
            win.push_str(&format!("{:.6},{:.6},5.000,90.00,10.00|,", -7.0 - i as f64 * 0.001, 110.0 + i as f64 * 0.001));
        }
        let w = format!("t={};k=500;a={};d={}", f0 + 5_000, f0 + 5_000, win);
        let mut c = Core::new();
        let e = codec::enc(CFG);
        let inp = |now: i64| Inputs { cfg: Some(&e), trk: Some(&w), beat: None, net: None, bt: None, sys: None, use_file: false, now, uptime: now, trk_path: &path };
        c.refresh(&inp(f0 + 5_000));
        c.tick(f0 + 7_000, f0 + 7_000);
        assert!((c.lat + 7.014).abs() < 1e-9);
        assert!(c.enabled);
        c.tick(f0 + 60_000, f0 + 60_000);
        assert!((c.lat + 7.120).abs() < 1e-9);
        assert!(c.enabled);
        c.tick(f0 + 99_500 + 61_000, f0 + 99_500 + 61_000);
        assert!(!c.enabled);
        let _ = fs::remove_file(&p);
    }

    #[test]
    fn mismatched_file_falls_back_to_window() {
        let p = std::env::temp_dir().join(format!("st_{}_bad", std::process::id()));
        fs::File::create(&p).unwrap().write_all(b"t=2000000;k=500;n=2\n-1 -1 5 90 10\n-2 -2 5 90 10\n").unwrap();
        let path = p.to_string_lossy().into_owned();
        let w = "t=2005000;k=500;a=2005000;d=,-1.000000,1.000000,5.000,90.00,10.00|,";
        let mut c = Core::new();
        let e = codec::enc(CFG);
        c.refresh(&Inputs { cfg: Some(&e), trk: Some(w), beat: None, net: None, bt: None, sys: None, use_file: false, now: 2_005_000, uptime: 2_005_000, trk_path: &path });
        assert!((c.lat + 1.0).abs() < 1e-9);
        let _ = fs::remove_file(&p);
    }
}
