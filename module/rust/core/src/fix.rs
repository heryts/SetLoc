use crate::rng::{hash_unit, XorShift};
use crate::state::{gnss_extras, network_accuracy, network_offset, Pt, State};
use std::collections::HashMap;

pub const GPS: &str = "gps";
pub const NETWORK: &str = "network";
pub const FUSED: &str = "fused";

#[derive(Clone, Copy, Debug)]
pub struct Motion {
    pub altitude: f64,
    pub speed: f32,
    pub v_acc: f32,
    pub s_acc: f32,
    pub bearing: Option<(f32, f32)>,
}

#[derive(Clone, Copy, Debug)]
pub struct Applied {
    pub lat: f64,
    pub lng: f64,
    pub accuracy: f32,
    pub motion: Option<Motion>,
    pub extras: Option<[i32; 3]>,
}

pub fn apply(st: &State, provider: &str, p: &Pt, elapsed_ms: i64) -> Applied {
    if provider == NETWORK {
        let off = network_offset(elapsed_ms, p.lat);
        return Applied {
            lat: p.lat + off[0],
            lng: p.lng + off[1],
            accuracy: network_accuracy(elapsed_ms, st.acc_now(Some(p))),
            motion: None,
            extras: None,
        };
    }
    let speed = if p.spd > 0.3 { (p.spd + p.acc_jit * 0.15).max(0.0) } else { p.spd };
    let bearing = if p.brg.is_nan() { None } else { Some((p.brg, State::b_acc(p))) };
    Applied {
        lat: p.lat,
        lng: p.lng,
        accuracy: st.acc_now(Some(p)),
        motion: Some(Motion { altitude: p.alt, speed, v_acc: st.v_acc(p), s_acc: State::s_acc(p), bearing }),
        extras: if provider == GPS || provider == FUSED { Some(gnss_extras(elapsed_ms)) } else { None },
    }
}

pub struct Stamper {
    last_ns: HashMap<String, i64>,
    phase_ns: i64,
    rnd: XorShift,
}

impl Stamper {
    pub fn new(elapsed_ns: i64) -> Self {
        Stamper {
            last_ns: HashMap::new(),
            phase_ns: (hash_unit(901, elapsed_ns / 1_000_000_000) * 1e9) as i64,
            rnd: XorShift::new(elapsed_ns as u64 ^ 0xA5A5_5A5A_1234_8765),
        }
    }

    pub fn stamp(&mut self, provider: &str, now_ns: i64, wall_ms: i64) -> (i64, i64) {
        let mut fix_ns = now_ns - (30 + self.rnd.below(90) as i64) * 1_000_000;
        if let Some(&last) = self.last_ns.get(provider) {
            if provider != NETWORK {
                let jitter = (self.rnd.below(101) as i64 - 50) * 1_000_000;
                let snapped = last + 1_000_000_000 + jitter;
                let age = now_ns - snapped;
                if (snapped - fix_ns).abs() < 100_000_000 && (20_000_000..=160_000_000).contains(&age) {
                    fix_ns = snapped;
                }
            }
            if fix_ns <= last {
                fix_ns = (last + 1_000_000).min(now_ns);
            }
        }
        if fix_ns > now_ns {
            fix_ns = now_ns;
        }
        self.last_ns.insert(provider.to_string(), fix_ns);
        (fix_ns, wall_ms - (now_ns - fix_ns) / 1_000_000)
    }

    pub fn last_known(&self, provider: &str, now_ns: i64, wall_ms: i64) -> (i64, i64) {
        let fix_ns = match self.last_ns.get(provider) {
            Some(&last) if now_ns - last < 5_000_000_000 => last,
            _ => (now_ns - (now_ns - self.phase_ns).rem_euclid(1_000_000_000)).min(now_ns),
        };
        (fix_ns, wall_ms - (now_ns - fix_ns) / 1_000_000)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::{Clock, ManualClock};

    fn live_state() -> State {
        let mut s = State::new();
        s.parse(Some("enabled=1;lat=-7.78;lng=110.36;acc=10;spd=4;brg=90;alt=120"));
        s
    }

    #[test]
    fn gps_has_motion_and_extras() {
        let clk = ManualClock::new(50_000);
        let mut s = live_state();
        let p = s.snapshot(&clk);
        let a = apply(&s, GPS, &p, clk.elapsed_ms());
        let m = a.motion.unwrap();
        assert!(m.bearing.is_some());
        assert!(m.speed > 3.0 && m.speed < 5.0);
        assert!(a.extras.is_some());
        assert!(a.accuracy >= 1.0);
    }

    #[test]
    fn network_has_no_motion_and_offset() {
        let clk = ManualClock::new(50_000);
        let mut s = live_state();
        let p = s.snapshot(&clk);
        let a = apply(&s, NETWORK, &p, clk.elapsed_ms());
        assert!(a.motion.is_none() && a.extras.is_none());
        assert!(a.accuracy >= 25.0);
        assert!((a.lat - p.lat).abs() > 0.0);
    }

    #[test]
    fn nan_bearing_drops_bearing() {
        let clk = ManualClock::new(50_000);
        let mut s = State::new();
        s.parse(Some("enabled=1;lat=1;lng=1;brg=-5"));
        let p = s.snapshot(&clk);
        assert!(apply(&s, GPS, &p, 1).motion.unwrap().bearing.is_none());
    }

    #[test]
    fn stamps_are_monotonic_and_recent() {
        let clk = ManualClock::new(1_000_000);
        let mut st = Stamper::new(clk.elapsed_ns());
        let mut prev = 0;
        for _ in 0..200 {
            clk.advance(1000);
            let (ns, ms) = st.stamp(GPS, clk.elapsed_ns(), clk.wall_ms());
            assert!(ns > prev);
            assert!(ns <= clk.elapsed_ns());
            let age = (clk.elapsed_ns() - ns) / 1_000_000;
            assert!(age < 200, "age {age}");
            assert!(ms <= clk.wall_ms());
            prev = ns;
        }
    }

    #[test]
    fn last_known_snaps_to_second_grid() {
        let clk = ManualClock::new(3_000_000);
        let st = Stamper::new(clk.elapsed_ns());
        let (ns, _) = st.last_known(GPS, clk.elapsed_ns(), clk.wall_ms());
        assert!(clk.elapsed_ns() - ns < 1_000_000_000);
        assert_eq!((ns - st.phase_ns).rem_euclid(1_000_000_000), 0);
    }
}
