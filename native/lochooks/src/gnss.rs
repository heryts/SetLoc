use configcore::jitter;
use std::f64::consts::PI;

pub const T_GPS: i32 = 1;
pub const T_GLO: i32 = 3;
pub const T_BDS: i32 = 5;
pub const T_GAL: i32 = 6;

const F_EPH: i32 = 1;
const F_ALM: i32 = 2;
const F_USED: i32 = 4;
const F_CARRIER: i32 = 8;
const F_BASEBAND: i32 = 16;
const SVID_SHIFT: i32 = 12;
const TYPE_SHIFT: i32 = 8;
const GPS_EPOCH_MS: i64 = 315_964_800_000;
const LEAP_S: i64 = 18;
const WEEK_NS: i64 = 604_800 * 1_000_000_000;
const LIGHT: f64 = 299_792_458.0;
const MASK48: i64 = (1i64 << 48) - 1;

pub const STRIDE_IN: usize = 7;
pub const STRIDE_OUT: usize = 9;
pub const MAX_SATS: usize = 64;

pub struct JRandom {
    seed: i64,
}

impl JRandom {
    pub fn new(seed: i64) -> Self {
        JRandom { seed: (seed ^ 0x5_DEEC_E66D) & MASK48 }
    }

    pub fn next_bits(&mut self, bits: u32) -> i32 {
        self.seed = self.seed.wrapping_mul(0x5_DEEC_E66D).wrapping_add(0xB) & MASK48;
        (self.seed >> (48 - bits)) as i32
    }

    pub fn next_int(&mut self, bound: i32) -> i32 {
        let mut r = self.next_bits(31);
        let m = bound - 1;
        if bound & m == 0 {
            return ((bound as i64 * r as i64) >> 31) as i32;
        }
        let mut u = r;
        loop {
            r = u % bound;
            if u.wrapping_sub(r).wrapping_add(m) >= 0 {
                return r;
            }
            u = self.next_bits(31);
        }
    }

    pub fn next_bool(&mut self) -> bool {
        self.next_bits(1) != 0
    }

    pub fn next_double(&mut self) -> f64 {
        let hi = self.next_bits(26) as i64;
        let lo = self.next_bits(27) as i64;
        ((hi << 27) + lo) as f64 * (1.0 / (1u64 << 53) as f64)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct SatDef {
    pub ty: i32,
    pub svid: i32,
    pub az0: f64,
    pub rate: f64,
    pub phase: f64,
    pub period: f64,
}

pub fn build_pool(seed: i64) -> Vec<SatDef> {
    let mut r = JRandom::new(seed);
    let plan: [(i32, i32, usize); 4] = [(T_GPS, 32, 11), (T_GLO, 24, 6), (T_GAL, 36, 6), (T_BDS, 37, 6)];
    let mut out = Vec::new();
    for (ty, count, take) in plan {
        let mut ids: Vec<i32> = (1..=count).collect();
        let mut i = ids.len();
        while i > 1 {
            let j = r.next_int(i as i32) as usize;
            ids.swap(i - 1, j);
            i -= 1;
        }
        for id in ids.iter().take(take) {
            let sign = if r.next_bool() { 1.0 } else { -1.0 };
            let rate = sign * (0.004 + r.next_double() * 0.006);
            let az0 = r.next_double() * 360.0;
            let phase = r.next_double() * 2.0 * PI;
            let period = 600.0 + r.next_double() * 900.0;
            out.push(SatDef { ty, svid: *id, az0, rate, phase, period });
        }
    }
    out
}

#[derive(Clone, Debug, PartialEq)]
pub struct Snap {
    pub ty: Vec<i32>,
    pub svid: Vec<i32>,
    pub cn0: Vec<f32>,
    pub elev: Vec<f32>,
    pub az: Vec<f32>,
    pub carrier: Vec<f32>,
    pub used: Vec<bool>,
}

impl Snap {
    pub fn len(&self) -> usize {
        self.ty.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ty.is_empty()
    }

    pub fn used_count(&self) -> usize {
        self.used.iter().filter(|u| **u).count()
    }

    pub fn flags(&self) -> Vec<i32> {
        (0..self.len())
            .map(|i| {
                (self.svid[i] << SVID_SHIFT)
                    | (self.ty[i] << TYPE_SHIFT)
                    | F_EPH
                    | F_ALM
                    | F_CARRIER
                    | F_BASEBAND
                    | if self.used[i] { F_USED } else { 0 }
            })
            .collect()
    }

    pub fn baseband(&self) -> Vec<f32> {
        self.cn0.iter().map(|c| (c - 1.5f32).max(0.0f32)).collect()
    }
}

fn carrier(ty: i32, svid: i32) -> f32 {
    match ty {
        T_GLO => 1_602_000_000f32 + ((svid % 14 - 7) as f32) * 562_500f32,
        T_BDS => 1_561_098_000f32,
        _ => 1_575_420_000f32,
    }
}

pub fn snapshot(seed: u64, pool: &[SatDef], now_sec: i64, fixed: bool) -> Snap {
    let (g0, g1, _) = jitter::gnss_extras(seed, now_sec);
    let total = pool.len();
    let want = total.min((g0 + 3 + (g0 & 1)).max(0) as usize);
    let t = now_sec as f64;
    let mut el = vec![0.0f64; total];
    let mut score = vec![0.0f64; total];
    let mut order: Vec<usize> = (0..total).collect();
    for i in 0..total {
        let s = &pool[i];
        el[i] = 8.0 + 77.0 * (0.5 + 0.5 * (s.phase + t / s.period).sin());
        score[i] = el[i] + (jitter::hash_unit(seed, s.ty as i64 * 1000 + s.svid as i64, now_sec / 5) - 0.5) * 20.0;
    }
    order.sort_by(|&a, &b| score[b].total_cmp(&score[a]));
    let used_left = if fixed { g0.max(0) as usize } else { 0 };
    let mut used_flag = vec![false; want];
    if used_left > 0 && want > 0 {
        let weight = |k: usize| -> f64 {
            let i = order[k];
            let s = &pool[i];
            el[i] + (jitter::hash_unit(seed, s.ty as i64 * 100 + s.svid as i64, now_sec) - 0.5) * 15.0
        };
        let mut used_order: Vec<usize> = (0..want).collect();
        used_order.sort_by(|&a, &b| weight(b).total_cmp(&weight(a)));
        for k in 0..used_left.min(want) {
            used_flag[used_order[k]] = true;
        }
    }
    let mut out = Snap {
        ty: Vec::with_capacity(want),
        svid: Vec::with_capacity(want),
        cn0: Vec::with_capacity(want),
        elev: Vec::with_capacity(want),
        az: Vec::with_capacity(want),
        carrier: Vec::with_capacity(want),
        used: Vec::with_capacity(want),
    };
    for k in 0..want {
        let i = order[k];
        let s = &pool[i];
        let norm = (el[i] - 8.0) / 77.0;
        let c = g1 as f64 - 7.0 + 14.0 * norm + (jitter::hash_unit(seed, s.ty as i64 * 100 + s.svid as i64, now_sec) - 0.5) * 2.4;
        out.ty.push(s.ty);
        out.svid.push(s.svid);
        out.cn0.push(c.clamp(14.0, 47.0) as f32);
        out.elev.push(el[i] as f32);
        out.az.push((((s.az0 + s.rate * t) % 360.0 + 360.0) % 360.0) as f32);
        out.carrier.push(carrier(s.ty, s.svid));
        out.used.push(used_flag[k]);
    }
    out
}

pub fn pack_out(s: &Snap) -> Vec<f64> {
    let n = s.len();
    let flags = s.flags();
    let bb = s.baseband();
    let mut a = Vec::with_capacity(1 + n * STRIDE_OUT);
    a.push(n as f64);
    for i in 0..n {
        a.push(s.ty[i] as f64);
        a.push(s.svid[i] as f64);
        a.push(s.cn0[i] as f64);
        a.push(s.elev[i] as f64);
        a.push(s.az[i] as f64);
        a.push(s.carrier[i] as f64);
        a.push(if s.used[i] { 1.0 } else { 0.0 });
        a.push(flags[i] as f64);
        a.push(bb[i] as f64);
    }
    a
}

pub fn unpack_in(a: &[f64]) -> Option<Snap> {
    let n0 = *a.first()?;
    if !(0.0..=MAX_SATS as f64).contains(&n0) || n0.fract() != 0.0 {
        return None;
    }
    let n = n0 as usize;
    if a.len() != 1 + n * STRIDE_IN {
        return None;
    }
    let mut s = Snap {
        ty: Vec::with_capacity(n),
        svid: Vec::with_capacity(n),
        cn0: Vec::with_capacity(n),
        elev: Vec::with_capacity(n),
        az: Vec::with_capacity(n),
        carrier: Vec::with_capacity(n),
        used: Vec::with_capacity(n),
    };
    for i in 0..n {
        let o = 1 + i * STRIDE_IN;
        s.ty.push(a[o] as i32);
        s.svid.push(a[o + 1] as i32);
        s.cn0.push(a[o + 2] as f32);
        s.elev.push(a[o + 3] as f32);
        s.az.push(a[o + 4] as f32);
        s.carrier.push(a[o + 5] as f32);
        s.used.push(a[o + 6] != 0.0);
    }
    Some(s)
}

fn talker(ty: i32) -> &'static str {
    match ty {
        T_GLO => "GL",
        T_GAL => "GA",
        T_BDS => "GB",
        _ => "GP",
    }
}

fn system_id(ty: i32) -> i32 {
    match ty {
        T_GLO => 2,
        T_GAL => 3,
        T_BDS => 4,
        _ => 1,
    }
}

fn signal_id(ty: i32) -> i32 {
    if ty == T_GAL {
        7
    } else {
        1
    }
}

fn nmea_id(ty: i32, svid: i32) -> i32 {
    if ty == T_GLO {
        64 + svid
    } else {
        svid
    }
}

fn jround(x: f64) -> i64 {
    if x.is_nan() {
        return 0;
    }
    let f = x.floor();
    let r = if x - f >= 0.5 { f + 1.0 } else { f };
    r as i64
}

fn jmax(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if a > b {
        a
    } else {
        b
    }
}

fn jmin(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if a < b {
        a
    } else {
        b
    }
}

pub fn java_fixed(v: f64, prec: usize) -> String {
    if v.is_nan() {
        return "NaN".to_string();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity" } else { "-Infinity" }.to_string();
    }
    let neg = v.is_sign_negative();
    let sci = format!("{:e}", v.abs());
    let (mant, exp) = match sci.split_once('e') {
        Some(p) => p,
        None => (sci.as_str(), "0"),
    };
    let exp: i64 = exp.parse().unwrap_or(0);
    let mut digits: Vec<u8> = mant.bytes().filter(|c| c.is_ascii_digit()).map(|c| c - b'0').collect();
    if digits.is_empty() {
        digits.push(0);
    }
    let mut point: i64 = exp + 1;
    let n = digits.len() as i64;
    let keep = point + prec as i64;
    if keep >= 0 && keep < n {
        if keep == 0 {
            if digits[0] >= 5 {
                digits = vec![1];
                point += 1;
            } else {
                digits = vec![0];
            }
        } else if digits[keep as usize] >= 5 {
            digits.truncate(keep as usize);
            let mut i = digits.len();
            let mut carry = true;
            while carry && i > 0 {
                i -= 1;
                if digits[i] == 9 {
                    digits[i] = 0;
                } else {
                    digits[i] += 1;
                    carry = false;
                }
            }
            if carry {
                digits.insert(0, 1);
                point += 1;
            }
        }
    }
    let digit_at = |idx: i64| -> char {
        if idx >= 0 && (idx as usize) < digits.len() {
            (b'0' + digits[idx as usize]) as char
        } else {
            '0'
        }
    };
    let mut out = String::new();
    if neg {
        out.push('-');
    }
    if point <= 0 {
        out.push('0');
    } else {
        for i in 0..point {
            out.push(digit_at(i));
        }
    }
    if prec > 0 {
        out.push('.');
        for j in 0..prec as i64 {
            out.push(digit_at(point + j));
        }
    }
    out
}

fn sentence(body: &str) -> String {
    let cs = body.bytes().fold(0u8, |a, b| a ^ b);
    format!("${}*{:02X}\r\n", body, cs)
}

fn coord(v: f64, lat: bool) -> String {
    let t = jround(v.abs() * 60_000_000.0);
    let m = t % 60_000_000;
    if lat {
        format!("{:02}{:02}.{:06}", t / 60_000_000, m / 1_000_000, m % 1_000_000)
    } else {
        format!("{:03}{:02}.{:06}", t / 60_000_000, m / 1_000_000, m % 1_000_000)
    }
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[derive(Clone, Copy, Debug)]
pub struct FixIn {
    pub lat: f64,
    pub lng: f64,
    pub acc: f32,
    pub spd: Option<f32>,
    pub brg: Option<f32>,
    pub alt: Option<f64>,
}

pub fn nmea(s: &Snap, f: &FixIn, wall_ms: i64) -> Vec<String> {
    let ms = wall_ms - wall_ms % 1000;
    let secs = ms.div_euclid(1000);
    let days = secs.div_euclid(86_400);
    let sod = secs.rem_euclid(86_400);
    let (year, month, day) = civil_from_days(days);
    let hms = format!("{:02}{:02}{:02}.00", sod / 3600, sod % 3600 / 60, sod % 60);
    let dmy = format!("{:02}{:02}{:02}", day, month, year % 100);
    let ns = if f.lat >= 0.0 { 'N' } else { 'S' };
    let ew = if f.lng >= 0.0 { 'E' } else { 'W' };
    let hdop = jmax(0.7, jmin(5.0, f.acc as f64 / 5.0));
    let vdop = hdop * 1.1;
    let pdop = (hdop * hdop + vdop * vdop).sqrt();
    let knots = f.spd.map_or(0.0, |v| v as f64 * 1.943844);
    let course = f.brg.map_or(0.0, |v| v as f64);
    let alt = f.alt.unwrap_or(0.0);
    let used = s.used_count();
    let fix_quality = if used > 0 { 1 } else { 0 };
    let fix_type = if used >= 4 { 3 } else { 1 };
    let mut out: Vec<String> = Vec::new();
    out.push(sentence(&format!(
        "GNGGA,{},{},{},{},{},{},{:02},{},{},M,{},M,,",
        hms,
        coord(f.lat, true),
        ns,
        coord(f.lng, false),
        ew,
        fix_quality,
        used,
        java_fixed(hdop, 1),
        java_fixed(alt, 1),
        java_fixed(0.0, 1)
    )));
    let groups = [T_GPS, T_GLO, T_GAL, T_BDS];
    for ty in groups {
        let ids: Vec<i32> = (0..s.len()).filter(|&i| s.ty[i] == ty && s.used[i]).map(|i| nmea_id(ty, s.svid[i])).collect();
        if ids.is_empty() {
            continue;
        }
        let mut slots = String::new();
        for i in 0..12 {
            if i > 0 {
                slots.push(',');
            }
            if i < ids.len() {
                slots.push_str(&format!("{:02}", ids[i]));
            }
        }
        out.push(sentence(&format!(
            "GNGSA,A,{},{},{},{},{},{}",
            fix_type,
            slots,
            java_fixed(pdop, 1),
            java_fixed(hdop, 1),
            java_fixed(vdop, 1),
            system_id(ty)
        )));
    }
    for ty in groups {
        let idx: Vec<usize> = (0..s.len()).filter(|&i| s.ty[i] == ty).collect();
        if idx.is_empty() {
            continue;
        }
        let pages = idx.len().div_ceil(4);
        for p in 0..pages {
            let mut sb = format!("{}GSV,{},{},{:02}", talker(ty), pages, p + 1, idx.len());
            let end = idx.len().min(p * 4 + 4);
            for &i in &idx[p * 4..end] {
                sb.push_str(&format!(
                    ",{:02},{:02},{:03},{:02}",
                    nmea_id(ty, s.svid[i]),
                    jround(s.elev[i] as f64),
                    jround(s.az[i] as f64) % 360,
                    jround(s.cn0[i] as f64)
                ));
            }
            sb.push(',');
            sb.push_str(&signal_id(ty).to_string());
            out.push(sentence(&sb));
        }
    }
    out.push(sentence(&format!(
        "GNRMC,{},{},{},{},{},{},{},{},{},,,A",
        hms,
        if used > 0 { 'A' } else { 'V' },
        coord(f.lat, true),
        ns,
        coord(f.lng, false),
        ew,
        java_fixed(knots, 2),
        java_fixed(course, 2),
        dmy
    )));
    out
}

pub fn meas(seed: u64, s: &Snap, now_ms: i64, time_ns: i64) -> Vec<i64> {
    let gps_ns = now_ms.wrapping_sub(GPS_EPOCH_MS).wrapping_add(LEAP_S * 1000).wrapping_mul(1_000_000);
    let mut out = Vec::with_capacity(2 + 2 * s.len());
    out.push(gps_ns);
    out.push(gps_ns % WEEK_NS);
    let bucket = time_ns / 1_000_000_000;
    for i in 0..s.len() {
        let norm = (s.elev[i] as f64 - 8.0) / 77.0;
        let travel = ((20_200_000.0 + (1.0 - norm) * 5_000_000.0) / LIGHT * 1e9) as i64;
        let base = (((s.svid[i] * 37 % 1600) - 800) as f64) * 0.9;
        let jit = (jitter::hash_unit(seed, s.ty[i] as i64 * 100 + s.svid[i] as i64, bucket) - 0.5) * 6.0;
        out.push(travel);
        out.push((base + jit).to_bits() as i64);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const SEED: u64 = 0x1234_5678_9ABC_DEF0;

    #[test]
    fn jrandom_matches_java_vectors() {
        let mut r = JRandom::new(42);
        assert_eq!(r.next_bits(32), -1_170_105_035);
        assert_eq!(r.next_bits(32), 234_785_527);
    }

    #[test]
    fn pool_shape() {
        let p = build_pool(987_654_321);
        assert_eq!(p.len(), 29);
        let count = |t: i32| p.iter().filter(|s| s.ty == t).count();
        assert_eq!((count(T_GPS), count(T_GLO), count(T_GAL), count(T_BDS)), (11, 6, 6, 6));
        for t in [T_GPS, T_GLO, T_GAL, T_BDS] {
            let mut ids: Vec<i32> = p.iter().filter(|s| s.ty == t).map(|s| s.svid).collect();
            ids.sort();
            ids.dedup();
            assert_eq!(ids.len(), count(t));
        }
        assert_eq!(build_pool(1), build_pool(1));
        assert_ne!(build_pool(1), build_pool(2));
    }

    #[test]
    fn java_fixed_cases() {
        assert_eq!(java_fixed(0.25, 1), "0.3");
        assert_eq!(java_fixed(0.15, 1), "0.2");
        assert_eq!(java_fixed(2.5, 0), "3");
        assert_eq!(java_fixed(0.0, 1), "0.0");
        assert_eq!(java_fixed(-0.0, 1), "-0.0");
        assert_eq!(java_fixed(-0.04, 1), "-0.0");
        assert_eq!(java_fixed(9.96, 1), "10.0");
        assert_eq!(java_fixed(99.995, 2), "100.00");
        assert_eq!(java_fixed(1234.55, 1), "1234.6");
        assert_eq!(java_fixed(0.005, 2), "0.01");
        assert_eq!(java_fixed(0.004, 2), "0.00");
        assert_eq!(java_fixed(12.0, 2), "12.00");
        assert_eq!(java_fixed(f64::NAN, 1), "NaN");
        assert_eq!(java_fixed(f64::NEG_INFINITY, 1), "-Infinity");
    }

    #[test]
    fn jround_half_up() {
        assert_eq!(jround(0.5), 1);
        assert_eq!(jround(-0.5), 0);
        assert_eq!(jround(-1.5), -1);
        assert_eq!(jround(0.49999999999999994), 0);
        assert_eq!(jround(2.4), 2);
        assert_eq!(jround(f64::NAN), 0);
    }

    #[test]
    fn civil_dates() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(11_016), (2000, 2, 29));
        assert_eq!(civil_from_days(19_723), (2024, 1, 1));
    }

    #[test]
    fn snapshot_invariants() {
        let pool = build_pool(777);
        for t in (0..200_000).step_by(1301) {
            for fixed in [false, true] {
                let s = snapshot(SEED, &pool, t, fixed);
                let (g0, _, _) = jitter::gnss_extras(SEED, t);
                assert_eq!(s.len(), pool.len().min((g0 + 3 + (g0 & 1)) as usize));
                assert_eq!(s.used_count(), if fixed { (g0 as usize).min(s.len()) } else { 0 });
                for i in 0..s.len() {
                    assert!((14.0..=47.0).contains(&s.cn0[i]));
                    assert!((8.0..=85.0).contains(&s.elev[i]));
                    assert!((0.0..=360.0).contains(&s.az[i]));
                }
                let mut seen = std::collections::HashSet::new();
                for i in 0..s.len() {
                    assert!(seen.insert((s.ty[i], s.svid[i])));
                }
            }
        }
    }

    #[test]
    fn pack_roundtrip_and_rejects() {
        let pool = build_pool(5);
        let s = snapshot(SEED, &pool, 1234, true);
        let out = pack_out(&s);
        assert_eq!(out.len(), 1 + s.len() * STRIDE_OUT);
        let mut input = vec![s.len() as f64];
        for i in 0..s.len() {
            let o = 1 + i * STRIDE_OUT;
            input.extend_from_slice(&out[o..o + STRIDE_IN]);
        }
        assert_eq!(unpack_in(&input), Some(s));
        assert!(unpack_in(&[]).is_none());
        assert!(unpack_in(&[1.0, 2.0]).is_none());
        assert!(unpack_in(&[-1.0]).is_none());
        assert!(unpack_in(&[65.0]).is_none());
        assert!(unpack_in(&[f64::NAN]).is_none());
        assert!(unpack_in(&[0.5]).is_none());
    }

    #[test]
    fn flags_and_baseband() {
        let s = Snap {
            ty: vec![T_GPS, T_GLO],
            svid: vec![5, 9],
            cn0: vec![30.0, 1.0],
            elev: vec![40.0, 20.0],
            az: vec![10.0, 200.0],
            carrier: vec![1_575_420_000.0, 1_602_000_000.0],
            used: vec![true, false],
        };
        assert_eq!(s.flags(), vec![(5 << 12) | (1 << 8) | 1 | 2 | 8 | 16 | 4, (9 << 12) | (3 << 8) | 1 | 2 | 8 | 16]);
        assert_eq!(s.baseband(), vec![28.5, 0.0]);
    }

    #[test]
    fn nmea_structure_and_checksum() {
        let pool = build_pool(31);
        let s = snapshot(SEED, &pool, 5000, true);
        let f = FixIn { lat: -6.2088, lng: 106.8456, acc: 12.5, spd: Some(1.2), brg: Some(270.5), alt: Some(8.3) };
        let v = nmea(&s, &f, 1_700_000_123_456);
        assert!(v[0].starts_with("$GNGGA,221523.00,0612.528000,S,10650.736000,E,1,"));
        assert!(v.last().is_some_and(|l| l.starts_with("$GNRMC,221523.00,A,0612.528000,S,10650.736000,E,")));
        assert!(v.last().is_some_and(|l| l.contains(",141123,,,A*")));
        for line in &v {
            assert!(line.ends_with("\r\n"));
            let star = line.rfind('*').unwrap_or(0);
            let cs = line[1..star].bytes().fold(0u8, |a, b| a ^ b);
            assert_eq!(&line[star + 1..star + 3], format!("{:02X}", cs));
        }
        let idle = snapshot(SEED, &pool, 5000, false);
        let w = nmea(&idle, &f, 1_700_000_123_456);
        assert!(w[0].contains(",0,00,"));
        assert!(w.last().is_some_and(|l| l.contains(",V,")));
        assert!(w.iter().all(|l| !l.starts_with("$GNGSA")));
    }

    #[test]
    fn meas_layout() {
        let pool = build_pool(8);
        let s = snapshot(SEED, &pool, 99, true);
        let m = meas(SEED, &s, 1_700_000_123_456, 98_765_432_101_234);
        assert_eq!(m.len(), 2 + 2 * s.len());
        assert_eq!(m[0], (1_700_000_123_456 - 315_964_800_000 + 18_000) * 1_000_000);
        assert_eq!(m[1], m[0] % 604_800_000_000_000);
        for i in 0..s.len() {
            assert!((60_000_000..100_000_000).contains(&m[2 + 2 * i]));
            let rate = f64::from_bits(m[3 + 2 * i] as u64);
            assert!(rate.abs() <= 720.0 + 3.0);
        }
    }
}
