use crate::keys::SALT;

const MASK48: u64 = (1u64 << 48) - 1;

pub struct JavaRandom {
    seed: u64,
}

impl JavaRandom {
    pub fn new(seed: i64) -> Self {
        JavaRandom { seed: (seed as u64 ^ 0x5DEE_CE66D) & MASK48 }
    }

    fn next(&mut self, bits: u32) -> i32 {
        self.seed = self.seed.wrapping_mul(0x5DEE_CE66D).wrapping_add(0xB) & MASK48;
        (self.seed >> (48 - bits)) as i32
    }

    pub fn next_int(&mut self, bound: i32) -> i32 {
        if bound & (bound - 1) == 0 {
            return ((bound as i64 * self.next(31) as i64) >> 31) as i32;
        }
        loop {
            let bits = self.next(31);
            let val = bits % bound;
            if bits.wrapping_sub(val).wrapping_add(bound - 1) >= 0 {
                return val;
            }
        }
    }

    pub fn next_i32(&mut self) -> i32 {
        self.next(32)
    }

    pub fn next_bool(&mut self) -> bool {
        self.next(1) != 0
    }

    pub fn next_double(&mut self) -> f64 {
        let hi = (self.next(26) as i64) << 27;
        let lo = self.next(27) as i64;
        (hi + lo) as f64 * (1.0 / (1u64 << 53) as f64)
    }

    pub fn shuffle<T>(&mut self, v: &mut [T]) {
        let mut i = v.len();
        while i > 1 {
            let j = self.next_int(i as i32) as usize;
            v.swap(i - 1, j);
            i -= 1;
        }
    }
}

pub struct XorShift(u64);

impl XorShift {
    pub fn new(seed: u64) -> Self {
        XorShift(if seed == 0 { 0x9E37_79B9_7F4A_7C15 } else { seed })
    }

    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }

    pub fn below(&mut self, bound: u32) -> u32 {
        (self.next_u64() >> 33) as u32 % bound
    }
}

fn mix(z: u64) -> u64 {
    let z = z.wrapping_add(0x9E37_79B9_7F4A_7C15);
    let z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    let z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

pub fn seed() -> i64 {
    let mut h: u64 = 0xCBF2_9CE4_8422_2325;
    for b in SALT.bytes() {
        h = (h ^ b as u64).wrapping_mul(0x0000_0100_0000_01B3);
    }
    h as i64
}

pub fn hash_unit(a: i64, b: i64) -> f64 {
    let h = mix(seed() as u64 ^ mix(a as u64) ^ (mix(b as u64) << 1));
    (h >> 11) as f64 * (1.0 / (1u64 << 53) as f64)
}

pub fn gauss(salt: i64, bucket: i64) -> f64 {
    (hash_unit(salt, bucket) + hash_unit(salt + 1000, bucket) + hash_unit(salt + 2000, bucket) - 1.5) * 2.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_java_random() {
        let mut r = JavaRandom::new(0);
        assert_eq!(r.next_i32(), -1155484576);
        let mut r = JavaRandom::new(0);
        assert!((r.next_double() - 0.730967787376657).abs() < 1e-15);
        let mut r = JavaRandom::new(42);
        let v: Vec<i32> = (0..5).map(|_| r.next_int(10)).collect();
        assert_eq!(v, vec![0, 3, 8, 4, 0]);
    }

    #[test]
    fn shuffle_is_permutation_and_deterministic() {
        let mut a: Vec<i32> = (1..=20).collect();
        let mut b = a.clone();
        JavaRandom::new(7).shuffle(&mut a);
        JavaRandom::new(7).shuffle(&mut b);
        assert_eq!(a, b);
        let mut c = a.clone();
        c.sort();
        assert_eq!(c, (1..=20).collect::<Vec<i32>>());
        assert_ne!(a, (1..=20).collect::<Vec<i32>>());
    }

    #[test]
    fn hash_unit_range_and_stability() {
        for i in 0..1000 {
            let v = hash_unit(i, i * 7);
            assert!((0.0..1.0).contains(&v));
            assert_eq!(v, hash_unit(i, i * 7));
        }
        assert_ne!(hash_unit(1, 2), hash_unit(2, 1));
    }
}
