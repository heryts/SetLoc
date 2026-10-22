use crate::fixed::fixed;
use crate::rng::{seed, JavaRandom, XorShift};
use std::collections::HashMap;

const TTL_MS: i64 = 30_000;
const PREFIXES: [&str; 17] = [
    "HOME-", "TP-LINK_", "IndiHome-", "MyRepublic-", "Biznet-", "AndroidAP_", "WARP-", "Xiaomi_", "Redmi_", "OPPO-", "vivo-",
    "realme-", "HUAWEI-", "Tenda_", "MERCURY_", "Cisco-", "DIRECT-",
];
const HEX: &[u8; 16] = b"0123456789ABCDEF";

#[derive(Clone, Debug, PartialEq)]
pub struct Ap {
    pub ssid: String,
    pub bssid: String,
    pub rssi: i32,
    pub freq: i32,
}

pub struct EnvSim {
    cache: HashMap<String, (Vec<Ap>, i64)>,
    jitter: XorShift,
}

impl EnvSim {
    pub fn new(entropy: u64) -> Self {
        EnvSim { cache: HashMap::new(), jitter: XorShift::new(entropy) }
    }

    pub fn wifi_for(&mut self, lat: f64, lng: f64, now_ms: i64) -> Vec<Ap> {
        let bucket = now_ms / TTL_MS;
        let key = format!("{},{},{}", fixed(lat, 3), fixed(lng, 3), bucket);
        if let Some((aps, ts)) = self.cache.get(&key) {
            if now_ms - ts < TTL_MS {
                let aps = aps.clone();
                return self.jittered(aps);
            }
        }
        if self.cache.len() > 32 {
            self.cache.clear();
        }
        let mut sd = seed() ^ bucket;
        for b in format!("wifi:{}", key).bytes() {
            sd = sd.wrapping_mul(31).wrapping_add(b as i8 as i64);
        }
        let mut r = JavaRandom::new(sd);
        let n = 4 + r.next_int(5);
        let mut out = Vec::with_capacity(n as usize);
        for _ in 0..n {
            let mut ssid = String::from(PREFIXES[r.next_int(PREFIXES.len() as i32) as usize]);
            let len = 4 + r.next_int(6);
            for _ in 0..len {
                ssid.push(HEX[r.next_int(16) as usize] as char);
            }
            let first = (r.next_int(256) | 0x02) & 0xFE;
            let mut bssid = format!("{:02X}:", first);
            for j in 1..6 {
                bssid.push_str(&format!("{:02X}", r.next_int(256)));
                if j < 5 {
                    bssid.push(':');
                }
            }
            let rssi = -40 - r.next_int(40);
            let freq = if r.next_bool() { 2412 + 5 * r.next_int(13) } else { 5180 + 5 * r.next_int(8) };
            out.push(Ap { ssid, bssid, rssi, freq });
        }
        self.cache.insert(key, (out.clone(), now_ms));
        out
    }

    fn jittered(&mut self, aps: Vec<Ap>) -> Vec<Ap> {
        aps.into_iter()
            .map(|mut a| {
                a.rssi = (a.rssi + self.jitter.below(5) as i32 - 2).min(-30).max(-95);
                a
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deterministic_per_bucket_and_shape() {
        let mut a = EnvSim::new(1);
        let mut b = EnvSim::new(2);
        let x = a.wifi_for(-7.7829, 110.3671, 90_000);
        let y = b.wifi_for(-7.7829, 110.3671, 90_000);
        assert_eq!(x, y);
        assert!((4..=8).contains(&x.len()));
        for ap in &x {
            assert_eq!(ap.bssid.len(), 17);
            let first = u8::from_str_radix(&ap.bssid[..2], 16).unwrap();
            assert_eq!(first & 1, 0);
            assert_eq!(first & 2, 2);
            assert!((-80..=-41).contains(&ap.rssi));
            assert!(ap.freq >= 2412);
        }
    }

    #[test]
    fn cached_calls_jitter_rssi_within_bounds() {
        let mut e = EnvSim::new(9);
        let base = e.wifi_for(1.0, 2.0, 60_000);
        for _ in 0..50 {
            let j = e.wifi_for(1.0, 2.0, 61_000);
            assert_eq!(j.len(), base.len());
            for (p, q) in base.iter().zip(j.iter()) {
                assert_eq!(p.ssid, q.ssid);
                assert!((p.rssi - q.rssi).abs() <= 2);
                assert!((-95..=-30).contains(&q.rssi));
            }
        }
    }

    #[test]
    fn different_places_differ() {
        let mut e = EnvSim::new(3);
        assert_ne!(e.wifi_for(1.0, 2.0, 0), e.wifi_for(40.0, 50.0, 0));
    }
}
