use crate::num::{plain_decimal, plain_integer};

pub struct Track {
    pub t0: i64,
    pub wrote: i64,
    pub tk: i64,
    pub la: Vec<f64>,
    pub lo: Vec<f64>,
    pub al: Vec<f64>,
    pub sp: Vec<f32>,
    pub br: Vec<f32>,
}

impl Track {
    pub fn n(&self) -> usize {
        self.la.len()
    }

    pub fn end_ms(&self) -> i64 {
        self.end_with(self.wrote)
    }

    pub fn end_with(&self, wrote: i64) -> i64 {
        let last = (self.n() as i64).wrapping_sub(1);
        self.t0.wrapping_add(last.wrapping_mul(self.tk)).max(wrote)
    }
}

pub fn parse_track(s: &str) -> Option<Track> {
    if !s.is_ascii() {
        return None;
    }
    let mut t0: Option<i64> = None;
    let mut wrote: Option<i64> = None;
    let mut tk: i64 = 0;
    let mut d: Option<&str> = None;
    for kv in s.split(';') {
        let (k, v) = match kv.split_once('=') {
            Some(p) => p,
            None => continue,
        };
        match k {
            "t" => {
                if !plain_integer(v) {
                    return None;
                }
                t0 = Some(v.parse().ok()?);
            }
            "a" => {
                if !plain_integer(v) {
                    return None;
                }
                wrote = Some(v.parse().ok()?);
            }
            "k" => {
                if !plain_integer(v) {
                    return None;
                }
                tk = v.parse::<i32>().ok()? as i64;
            }
            "d" => d = Some(v),
            _ => {}
        }
    }
    let t0 = t0?;
    let d = d?;
    if tk <= 0 {
        return None;
    }
    let rows: Vec<&str> = d.split('|').collect();
    let mut la = Vec::with_capacity(rows.len());
    let mut lo = Vec::with_capacity(rows.len());
    let mut al = Vec::with_capacity(rows.len());
    let mut sp = Vec::with_capacity(rows.len());
    let mut br = Vec::with_capacity(rows.len());
    for row in rows {
        let row = row.trim_start_matches(',');
        if row.is_empty() {
            continue;
        }
        let mut f = row.split(',');
        let mut field = || -> Option<&str> {
            let x = f.next()?;
            if plain_decimal(x) {
                Some(x)
            } else {
                None
            }
        };
        la.push(field()?.parse().ok()?);
        lo.push(field()?.parse().ok()?);
        sp.push(field()?.parse().ok()?);
        br.push(field()?.parse().ok()?);
        al.push(field()?.parse().ok()?);
    }
    if la.is_empty() {
        return None;
    }
    Some(Track { t0, wrote: wrote.unwrap_or(t0), tk, la, lo, al, sp, br })
}

pub fn lerp_angle(a: f32, b: f32, f: f64) -> f32 {
    let d = (((b - a) as f64 + 540.0) % 360.0) - 180.0;
    ((a as f64 + d * f + 360.0) % 360.0) as f32
}

pub struct Applied {
    pub lat: f64,
    pub lng: f64,
    pub alt: f64,
    pub spd: f32,
    pub brg: f32,
}

pub fn apply_track(t: &Track, now_ms: i64, stale: i32) -> Applied {
    let n = t.n();
    let rel = now_ms.wrapping_sub(t.t0);
    let at = if rel <= 0 { 0.0 } else { rel as f64 / t.tk as f64 };
    let (i, f) = if at >= (n - 1) as f64 {
        (n - 1, 0.0)
    } else {
        let i = at as usize;
        (i, at - i as f64)
    };
    let j = if f > 0.0 { i + 1 } else { i };
    let tla = t.la[i] + (t.la[j] - t.la[i]) * f;
    let mut dl = t.lo[j] - t.lo[i];
    if dl > 180.0 {
        dl -= 360.0;
    } else if dl < -180.0 {
        dl += 360.0;
    }
    let mut lo = t.lo[i] + dl * f;
    if lo > 180.0 {
        lo -= 360.0;
    } else if lo < -180.0 {
        lo += 360.0;
    }
    let alt = t.al[i] + (t.al[j] - t.al[i]) * f;
    let spd = if stale >= 1 {
        0.0
    } else {
        crate::num::max0((t.sp[i] as f64 + (t.sp[j] - t.sp[i]) as f64 * f) as f32)
    };
    let brg = lerp_angle(t.br[i], t.br[j], f);
    Applied { lat: tla, lng: lo, alt, spd, brg }
}

const TRK_GRACE_MS: i64 = 3_000;
const OFF_MS: i64 = 60_000;

pub fn stale_level(late_ms: i64) -> i32 {
    if late_ms >= OFF_MS {
        2
    } else if late_ms >= TRK_GRACE_MS {
        1
    } else {
        0
    }
}

const GAP_MS: i64 = 3_000;

pub struct Stale {
    last_elapsed: i64,
    last_uptime: i64,
    resume_at: i64,
    miss_at: i64,
}

impl Stale {
    pub const fn new() -> Self {
        Stale { last_elapsed: 0, last_uptime: 0, resume_at: 0, miss_at: 0 }
    }

    pub fn level(&mut self, now: i64, uptime: i64, end: Option<i64>) -> i32 {
        if self.last_elapsed != 0 && (now - self.last_elapsed) - (uptime - self.last_uptime) > GAP_MS {
            self.resume_at = now;
        }
        self.last_elapsed = now;
        self.last_uptime = uptime;
        let late = match end {
            None => {
                if self.miss_at == 0 {
                    self.miss_at = now;
                }
                now - self.miss_at.max(self.resume_at)
            }
            Some(e) => {
                self.miss_at = 0;
                now - e.max(self.resume_at)
            }
        };
        stale_level(late)
    }

    pub fn reset(&mut self, all: bool) {
        if all {
            *self = Stale::new();
        } else {
            self.miss_at = 0;
        }
    }
}

pub fn tk_on(s: &str) -> bool {
    s.split(';').any(|kv| crate::num::jtrim(kv) == "tk=1")
}

pub const FLAG_SYSTEM: i32 = 1;
pub const FLAG_HEARTBEAT: i32 = 2;
pub const FLAG_HIDE_NET: i32 = 4;
pub const FLAG_HIDE_BT: i32 = 8;

pub fn flags(sys: Option<&str>, net: Option<&str>, bt: Option<&str>) -> i32 {
    let mut f = 0;
    if matches!(sys, Some("1") | Some("2")) {
        f |= FLAG_SYSTEM;
    }
    if sys == Some("2") {
        f |= FLAG_HEARTBEAT;
    }
    if net != Some("0") {
        f |= FLAG_HIDE_NET;
    }
    if bt == Some("1") {
        f |= FLAG_HIDE_BT;
    }
    f
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stale_without_track_counts_from_first_miss() {
        let mut s = Stale::new();
        assert_eq!(s.level(100_000, 100_000, None), 0);
        assert_eq!(s.level(102_999, 102_999, None), 0);
        assert_eq!(s.level(103_000, 103_000, None), 1);
        assert_eq!(s.level(159_999, 159_999, None), 1);
        assert_eq!(s.level(160_000, 160_000, None), 2);
        s.reset(false);
        assert_eq!(s.level(161_000, 161_000, None), 0);
    }

    #[test]
    fn stale_with_track_uses_end_and_clears_miss() {
        let mut s = Stale::new();
        assert_eq!(s.level(10_000, 10_000, None), 0);
        assert_eq!(s.level(20_000, 20_000, Some(19_000)), 0);
        assert_eq!(s.level(30_000, 30_000, Some(19_000)), 1);
        assert_eq!(s.level(79_000, 79_000, Some(19_000)), 2);
        assert_eq!(s.level(80_000, 80_000, None), 0);
    }

    #[test]
    fn stale_resume_after_suspend_shifts_baseline() {
        let mut s = Stale::new();
        assert_eq!(s.level(1_000, 1_000, Some(0)), 0);
        assert_eq!(s.level(70_000, 5_000, Some(0)), 0);
        assert_eq!(s.level(72_000, 7_000, Some(0)), 0);
        assert_eq!(s.level(75_000, 10_000, Some(0)), 1);
        s.reset(true);
        assert_eq!(s.level(200_000, 200_000, Some(0)), 2);
    }

    #[test]
    fn tk_on_matches_trimmed_pair() {
        assert!(tk_on("enabled=1;tk=1;pl=1"));
        assert!(tk_on(" tk=1 "));
        assert!(tk_on("tk=1"));
        assert!(!tk_on("tk=0;xtk=1;tk=10"));
        assert!(!tk_on(""));
    }

    #[test]
    fn flags_map() {
        assert_eq!(flags(None, None, None), FLAG_HIDE_NET);
        assert_eq!(flags(Some("1"), Some("0"), Some("1")), FLAG_SYSTEM | FLAG_HIDE_BT);
        assert_eq!(flags(Some("2"), Some("1"), None), FLAG_SYSTEM | FLAG_HEARTBEAT | FLAG_HIDE_NET);
        assert_eq!(flags(Some("0"), Some(""), Some("0")), FLAG_HIDE_NET);
    }

    fn sample() -> Track {
        parse_track("t=1000;k=1000;d=1,2,3,4,5|6,7,8,9,10|11,12,13,14,15").unwrap()
    }

    #[test]
    fn parses_three_points() {
        let t = sample();
        assert_eq!(t.n(), 3);
        assert_eq!(t.la, vec![1.0, 6.0, 11.0]);
    }

    #[test]
    fn rejects_short_row() {
        assert!(parse_track("t=1000;k=1000;d=1,2,3,4").is_none());
    }

    #[test]
    fn interpolates_halfway() {
        let t = sample();
        let a = apply_track(&t, 1500, 0);
        assert!((a.lat - 3.5).abs() < 1e-9);
    }

    #[test]
    fn clamps_at_end() {
        let t = sample();
        let a = apply_track(&t, 10_000, 0);
        assert_eq!(a.lat, 11.0);
    }

    #[test]
    fn stale_speed_zeroed() {
        let t = sample();
        let a = apply_track(&t, 1500, 1);
        assert_eq!(a.spd, 0.0);
    }

    #[test]
    fn lerp_angle_wraps_shortest_path() {
        assert!((lerp_angle(350.0, 10.0, 0.5) - 0.0).abs() < 1e-3);
    }

    #[test]
    fn wrote_defaults_to_t0_and_reads_a() {
        assert_eq!(sample().wrote, 1000);
        let t = parse_track("t=1000;a=900;k=1000;d=1,2,3,4,5").unwrap();
        assert_eq!(t.wrote, 900);
    }

    #[test]
    fn pairs_without_equals_are_skipped_like_java() {
        assert!(parse_track("junk;t=1000;k=1000;d=1,2,3,4,5").is_some());
    }

    #[test]
    fn first_bad_number_wins_even_if_repeated() {
        assert!(parse_track("t=abc;t=5;k=1000;d=1,2,3,4,5").is_none());
        assert!(parse_track("t=5;k=3000000000;d=1,2,3,4,5").is_none());
        assert!(parse_track("t=5;k=0;d=1,2,3,4,5").is_none());
    }

    #[test]
    fn non_plain_numbers_and_non_ascii_are_handed_back() {
        assert!(parse_track("t=5;k=1000;d=1,2,3,4,5d").is_none());
        assert!(parse_track("t=5;k=1000;d= 1,2,3,4,5").is_none());
        assert!(parse_track("t=5;k=1000;d=1,2,3,4,\u{661}").is_none());
    }

    #[test]
    fn extra_fields_and_trailing_comma_are_fine() {
        let t = parse_track("t=5;k=1000;d=,1,2,3,4,5,9,9|,6,7,8,9,10,|").unwrap();
        assert_eq!(t.n(), 2);
        assert_eq!(t.al, vec![5.0, 10.0]);
    }

    #[test]
    fn speed_interpolation_uses_float_difference_like_java() {
        let t = parse_track("t=0;k=1000;d=0,0,0.1,0,0|0,0,0.7,0,0").unwrap();
        let a = apply_track(&t, 333, 0);
        let want = (0.1f32 as f64 + (0.7f32 - 0.1f32) as f64 * 0.333) as f32;
        assert_eq!(a.spd, want);
    }

    #[test]
    fn stale_levels() {
        assert_eq!(stale_level(0), 0);
        assert_eq!(stale_level(3000), 1);
        assert_eq!(stale_level(60_000), 2);
    }
}
