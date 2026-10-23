use configcore::jitter;
use std::collections::HashMap;

use crate::providers::{FUSED, GPS, NETWORK};

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Pt {
    pub lat: f64,
    pub lng: f64,
    pub at: i64,
    pub spd: f32,
    pub brg: f32,
    pub alt: f64,
    pub acc_jit: f32,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Motion {
    pub alt: f64,
    pub spd: f32,
    pub v_acc: f32,
    pub s_acc: f32,
    pub bearing: Option<(f32, f32)>,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Applied {
    pub lat: f64,
    pub lng: f64,
    pub acc: f32,
    pub motion: Option<Motion>,
    pub extras: Option<(i32, i32, i32)>,
}

pub fn acc_now(acc: f32, pt: &Pt) -> f32 {
    jitter::acc_now(acc, pt.acc_jit)
}

pub fn v_acc(acc: f32, pt: &Pt) -> f32 {
    jitter::v_acc(acc, pt.acc_jit)
}

pub fn s_acc(pt: &Pt) -> f32 {
    jitter::s_acc(pt.acc_jit)
}

pub fn b_acc(pt: &Pt) -> f32 {
    jitter::b_acc(pt.acc_jit)
}

pub fn motion(acc: f32, pt: &Pt) -> Motion {
    let spd = if pt.spd > 0.3 { (pt.spd + pt.acc_jit * 0.15).max(0.0) } else { pt.spd };
    let bearing = if pt.brg.is_nan() { None } else { Some((pt.brg, b_acc(pt))) };
    Motion { alt: pt.alt, spd, v_acc: v_acc(acc, pt), s_acc: s_acc(pt), bearing }
}

pub fn apply(provider: &str, pt: &Pt, acc: f32, seed: u64, now_ms: i64) -> Applied {
    if provider == NETWORK {
        let (d_lat, d_lng) = jitter::network_offset(seed, now_ms, pt.lat);
        let net_acc = jitter::network_accuracy(seed, now_ms, acc_now(acc, pt) as f64) as f32;
        return Applied { lat: pt.lat + d_lat, lng: pt.lng + d_lng, acc: net_acc.max(25.0), motion: None, extras: None };
    }
    let extras = if provider == GPS || provider == FUSED { Some(jitter::gnss_extras(seed, now_ms / 1000)) } else { None };
    Applied { lat: pt.lat, lng: pt.lng, acc: acc_now(acc, pt), motion: Some(motion(acc, pt)), extras }
}

pub struct Stamper {
    last: HashMap<String, i64>,
}

const MS: i64 = 1_000_000;
const SEC: i64 = 1_000_000_000;

impl Stamper {
    pub fn new() -> Self {
        Stamper { last: HashMap::new() }
    }

    pub fn stamp(&mut self, provider: &str, now_ns: i64, wall_ms: i64, rnd: &mut dyn FnMut(i32) -> i32) -> (i64, i64) {
        let mut fix_ns = now_ns - (30 + rnd(90) as i64) * MS;
        if let Some(last) = self.last.get(provider).copied() {
            if provider != NETWORK {
                let jit = (rnd(101) as i64 - 50) * MS;
                let snapped = last + SEC + jit;
                let age = now_ns - snapped;
                if (snapped - fix_ns).abs() < 100 * MS && (20 * MS..=160 * MS).contains(&age) {
                    fix_ns = snapped;
                }
            }
            if fix_ns <= last {
                fix_ns = (last + MS).min(now_ns);
            }
        }
        if fix_ns > now_ns {
            fix_ns = now_ns;
        }
        self.last.insert(provider.to_string(), fix_ns);
        let age_ms = (now_ns - fix_ns) / MS;
        (fix_ns, wall_ms - age_ms)
    }

    pub fn last_known(&self, provider: &str, now_ns: i64, wall_ms: i64, phase_ns: i64) -> (i64, i64) {
        let fix_ns = match self.last.get(provider) {
            Some(&last) if now_ns - last < 5 * SEC => last,
            _ => (now_ns - (now_ns - phase_ns).rem_euclid(SEC)).min(now_ns),
        };
        (fix_ns, wall_ms - (now_ns - fix_ns) / MS)
    }
}

impl Default for Stamper {
    fn default() -> Self {
        Self::new()
    }
}

pub fn phase_ns(seed: u64, boot_ns: i64) -> i64 {
    (jitter::hash_unit(seed, 901, boot_ns / SEC) * 1e9) as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    const SEED_SALT: &str = "Vzvwk4Rxj7Ps";

    fn pt() -> Pt {
        Pt { lat: -7.7, lng: 110.4, at: 0, spd: 0.0, brg: f32::NAN, alt: 100.0, acc_jit: 0.0 }
    }

    fn seq(vals: Vec<i32>) -> impl FnMut(i32) -> i32 {
        let mut i = 0;
        move |bound| {
            let v = vals[i % vals.len()] % bound;
            i += 1;
            v
        }
    }

    #[test]
    fn gps_apply_sets_motion_and_extras() {
        let seed = jitter::seed(SEED_SALT);
        let a = apply("gps", &pt(), 10.0, seed, 123_456);
        assert_eq!((a.lat, a.lng, a.acc), (-7.7, 110.4, 10.0));
        let m = a.motion.unwrap();
        assert_eq!(m.alt, 100.0);
        assert_eq!(m.spd, 0.0);
        assert_eq!(m.bearing, None);
        assert!(a.extras.is_some());
    }

    #[test]
    fn fused_has_extras_passive_does_not() {
        let seed = jitter::seed(SEED_SALT);
        assert!(apply("fused", &pt(), 10.0, seed, 5_000).extras.is_some());
        let p = apply("passive", &pt(), 10.0, seed, 5_000);
        assert!(p.extras.is_none());
        assert!(p.motion.is_some());
    }

    #[test]
    fn network_apply_offsets_and_clears_motion() {
        let seed = jitter::seed(SEED_SALT);
        let a = apply("network", &pt(), 10.0, seed, 77_000);
        assert!(a.motion.is_none() && a.extras.is_none());
        let off = ((a.lat + 7.7) * 111_320.0).hypot((a.lng - 110.4) * 111_320.0 * (-7.7f64).to_radians().cos());
        assert!((35.0..=125.0).contains(&off), "{off}");
        assert!(a.acc >= 25.0);
    }

    #[test]
    fn motion_speed_and_bearing() {
        let mut p = pt();
        p.spd = 5.0;
        p.acc_jit = 0.2;
        p.brg = 90.0;
        let m = motion(10.0, &p);
        assert!((m.spd - 5.03).abs() < 1e-5);
        assert_eq!(m.bearing, Some((90.0, 10.0 + 0.2 * 8.0)));
        p.spd = 0.3;
        assert_eq!(motion(10.0, &p).spd, 0.3);
        p.spd = 1.0;
        p.acc_jit = -10.0;
        assert_eq!(motion(10.0, &p).spd, 0.0);
    }

    #[test]
    fn stamp_first_call_uses_random_age() {
        let mut s = Stamper::new();
        let mut r = seq(vec![40]);
        let (ns, ms) = s.stamp("gps", 10 * SEC, 1_000_000, &mut r);
        assert_eq!(10 * SEC - ns, 70 * MS);
        assert_eq!(ms, 1_000_000 - 70);
    }

    #[test]
    fn stamp_snaps_to_one_second_grid_and_stays_monotonic() {
        let mut s = Stamper::new();
        let mut r = seq(vec![40, 50]);
        let now0 = 10 * SEC;
        let (n0, _) = s.stamp("gps", now0, 0, &mut r);
        let now1 = now0 + SEC;
        let mut r1 = seq(vec![40, 50]);
        let (n1, _) = s.stamp("gps", now1, 0, &mut r1);
        assert_eq!(n1, n0 + SEC);
        let mut r2 = seq(vec![0]);
        let (n2, _) = s.stamp("gps", now1, 0, &mut r2);
        assert!(n2 > n1);
        assert!(n2 <= now1);
    }

    #[test]
    fn stamp_network_skips_grid_snap() {
        let mut s = Stamper::new();
        let mut r = seq(vec![40]);
        let (n0, _) = s.stamp("network", 10 * SEC, 0, &mut r);
        let (n1, _) = s.stamp("network", 11 * SEC, 0, &mut r);
        assert_eq!(n0, 10 * SEC - 70 * MS);
        assert_eq!(n1, 11 * SEC - 70 * MS);
    }

    #[test]
    fn stamp_never_exceeds_now() {
        let mut s = Stamper::new();
        let mut r = seq(vec![0]);
        let (n0, _) = s.stamp("gps", 5 * SEC, 0, &mut r);
        let (n1, _) = s.stamp("gps", n0 + 500, 0, &mut r);
        assert_eq!(n1, n0 + 500);
    }

    #[test]
    fn last_known_reuses_recent_stamp_else_phase_grid() {
        let mut s = Stamper::new();
        let mut r = seq(vec![40]);
        let (n0, _) = s.stamp("gps", 10 * SEC, 0, &mut r);
        let (ns, _) = s.last_known("gps", 12 * SEC, 0, 123);
        assert_eq!(ns, n0);
        let (ns, ms) = s.last_known("gps", 20 * SEC + 400 * MS, 50_000, 100 * MS);
        assert_eq!(ns, 20 * SEC + 100 * MS);
        assert_eq!(ms, 50_000 - 300);
        let (ns, _) = Stamper::new().last_known("gps", 20 * SEC, 0, 700 * MS);
        assert_eq!(ns, 19 * SEC + 700 * MS);
        assert!(ns <= 20 * SEC);
    }

    #[test]
    fn phase_is_within_one_second() {
        let seed = jitter::seed(SEED_SALT);
        let p = phase_ns(seed, 123 * SEC + 5);
        assert!((0..SEC).contains(&p));
    }
}
