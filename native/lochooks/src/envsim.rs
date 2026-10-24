use crate::gnss::JRandom;
use configcore::jitter;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

pub const TTL_MS: i64 = 30_000;
pub const MAX_APS: usize = 8;

const CACHE_LIMIT: usize = 32;
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

fn cache() -> &'static Mutex<HashMap<String, Vec<Ap>>> {
    static C: OnceLock<Mutex<HashMap<String, Vec<Ap>>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

fn jitter_state() -> &'static Mutex<u64> {
    static S: OnceLock<Mutex<u64>> = OnceLock::new();
    S.get_or_init(|| {
        let t = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0x9E37_79B9_7F4A_7C15);
        Mutex::new(t | 1)
    })
}

fn xorshift(s: &mut u64, bound: u64) -> u64 {
    *s ^= *s >> 12;
    *s ^= *s << 25;
    *s ^= *s >> 27;
    (s.wrapping_mul(0x2545_F491_4F6C_DD1D) >> 33) % bound
}

fn cell(x: f64) -> f64 {
    jitter::java_round(x * 1000.0) / 1000.0
}

pub fn key(lat: f64, lon: f64, bucket: i64) -> String {
    format!("{:.3},{:.3},{}", lat, lon, bucket)
}

pub fn generate(lat: f64, lon: f64, bucket: i64) -> Vec<Ap> {
    let k = key(lat, lon, bucket);
    let mut seed = (jitter::generate_deterministic_seed(cell(lat), cell(lon)) as i64) ^ bucket;
    for b in format!("wifi:{}", k).bytes() {
        seed = seed.wrapping_mul(31).wrapping_add(b as i8 as i64);
    }
    let mut r = JRandom::new(seed);
    let n = 4 + r.next_int(5) as usize;
    let mut out = Vec::with_capacity(n);
    for _ in 0..n {
        let mut ssid = String::from(PREFIXES[r.next_int(PREFIXES.len() as i32) as usize]);
        let len = 4 + r.next_int(6);
        for _ in 0..len {
            ssid.push(HEX[r.next_int(16) as usize] as char);
        }
        let first = (r.next_int(256) | 0x02) & 0xFE;
        let mut bssid = format!("{:02X}", first);
        for _ in 1..6 {
            bssid.push_str(&format!(":{:02X}", r.next_int(256)));
        }
        let rssi = -40 - r.next_int(40);
        let freq = if r.next_bool() { 2412 + 5 * r.next_int(13) } else { 5180 + 5 * r.next_int(8) };
        out.push(Ap { ssid, bssid, rssi, freq });
    }
    out
}

fn jittered(src: &[Ap]) -> Vec<Ap> {
    let mut g = jitter_state().lock().unwrap_or_else(|e| e.into_inner());
    src.iter()
        .map(|a| {
            let d = xorshift(&mut g, 5) as i32 - 2;
            Ap { ssid: a.ssid.clone(), bssid: a.bssid.clone(), rssi: (a.rssi + d).clamp(-95, -30), freq: a.freq }
        })
        .collect()
}

pub fn wifi_for(lat: f64, lon: f64, now_ms: i64) -> Vec<Ap> {
    let bucket = now_ms.div_euclid(TTL_MS);
    let k = key(lat, lon, bucket);
    let mut c = cache().lock().unwrap_or_else(|e| e.into_inner());
    if let Some(hit) = c.get(&k) {
        return jittered(hit);
    }
    if c.len() > CACHE_LIMIT {
        c.clear();
    }
    let out = generate(lat, lon, bucket);
    c.insert(k, out.clone());
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shape_and_ranges() {
        for i in 0..200 {
            let aps = generate(-7.8 + i as f64 * 0.01, 110.36 - i as f64 * 0.013, 55_000 + i);
            assert!((4..=MAX_APS).contains(&aps.len()));
            for a in &aps {
                assert!(PREFIXES.iter().any(|p| a.ssid.starts_with(p)));
                let tail = PREFIXES.iter().find(|p| a.ssid.starts_with(*p)).map(|p| &a.ssid[p.len()..]).unwrap();
                assert!((4..=9).contains(&tail.len()));
                assert!(tail.bytes().all(|b| HEX.contains(&b)));
                let parts: Vec<&str> = a.bssid.split(':').collect();
                assert_eq!(parts.len(), 6);
                let first = u8::from_str_radix(parts[0], 16).unwrap();
                assert_eq!(first & 1, 0);
                assert_eq!(first & 2, 2);
                assert!(parts.iter().all(|p| p.len() == 2 && p.bytes().all(|b| HEX.contains(&b))));
                assert!((-79..=-40).contains(&a.rssi));
                assert!((2412..=2472).contains(&a.freq) && (a.freq - 2412) % 5 == 0 || (5180..=5215).contains(&a.freq) && (a.freq - 5180) % 5 == 0);
            }
        }
    }

    #[test]
    fn deterministic_per_cell_and_bucket() {
        assert_eq!(generate(-7.8004, 110.3601, 9), generate(-7.8004, 110.3601, 9));
        assert_ne!(generate(-7.8004, 110.3601, 9), generate(-7.8004, 110.3601, 10));
        assert_ne!(generate(-7.8004, 110.3601, 9), generate(-7.9004, 110.3601, 9));
    }

    #[test]
    fn matches_java_random_stream() {
        let mut r = JRandom::new(42);
        assert_eq!(r.next_int(5), 0);
        assert_eq!(r.next_int(17), 11);
        assert_eq!(r.next_int(256), 174);
    }

    #[test]
    fn cached_aps_keep_identity_with_small_rssi_drift() {
        let a = wifi_for(1.5, 2.5, 90_000);
        let b = wifi_for(1.5, 2.5, 100_000);
        assert_eq!(a.len(), b.len());
        for (x, y) in a.iter().zip(&b) {
            assert_eq!((&x.ssid, &x.bssid, x.freq), (&y.ssid, &y.bssid, y.freq));
            assert!((x.rssi - y.rssi).abs() <= 4);
            assert!((-95..=-30).contains(&y.rssi));
        }
    }

    #[test]
    fn java_reference_vectors() {
        let a = generate(-7.8004, 110.3601, 0);
        assert_eq!(a.len(), 4);
        assert_eq!((a[0].ssid.as_str(), a[0].bssid.as_str(), a[0].rssi, a[0].freq), ("TP-LINK_96B82C1", "32:5B:FB:14:F6:B0", -55, 2462));
        assert_eq!((a[3].ssid.as_str(), a[3].bssid.as_str(), a[3].rssi, a[3].freq), ("Cisco-C3DA8FB", "F6:60:C4:00:52:CD", -41, 2447));
        let b = generate(-7.8004, 110.3601, 9);
        assert_eq!(b.len(), 5);
        assert_eq!((b[0].ssid.as_str(), b[0].bssid.as_str(), b[0].rssi, b[0].freq), ("HOME-1C141", "32:1C:36:F8:CC:D6", -43, 5190));
    }

    #[test]
    fn key_format_and_cell_rounding() {
        assert_eq!(key(-7.8, 110.36, 3), "-7.800,110.360,3");
        assert_eq!(cell(-7.8004), -7.8);
        assert_eq!(cell(-7.8006), -7.801);
    }
}
