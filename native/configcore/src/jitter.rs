use std::f64::consts::PI;

const JIT_PERIOD_S: [f64; 4] = [7.0, 13.0, 23.0, 41.0];

fn fnv64(s: &str) -> u64 {
    let mut h: u64 = 0xCBF2_9CE4_8422_2325;
    for b in s.bytes() {
        h = (h ^ b as u64).wrapping_mul(0x0000_0100_0000_01B3);
    }
    h
}

fn mix(mut z: u64) -> u64 {
    z = z.wrapping_add(0x9E37_79B9_7F4A_7C15);
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

pub fn seed(salt: &str) -> u64 {
    fnv64(salt)
}

pub fn hash_unit(seed: u64, a: i64, b: i64) -> f64 {
    let h = mix(seed ^ mix(a as u64) ^ (mix(b as u64) << 1));
    (h >> 11) as f64 * (1.0 / (1u64 << 53) as f64)
}

pub fn gauss(seed: u64, salt: i64, bucket: i64) -> f64 {
    (hash_unit(seed, salt, bucket) + hash_unit(seed, salt + 1000, bucket) + hash_unit(seed, salt + 2000, bucket) - 1.5) * 2.0
}

fn wave(seed: u64, axis: i64, t_sec: f64, sigma: f64) -> f64 {
    let mut sum = 0.0;
    for (i, period) in JIT_PERIOD_S.iter().enumerate() {
        let phase = hash_unit(seed, 100 + axis, i as i64) * 2.0 * PI;
        sum += (2.0 * PI * t_sec / period + phase).sin();
    }
    sum * sigma / (JIT_PERIOD_S.len() as f64 / 2.0).sqrt()
}

pub fn step_jitter(seed: u64, now_ms: i64, r: f64) -> (f64, f64) {
    if r <= 0.0 {
        return (0.0, 0.0);
    }
    let sigma = r / 3.0;
    let t = now_ms as f64 / 1000.0;
    let mut jit_n = wave(seed, 1, t, sigma);
    let mut jit_e = wave(seed, 2, t, sigma);
    let d = jit_n.hypot(jit_e);
    if d > r {
        jit_n *= r / d;
        jit_e *= r / d;
    }
    (jit_n, jit_e)
}

pub fn network_offset(seed: u64, now_ms: i64, ref_lat: f64) -> (f64, f64) {
    const GRID_MS: i64 = 30_000;
    let boundary = |k: i64| -> i64 { k * GRID_MS + ((hash_unit(seed, 20, k) - 0.5) * 10_000.0) as i64 };
    let mut k = now_ms / GRID_MS;
    if boundary(k + 1) <= now_ms {
        k += 1;
    } else if boundary(k) > now_ms {
        k -= 1;
    }
    let ang = hash_unit(seed, 21, k) * 2.0 * PI;
    let dist = 35.0 + hash_unit(seed, 22, k) * 90.0;
    let mut c = ref_lat.to_radians().cos();
    if c.abs() < 1e-6 {
        c = 1e-6;
    }
    (ang.sin() * dist / 111320.0, ang.cos() * dist / (111320.0 * c))
}

pub fn network_accuracy(seed: u64, now_ms: i64, gps_acc: f64) -> f64 {
    (gps_acc * 2.5 + 20.0 + gauss(seed, 51, now_ms / 1000) * 6.0).max(25.0)
}

pub fn altitude_now(seed: u64, now_s: i64, alt: f64, alt_var: f64) -> f64 {
    if alt_var <= 0.0 {
        return alt;
    }
    let mut n = 0.0;
    let mut w = 4.0;
    let mut tot = 0.0;
    for i in 0..3 {
        n += w * (hash_unit(seed, 41, now_s - i) * 2.0 - 1.0);
        tot += w;
        w /= 2.0;
    }
    alt + alt_var * n / tot
}

pub fn java_round(x: f64) -> f64 {
    (x + 0.5).floor()
}

pub fn gnss_extras(seed: u64, t_sec: i64) -> (i32, i32, i32) {
    let p1 = hash_unit(seed, 11, 0) * 2.0 * PI;
    let p2 = hash_unit(seed, 12, 0) * 2.0 * PI;
    let p3 = hash_unit(seed, 13, 0) * 2.0 * PI;
    let p4 = hash_unit(seed, 14, 0) * 2.0 * PI;
    let t = t_sec as f64;
    let sat = 9.0 + java_round(2.2 * (t / 53.0 + p1).sin() + 1.6 * (t / 17.0 + p2).sin());
    let mean = 29.0 + java_round(3.0 * (t / 71.0 + p3).sin() + 1.5 * (t / 23.0 + p4).sin());
    let sat = sat.clamp(5.0, 14.0) as i32;
    let mean = mean.clamp(22.0, 36.0) as i32;
    let third = mean + 9 + (hash_unit(seed, 15, t_sec) * 3.0) as i32;
    (sat, mean, third)
}

pub fn acc_now(acc: f32, acc_jit: f32) -> f32 {
    (acc + acc_jit).max(1.0)
}

pub fn v_acc(acc: f32, acc_jit: f32) -> f32 {
    (acc_now(acc, acc_jit) * 1.3 + acc_jit * 1.5).max(1.0)
}

pub fn s_acc(acc_jit: f32) -> f32 {
    (0.4 + acc_jit.abs() * 0.5).max(0.1)
}

pub fn b_acc(acc_jit: f32) -> f32 {
    (10.0 + acc_jit.abs() * 8.0).max(1.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_unit_in_unit_range() {
        let s = seed("Vzvwk4Rxj7Ps");
        for b in 0..50 {
            let v = hash_unit(s, 1, b);
            assert!((0.0..1.0).contains(&v), "{v}");
        }
    }

    #[test]
    fn step_jitter_zero_radius_is_zero() {
        let s = seed("Vzvwk4Rxj7Ps");
        assert_eq!(step_jitter(s, 1000, 0.0), (0.0, 0.0));
    }

    #[test]
    fn step_jitter_stays_within_radius() {
        let s = seed("Vzvwk4Rxj7Ps");
        for t in (0..200_000).step_by(997) {
            let (n, e) = step_jitter(s, t, 8.0);
            assert!(n.hypot(e) <= 8.0 + 1e-6);
        }
    }

    #[test]
    fn acc_helpers_floor() {
        assert_eq!(acc_now(0.0, 0.0), 1.0);
        assert_eq!(v_acc(0.0, 0.0), 1.3);
        assert_eq!(s_acc(0.0), 0.4);
        assert_eq!(b_acc(0.0), 10.0);
    }

    #[test]
    fn java_round_matches_math_round() {
        assert_eq!(java_round(0.5), 1.0);
        assert_eq!(java_round(-0.5), 0.0);
        assert_eq!(java_round(-1.5), -1.0);
        assert_eq!(java_round(2.4), 2.0);
        assert_eq!(java_round(-2.6), -3.0);
    }

    #[test]
    fn gnss_extras_within_bounds() {
        let s = seed("Vzvwk4Rxj7Ps");
        for t in (0..100_000).step_by(3331) {
            let (sat, mean, third) = gnss_extras(s, t);
            assert!((5..=14).contains(&sat));
            assert!((22..=36).contains(&mean));
            assert!(third >= mean);
        }
    }
}
