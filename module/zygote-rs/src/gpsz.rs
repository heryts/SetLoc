
#![allow(dead_code)]

pub mod codec {

pub const SALT: &str = "Vzvwk4Rxj7Ps";

pub fn tag_with(salt: &str, logical: &str) -> String {
    let mut h: i32 = 0x811C_9DC5u32 as i32;
    for o in salt.bytes().chain(logical.bytes()) {
        h = (h ^ o as i32).wrapping_mul(16_777_619) & 0x7FFF_FFFF;
    }
    format!("{:06x}", h & 0xFF_FFFF)
}

pub fn tag(logical: &str) -> String {
    tag_with(SALT, logical)
}

pub fn enc_with(salt: &str, plain: &str) -> String {
    let k = salt.as_bytes();
    let mut out = String::with_capacity(plain.len() * 2);
    for (i, c) in plain.bytes().enumerate() {
        let v = (c as u32 + k[i % k.len()] as u32) % 256;
        out.push_str(&format!("{:02x}", v));
    }
    out
}

pub fn enc(plain: &str) -> String {
    enc_with(SALT, plain)
}

pub fn dec_with(salt: &str, hex: &str) -> Option<String> {
    let k = salt.as_bytes();
    let b = hex.as_bytes();
    let mut out = String::with_capacity(b.len() / 2);
    let mut i = 0;
    while i + 1 < b.len() {
        let pair = std::str::from_utf8(&b[i..i + 2]).ok()?;
        let x = u8::from_str_radix(pair, 16).ok()? as i32;
        let kv = k[(i / 2) % k.len()] as i32;
        let v = (((x - kv) % 256) + 256) % 256;
        out.push(v as u8 as char);
        i += 2;
    }
    Some(out)
}

pub fn dec(hex: &str) -> Option<String> {
    dec_with(SALT, hex)
}

#[cfg(test)]
mod tests {
    use super::*;
    const S: &str = "Vzvwk4Rxj7Ps";

    #[test]
    fn tag_vectors() {
        assert_eq!(tag_with(S, "cfg"), "956349");
        assert_eq!(tag_with(S, "sys"), "db078e");
        assert_eq!(tag_with(S, "beat"), "c1e67d");
        assert_eq!(tag_with(S, "trk"), "c724d0");
        assert_eq!(tag_with(S, "net"), "70a4b0");
        assert_eq!(tag_with(S, "bt"), "50a6bb");
        assert_eq!(tag_with(S, "live"), "ca29e7");
    }

    #[test]
    fn enc_dec_roundtrip() {
        assert_eq!(enc_with(S, "1234567"), "87aca9aba06a89");
        assert_eq!(dec_with(S, "87aca9aba06a89").as_deref(), Some("1234567"));
        assert_eq!(dec_with(S, "zz"), None);
    }
}
}

pub mod prng {

use crate::codec::SALT;
use std::f64::consts::PI;

const JIT_PERIOD_S: [f64; 4] = [7.0, 13.0, 23.0, 41.0];
pub const NET_GRID_MS: i64 = 30_000;

pub fn seed_from(salt: &str) -> u64 {
    let mut h: u64 = 0xCBF2_9CE4_8422_2325;
    for b in salt.bytes() {
        h = (h ^ b as u64).wrapping_mul(0x1_0000_0001_B3);
    }
    h
}

fn mix(z: u64) -> u64 {
    let mut z = z.wrapping_add(0x9E37_79B9_7F4A_7C15);
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

pub struct Rng {
    seed: u64,
}

impl Rng {
    pub fn new(salt: &str) -> Self {
        Rng { seed: seed_from(salt) }
    }

    pub fn global() -> Self {
        Rng::new(SALT)
    }

    pub fn hash_unit(&self, a: i64, b: i64) -> f64 {
        let h = mix(self.seed ^ mix(a as u64) ^ (mix(b as u64) << 1));
        (h >> 11) as f64 * (1.0 / (1u64 << 53) as f64)
    }

    pub fn gauss(&self, salt: i64, bucket: i64) -> f64 {
        (self.hash_unit(salt, bucket)
            + self.hash_unit(salt + 1000, bucket)
            + self.hash_unit(salt + 2000, bucket)
            - 1.5)
            * 2.0
    }

    fn wave(&self, axis: i64, t_sec: f64, sigma: f64) -> f64 {
        let mut sum = 0.0;
        for (i, p) in JIT_PERIOD_S.iter().enumerate() {
            let phase = self.hash_unit(100 + axis, i as i64) * 2.0 * PI;
            sum += (2.0 * PI * t_sec / p + phase).sin();
        }
        sum * sigma / (JIT_PERIOD_S.len() as f64 / 2.0).sqrt()
    }

    pub fn jitter(&self, now_ms: i64, r: f64) -> (f64, f64) {
        if r <= 0.0 {
            return (0.0, 0.0);
        }
        let sigma = r / 3.0;
        let t = now_ms as f64 / 1000.0;
        let mut n = self.wave(1, t, sigma);
        let mut e = self.wave(2, t, sigma);
        let d = n.hypot(e);
        if d > r {
            n *= r / d;
            e *= r / d;
        }
        (n, e)
    }

    pub fn gnss_extras(&self, t_ms: i64) -> [i32; 3] {
        let t = t_ms / 1000;
        let tf = t as f64;
        let p = |i: i64| self.hash_unit(i, 0) * 2.0 * PI;
        let (p1, p2, p3, p4) = (p(11), p(12), p(13), p(14));
        let mut sat = 9 + jround(2.2 * (tf / 53.0 + p1).sin() + 1.6 * (tf / 17.0 + p2).sin());
        let mut mean = 29 + jround(3.0 * (tf / 71.0 + p3).sin() + 1.5 * (tf / 23.0 + p4).sin());
        sat = sat.clamp(5, 14);
        mean = mean.clamp(22, 36);
        [sat, mean, mean + 9 + (self.hash_unit(15, t) * 3.0) as i32]
    }

    fn net_boundary(&self, k: i64) -> i64 {
        k * NET_GRID_MS + ((self.hash_unit(20, k) - 0.5) * 10_000.0) as i64
    }

    pub fn network_offset(&self, ref_lat: f64, t_ms: i64) -> (f64, f64) {
        let mut k = t_ms / NET_GRID_MS;
        if self.net_boundary(k + 1) <= t_ms {
            k += 1;
        } else if self.net_boundary(k) > t_ms {
            k -= 1;
        }
        let ang = self.hash_unit(21, k) * 2.0 * PI;
        let dist = 35.0 + self.hash_unit(22, k) * 90.0;
        let mut c = ref_lat.to_radians().cos();
        if c.abs() < 1e-6 {
            c = 1e-6;
        }
        (ang.sin() * dist / 111_320.0, ang.cos() * dist / (111_320.0 * c))
    }

    pub fn network_accuracy(&self, gps_acc: f32, t_ms: i64) -> f32 {
        let g = self.gauss(51, t_ms / 1000) * 6.0;
        (gps_acc * 2.5 + 20.0 + g as f32).max(25.0)
    }

    pub fn altitude_now(&self, alt: f64, alt_var: f32, t_ms: i64) -> f64 {
        if alt_var <= 0.0 {
            return alt;
        }
        let sec = t_ms / 1000;
        let (mut n, mut w, mut tot) = (0.0, 4.0, 0.0);
        for i in 0..3 {
            n += w * (self.hash_unit(41, sec - i) * 2.0 - 1.0);
            tot += w;
            w /= 2.0;
        }
        alt + alt_var as f64 * n / tot
    }
}

fn jround(x: f64) -> i32 {
    (x + 0.5).floor() as i32
}

#[cfg(test)]
mod tests {
    use super::*;
    const S: &str = "Vzvwk4Rxj7Ps";

    #[test]
    fn seed_vector() {
        assert_eq!(seed_from(S), 8679242076873642813);
    }

    #[test]
    fn hash_unit_vectors() {
        let r = Rng::new(S);
        let close = |a: f64, b: f64| (a - b).abs() < 1e-15;
        assert!(close(r.hash_unit(901, 5), 0.5706560952541347));
        assert!(close(r.hash_unit(11, 0), 0.7368517896031725));
        assert!(close(r.hash_unit(20, 3), 0.920501830988786));
    }

    #[test]
    fn jitter_bounded() {
        let r = Rng::new(S);
        for ms in (0..200_000).step_by(997) {
            let (n, e) = r.jitter(ms, 8.0);
            assert!(n.hypot(e) <= 8.0 + 1e-9);
        }
        assert_eq!(r.jitter(1234, 0.0), (0.0, 0.0));
    }

    #[test]
    fn gnss_extras_in_range() {
        let r = Rng::new(S);
        for ms in (0..500_000).step_by(1000) {
            let g = r.gnss_extras(ms);
            assert!((5..=14).contains(&g[0]));
            assert!((22..=36).contains(&g[1]));
            assert!(g[2] >= g[1] + 9);
        }
    }
}
}

pub mod cfg {

#[derive(Clone, Debug)]
pub struct Cfg {
    pub en: bool,
    pub jitter: bool,
    pub playing: bool,
    pub tk: bool,
    pub lat: f64,
    pub lng: f64,
    pub acc: f32,
    pub spd: f32,
    pub brg: f32,
    pub alt: f64,
    pub alt_var: f32,
    pub rr: f32,
}

impl Default for Cfg {
    fn default() -> Self {
        Cfg {
            en: false, jitter: false, playing: false, tk: false,
            lat: f64::NAN, lng: f64::NAN, acc: 10.0, spd: 0.0,
            brg: f32::NAN, alt: 0.0, alt_var: 0.0, rr: 8.0,
        }
    }
}

impl Cfg {
    pub fn has_pos(&self) -> bool {
        !self.lat.is_nan() && !self.lng.is_nan()
    }
}

fn in_range(v: f64, limit: f64, fallback: f64) -> f64 {
    if v.abs() <= limit { v } else { fallback }
}

fn pf64(v: &str) -> Option<f64> { v.trim().parse::<f64>().ok() }
fn pf32(v: &str) -> Option<f32> { v.trim().parse::<f32>().ok() }

pub fn parse(s: Option<&str>, prev: &Cfg) -> Option<Cfg> {
    let s = match s {
        None => return None,
        Some("null") => return None,
        Some(s) => s,
    };
    let mut c = Cfg {
        en: false, jitter: false, playing: false, tk: false,
        lat: prev.lat, lng: prev.lng, acc: prev.acc, spd: 0.0,
        brg: f32::NAN, alt: 0.0, alt_var: 0.0, rr: 8.0,
    };
    for kv in s.split(';') {
        let i = match kv.find('=') { Some(i) => i, None => continue };
        let k = kv[..i].trim();
        let v = kv[i + 1..].trim();
        match k {
            "enabled" => c.en = v == "1",
            "lat" => if let Some(x) = pf64(v) { c.lat = in_range(x, 90.0, c.lat) },
            "lng" => if let Some(x) = pf64(v) { c.lng = in_range(x, 180.0, c.lng) },
            "acc" => if let Some(x) = pf32(v) { c.acc = in_range(x as f64, 1e5, c.acc as f64) as f32 },
            "random" => c.jitter = v == "1",
            "spd" => if let Some(x) = pf32(v) { c.spd = (in_range(x as f64, 1e4, 0.0) as f32).max(0.0) },
            "brg" => if let Some(b) = pf32(v) {
                c.brg = if b.is_nan() || b.is_infinite() || b < 0.0 { f32::NAN } else { b % 360.0 };
            },
            "alt" => if let Some(x) = pf64(v) { c.alt = in_range(x, 1e5, 0.0) },
            "altv" => if let Some(x) = pf32(v) { c.alt_var = (in_range(x as f64, 1e4, 0.0) as f32).max(0.0) },
            "rr" => if let Some(x) = pf32(v) { c.rr = (in_range(x as f64, 1e4, 8.0) as f32).clamp(0.0, 100.0) },
            "pl" => c.playing = v == "1",
            "tk" => c.tk = v == "1",
            _ => {}
        }
    }
    Some(c)
}

pub fn tk_on(s: Option<&str>) -> bool {
    s.map_or(false, |s| s.split(';').any(|kv| kv.trim() == "tk=1"))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Flags {
    pub system: bool,
    pub heartbeat: bool,
    pub hide_net: bool,
    pub hide_bt: bool,
}

pub fn apply_flags(sv: Option<&str>, net: Option<&str>, bt: Option<&str>) -> Flags {
    Flags {
        system: matches!(sv, Some("1") | Some("2")),
        heartbeat: sv == Some("2"),
        hide_net: net != Some("0"),
        hide_bt: bt == Some("1"),
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FileFields {
    pub cfg: String,
    pub sys: Option<String>,
    pub net: Option<String>,
    pub bt: Option<String>,
}

pub fn read_file_fields(text: &str) -> Option<FileFields> {
    let (mut c, mut sy, mut ne, mut b) = (None, None, None, None);
    for line in text.split('\n') {
        let i = match line.find(':') { Some(i) => i, None => continue };
        let k = line[..i].trim();
        let v = line[i + 1..].trim();
        if v.is_empty() || v == "null" { continue; }
        let v = Some(v.to_string());
        match k {
            "cfg" => c = v,
            "sys" => sy = v,
            "net" => ne = v,
            "bt" => b = v,
            _ => {}
        }
    }
    c.map(|cfg| FileFields { cfg, sys: sy, net: ne, bt: b })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_basic() {
        let c = parse(Some("enabled=1;lat=-8.65;lng=115.2;acc=5;random=1;pl=1;brg=450"), &Cfg::default()).unwrap();
        assert!(c.en && c.jitter && c.playing && !c.tk);
        assert_eq!(c.lat, -8.65);
        assert_eq!(c.acc, 5.0);
        assert_eq!(c.brg, 90.0);
    }

    #[test]
    fn parse_out_of_range_falls_back() {
        let prev = Cfg { lat: 1.0, lng: 2.0, ..Cfg::default() };
        let c = parse(Some("lat=200;lng=999"), &prev).unwrap();
        assert_eq!((c.lat, c.lng), (1.0, 2.0));
    }

    #[test]
    fn parse_null_resets() {
        assert!(parse(None, &Cfg::default()).is_none());
        assert!(parse(Some("null"), &Cfg::default()).is_none());
    }

    #[test]
    fn flags() {
        let f = apply_flags(Some("2"), None, Some("1"));
        assert!(f.system && f.heartbeat && f.hide_net && f.hide_bt);
        let f = apply_flags(Some("0"), Some("0"), None);
        assert!(!f.system && !f.hide_net && !f.hide_bt);
    }

    #[test]
    fn file_fields() {
        let f = read_file_fields("cfg: enabled=1;lat=1\nsys:1\nnet:null\nbt:\n").unwrap();
        assert_eq!(f.cfg, "enabled=1;lat=1");
        assert_eq!(f.sys.as_deref(), Some("1"));
        assert_eq!(f.net, None);
        assert!(read_file_fields("sys:1").is_none());
    }
}
}

pub mod track {

#[derive(Clone, Debug)]
pub struct Track {
    pub t0: i64,
    pub wrote: i64,
    pub tk: i32,
    pub n: usize,
    pub la: Vec<f64>,
    pub lo: Vec<f64>,
    pub al: Vec<f64>,
    pub sp: Vec<f32>,
    pub br: Vec<f32>,
}

pub fn parse_track(s: &str) -> Option<Track> {
    let (mut t0, mut a, mut tk, mut d): (Option<i64>, Option<i64>, i32, Option<&str>) = (None, None, 0, None);
    for kv in s.split(';') {
        let i = match kv.find('=') { Some(i) => i, None => continue };
        let v = &kv[i + 1..];
        match &kv[..i] {
            "t" => t0 = Some(v.parse().ok()?),
            "a" => a = Some(v.parse().ok()?),
            "k" => tk = v.parse().ok()?,
            "d" => d = Some(v),
            _ => {}
        }
    }
    let d = d?;
    let t0 = t0?;
    if tk <= 0 {
        return None;
    }
    let mut tr = Track { t0, wrote: a.unwrap_or(t0), tk, n: 0, la: vec![], lo: vec![], al: vec![], sp: vec![], br: vec![] };
    for row in d.split('|') {
        let r = row.trim_start_matches(',');
        if r.is_empty() {
            continue;
        }
        let f: Vec<&str> = r.split(',').collect();
        if f.len() < 5 {
            return None;
        }
        tr.la.push(f[0].trim().parse().ok()?);
        tr.lo.push(f[1].trim().parse().ok()?);
        tr.sp.push(f[2].trim().parse().ok()?);
        tr.br.push(f[3].trim().parse().ok()?);
        tr.al.push(f[4].trim().parse().ok()?);
        tr.n += 1;
    }
    if tr.n == 0 { None } else { Some(tr) }
}

pub fn parse_track_file(s: &str, len: usize) -> Option<Track> {
    let nl = s.find('\n')?;
    let (mut t0, mut tk, mut n): (Option<i64>, i32, i64) = (None, 0, 0);
    for kv in s[..nl].split(';') {
        let i = match kv.find('=') { Some(i) => i, None => continue };
        let v = kv[i + 1..].trim();
        match kv[..i].trim() {
            "t" => t0 = Some(v.parse().ok()?),
            "k" => tk = v.parse().ok()?,
            "n" => n = v.parse().ok()?,
            _ => {}
        }
    }
    let t0 = t0?;
    if tk <= 0 || n <= 0 || n as usize > len / 10 {
        return None;
    }
    let n = n as usize;
    let mut it = s[nl + 1..].split_whitespace();
    let mut tr = Track { t0, wrote: t0, tk, n, la: Vec::with_capacity(n), lo: Vec::with_capacity(n), al: Vec::with_capacity(n), sp: Vec::with_capacity(n), br: Vec::with_capacity(n) };
    for _ in 0..n {
        tr.la.push(it.next()?.parse().ok()?);
        tr.lo.push(it.next()?.parse().ok()?);
        tr.sp.push(it.next()?.parse().ok()?);
        tr.br.push(it.next()?.parse().ok()?);
        tr.al.push(it.next()?.parse().ok()?);
    }
    Some(tr)
}

pub fn with_file(w: Option<Track>, f: Option<&Track>) -> Option<Track> {
    let w = w?;
    let f = match f { Some(f) if f.tk == w.tk => f, _ => return Some(w) };
    let d = w.t0 - f.t0;
    if d < 0 || d % f.tk as i64 != 0 || d / f.tk as i64 >= f.n as i64 {
        return Some(w);
    }
    let i = (d / f.tk as i64) as usize;
    if (f.la[i] - w.la[0]).abs() > 1e-5 || (f.lo[i] - w.lo[0]).abs() > 1e-5 {
        return Some(w);
    }
    Some(Track { t0: f.t0, wrote: w.wrote, tk: f.tk, n: f.n, la: f.la.clone(), lo: f.lo.clone(), al: f.al.clone(), sp: f.sp.clone(), br: f.br.clone() })
}

pub fn lerp_angle(a: f32, b: f32, f: f64) -> f32 {
    let d = (((b - a) as f64 + 540.0) % 360.0) - 180.0;
    ((a as f64 + d * f + 360.0) % 360.0) as f32
}

pub const TRK_GRACE_MS: i64 = 3_000;
pub const OFF_MS: i64 = 60_000;

pub fn track_stale(now: i64, t: Option<&Track>, miss_at: i64, resume_at: i64) -> i32 {
    let late = match t {
        None => now - miss_at.max(resume_at),
        Some(t) => now - (t.t0 + (t.n as i64 - 1) * t.tk as i64).max(t.wrote).max(resume_at),
    };
    if late >= OFF_MS { 2 } else if late >= TRK_GRACE_MS { 1 } else { 0 }
}

#[derive(Clone, Copy, Debug)]
pub struct Sample {
    pub lat: f64,
    pub lng: f64,
    pub alt: f64,
    pub spd: f32,
    pub brg: f32,
}

pub fn interpolate(t: &Track, now: i64, stale: i32) -> Sample {
    let rel = now - t.t0;
    let at = if rel <= 0 { 0.0 } else { rel as f64 / t.tk as f64 };
    let (i, f) = if at >= (t.n - 1) as f64 { (t.n - 1, 0.0) } else { let i = at as usize; (i, at - i as f64) };
    let j = if f > 0.0 { i + 1 } else { i };
    let lat = t.la[i] + (t.la[j] - t.la[i]) * f;
    let mut dl = t.lo[j] - t.lo[i];
    if dl > 180.0 { dl -= 360.0 } else if dl < -180.0 { dl += 360.0 }
    let mut lng = t.lo[i] + dl * f;
    if lng > 180.0 { lng -= 360.0 } else if lng < -180.0 { lng += 360.0 }
    let alt = t.al[i] + (t.al[j] - t.al[i]) * f;
    let spd = if stale >= 1 { 0.0 } else { ((t.sp[i] as f64 + (t.sp[j] - t.sp[i]) as f64 * f) as f32).max(0.0) };
    Sample { lat, lng, alt, spd, brg: lerp_angle(t.br[i], t.br[j], f) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_window() {
        let t = parse_track("t=1000;a=1500;k=1000;d=1,2,3,90,5|1.1,2.1,3,90,6").unwrap();
        assert_eq!((t.t0, t.wrote, t.tk, t.n), (1000, 1500, 1000, 2));
        assert!(parse_track("t=1;k=0;d=1,2,3,4,5").is_none());
        assert!(parse_track("t=1;k=5;d=1,2,3").is_none());
        assert!(parse_track("t=x;k=5;d=1,2,3,4,5").is_none());
    }

    #[test]
    fn interpolate_midpoint() {
        let t = parse_track("t=0;k=1000;d=0,0,10,0,0|2,2,20,90,10").unwrap();
        let s = interpolate(&t, 500, 0);
        assert!((s.lat - 1.0).abs() < 1e-9 && (s.lng - 1.0).abs() < 1e-9);
        assert!((s.spd - 15.0).abs() < 1e-5 && (s.alt - 5.0).abs() < 1e-9);
        assert!((s.brg - 45.0).abs() < 1e-4);
        assert_eq!(interpolate(&t, 500, 1).spd, 0.0);
        let end = interpolate(&t, 99_999, 0);
        assert!((end.lat - 2.0).abs() < 1e-9);
    }

    #[test]
    fn lerp_wraps_through_north() {
        let b = lerp_angle(350.0, 10.0, 0.5);
        assert!(b < 1e-3 || (b - 360.0).abs() < 1e-3);
    }

    #[test]
    fn stale_levels() {
        let t = parse_track("t=0;k=1000;d=0,0,0,0,0|1,1,0,0,0").unwrap();
        assert_eq!(track_stale(1_500, Some(&t), 0, 0), 0);
        assert_eq!(track_stale(5_000, Some(&t), 0, 0), 1);
        assert_eq!(track_stale(70_000, Some(&t), 0, 0), 2);
    }

    #[test]
    fn file_format() {
        let s = "t=0;k=1000;n=2\n0 0 1 0 0\n1 1 1 0 0\n";
        let t = parse_track_file(s, s.len()).unwrap();
        assert_eq!(t.n, 2);
        assert!(parse_track_file("t=0;k=1000;n=3\n0 0 1 0 0\n", 100).is_none());
    }
}
}

pub mod snapshot {

use crate::prng::Rng;

pub const SNAP_MS: i64 = 50;
pub const FLOOR_RADIUS_M: f64 = 0.8;

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

pub struct Inputs {
    pub lat: f64,
    pub lng: f64,
    pub spd: f32,
    pub brg: f32,
    pub alt: f64,
    pub alt_var: f32,
    pub jitter: bool,
    pub rr: f32,
}

pub fn compute(rng: &Rng, i: &Inputs, now: i64) -> Pt {
    let s_alt = rng.altitude_now(i.alt, i.alt_var, now);
    let r = if i.jitter { i.rr as f64 } else { FLOOR_RADIUS_M };
    let (jn, je) = rng.jitter(now, r);
    let mut c = i.lat.to_radians().cos();
    if c.abs() < 1e-6 {
        c = 1e-6;
    }
    let mut la = i.lat + jn / 111_320.0;
    let mut lo = i.lng + je / (111_320.0 * c);
    la = la.clamp(-90.0, 90.0);
    if lo > 180.0 { lo -= 360.0 } else if lo < -180.0 { lo += 360.0 }
    let acc_jit = (rng.gauss(31, now / SNAP_MS) * 0.35) as f32;
    Pt { lat: la, lng: lo, at: now, spd: i.spd, brg: i.brg, alt: s_alt, acc_jit }
}

pub fn acc_now(acc: f32, p: &Pt) -> f32 { (acc + p.acc_jit).max(1.0) }
pub fn v_acc(acc: f32, p: &Pt) -> f32 { (acc_now(acc, p) * 1.3 + p.acc_jit * 1.5).max(1.0) }
pub fn s_acc(p: &Pt) -> f32 { (0.4 + p.acc_jit.abs() * 0.5).max(0.1) }
pub fn b_acc(p: &Pt) -> f32 { (10.0 + p.acc_jit.abs() * 8.0).max(1.0) }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_stays_near_point() {
        let rng = Rng::new("Vzvwk4Rxj7Ps");
        let i = Inputs { lat: -8.65, lng: 115.2, spd: 0.0, brg: f32::NAN, alt: 10.0, alt_var: 0.0, jitter: true, rr: 8.0 };
        for now in (0..100_000).step_by(313) {
            let p = compute(&rng, &i, now);
            assert!((p.lat - i.lat).abs() < 8.5 / 111_320.0);
            assert_eq!(p.alt, 10.0);
        }
    }
}
}

pub mod fix {

use crate::prng::Rng;
use crate::snapshot::{acc_now, b_acc, s_acc, v_acc, Pt};

#[derive(Clone, Debug, PartialEq)]
pub struct Motion {
    pub altitude: f64,
    pub speed: f32,
    pub vert_acc: f32,
    pub speed_acc: f32,
    pub bearing: Option<(f32, f32)>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Fields {
    pub lat: f64,
    pub lng: f64,
    pub accuracy: f32,
    pub motion: Option<Motion>,
    pub extras: Option<[i32; 3]>,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Provider { Gps, Fused, Network, Other }

impl Provider {
    pub fn from_name(n: &str) -> Provider {
        match n { "gps" => Provider::Gps, "fused" => Provider::Fused, "network" => Provider::Network, _ => Provider::Other }
    }
}

pub fn fields_for(rng: &Rng, provider: Provider, p: &Pt, acc: f32, now_ms: i64) -> Fields {
    if provider == Provider::Network {
        let (dla, dlo) = rng.network_offset(p.lat, now_ms);
        return Fields {
            lat: p.lat + dla,
            lng: p.lng + dlo,
            accuracy: rng.network_accuracy(acc_now(acc, p), now_ms),
            motion: None,
            extras: None,
        };
    }
    let motion = Motion {
        altitude: p.alt,
        speed: if p.spd > 0.3 { (p.spd + p.acc_jit * 0.15).max(0.0) } else { p.spd },
        vert_acc: v_acc(acc, p),
        speed_acc: s_acc(p),
        bearing: if p.brg.is_nan() { None } else { Some((p.brg, b_acc(p))) },
    };
    let extras = if matches!(provider, Provider::Gps | Provider::Fused) { Some(rng.gnss_extras(now_ms)) } else { None };
    Fields { lat: p.lat, lng: p.lng, accuracy: acc_now(acc, p), motion: Some(motion), extras }
}

const MS: i64 = 1_000_000;

pub fn stamp_ns(last: Option<i64>, now_ns: i64, network: bool, rnd: &mut dyn FnMut(i64) -> i64) -> i64 {
    let mut fix = now_ns - (30 + rnd(90)) * MS;
    if let Some(last) = last {
        if !network {
            let jitter = (rnd(101) - 50) * MS;
            let snapped = last + 1_000_000_000 + jitter;
            let age = now_ns - snapped;
            if (snapped - fix).abs() < 100 * MS && age >= 20 * MS && age <= 160 * MS {
                fix = snapped;
            }
        }
        if fix <= last {
            fix = (last + MS).min(now_ns);
        }
    }
    fix.min(now_ns)
}

pub fn last_known_ns(last: Option<i64>, now_ns: i64, phase_ns: i64) -> i64 {
    match last {
        Some(l) if now_ns - l < 5_000_000_000 => l,
        _ => (now_ns - (now_ns - phase_ns).rem_euclid(1_000_000_000)).min(now_ns),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pt() -> Pt { Pt { lat: -8.65, lng: 115.2, at: 0, spd: 0.0, brg: f32::NAN, alt: 3.0, acc_jit: 0.1 } }

    #[test]
    fn network_has_no_motion() {
        let r = Rng::new("Vzvwk4Rxj7Ps");
        let f = fields_for(&r, Provider::Network, &pt(), 10.0, 12_345);
        assert!(f.motion.is_none() && f.extras.is_none());
        assert!(f.accuracy >= 25.0);
    }

    #[test]
    fn gps_has_motion_and_extras() {
        let r = Rng::new("Vzvwk4Rxj7Ps");
        let f = fields_for(&r, Provider::Gps, &pt(), 10.0, 12_345);
        assert!(f.motion.as_ref().unwrap().bearing.is_none());
        assert!(f.extras.is_some());
    }

    #[test]
    fn stamp_monotonic() {
        let mut seq = 7i64;
        let mut rnd = |n: i64| { seq = (seq * 1103515245 + 12345) & 0x7fffffff; seq % n };
        let mut last = None;
        let mut now = 10_000_000_000i64;
        for _ in 0..200 {
            now += 1_000_000_000;
            let f = stamp_ns(last, now, false, &mut rnd);
            assert!(f <= now);
            if let Some(l) = last { assert!(f > l); }
            last = Some(f);
        }
    }

    #[test]
    fn last_known_aligned() {
        assert_eq!(last_known_ns(None, 5_500_000_000, 250_000_000), 5_250_000_000);
        assert_eq!(last_known_ns(Some(4_000_000_000), 5_500_000_000, 0), 4_000_000_000);
    }
}
}

