use configcore::jitter;

use crate::fix::Pt;

pub const SNAP_MS: i64 = 50;
pub const FLOOR_RADIUS_M: f64 = 0.8;

pub struct Inputs {
    pub lat: f64,
    pub lng: f64,
    pub spd: f32,
    pub brg: f32,
    pub alt: f64,
    pub alt_var: f32,
    pub random: bool,
    pub rr: f32,
    pub now_ms: i64,
    pub seed: u64,
}

pub fn renew(i: &Inputs) -> Pt {
    let alt = jitter::altitude_now(i.seed, i.now_ms / 1000, i.alt, i.alt_var as f64);
    let radius = if i.random { i.rr as f64 } else { FLOOR_RADIUS_M };
    let (jit_n, jit_e) = jitter::step_jitter(i.seed, i.now_ms, radius);
    let mut c = i.lat.to_radians().cos();
    if c.abs() < 1e-6 {
        c = 1e-6;
    }
    let mut la = i.lat + jit_n / 111_320.0;
    let mut lo = i.lng + jit_e / (111_320.0 * c);
    la = la.clamp(-90.0, 90.0);
    if lo > 180.0 {
        lo -= 360.0;
    } else if lo < -180.0 {
        lo += 360.0;
    }
    let acc_jit = (jitter::gauss(i.seed, 31, i.now_ms / SNAP_MS) * 0.35) as f32;
    Pt { lat: la, lng: lo, at: i.now_ms, spd: i.spd, brg: i.brg, alt, acc_jit }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn inp() -> Inputs {
        Inputs {
            lat: -7.7,
            lng: 110.4,
            spd: 0.0,
            brg: f32::NAN,
            alt: 100.0,
            alt_var: 0.0,
            random: false,
            rr: 8.0,
            now_ms: 500_000,
            seed: jitter::seed("Vzvwk4Rxj7Ps"),
        }
    }

    #[test]
    fn floor_radius_keeps_under_one_meter() {
        let mut i = inp();
        for t in (0..300_000).step_by(1_013) {
            i.now_ms = 500_000 + t;
            let p = renew(&i);
            let n = (p.lat + 7.7) * 111_320.0;
            let e = (p.lng - 110.4) * 111_320.0 * (-7.7f64).to_radians().cos();
            assert!(n.hypot(e) <= FLOOR_RADIUS_M + 1e-6);
        }
    }

    #[test]
    fn random_radius_is_used_when_enabled() {
        let mut i = inp();
        i.random = true;
        let mut max = 0.0f64;
        for t in (0..600_000).step_by(997) {
            i.now_ms = 500_000 + t;
            let p = renew(&i);
            let n = (p.lat + 7.7) * 111_320.0;
            let e = (p.lng - 110.4) * 111_320.0 * (-7.7f64).to_radians().cos();
            max = max.max(n.hypot(e));
            assert!(n.hypot(e) <= 8.0 + 1e-6);
        }
        assert!(max > FLOOR_RADIUS_M);
    }

    #[test]
    fn clamps_poles_and_wraps_antimeridian() {
        let mut i = inp();
        i.lat = 89.9999999;
        i.random = true;
        i.rr = 1000.0;
        for t in 0..200 {
            i.now_ms = 1_000_000 + t * 777;
            let p = renew(&i);
            assert!((-90.0..=90.0).contains(&p.lat));
        }
        let mut i = inp();
        i.lat = 0.0;
        i.lng = 179.99999;
        i.random = true;
        i.rr = 50.0;
        for t in 0..400 {
            i.now_ms = 2_000_000 + t * 911;
            let p = renew(&i);
            assert!((-180.0..=180.0).contains(&p.lng), "{}", p.lng);
        }
    }

    #[test]
    fn acc_jit_is_bounded() {
        let mut i = inp();
        for t in 0..2_000 {
            i.now_ms = 3_000_000 + t * 51;
            assert!(renew(&i).acc_jit.abs() <= 1.05 + 1e-6);
        }
    }
}
