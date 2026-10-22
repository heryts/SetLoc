use crate::rng::{hash_unit, seed, JavaRandom};
use crate::state::gnss_extras;
use std::f64::consts::PI;
use std::sync::OnceLock;

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
pub const GPS_EPOCH_MS: i64 = 315_964_800_000;
pub const LEAP_S: i32 = 18;
const WEEK_NS: i64 = 604_800 * 1_000_000_000;
const LIGHT: f64 = 299_792_458.0;

#[derive(Clone, Copy)]
struct Sat {
    ty: i32,
    svid: i32,
    az0: f64,
    rate: f64,
    phase: f64,
    period: f64,
}

#[derive(Clone, Debug, Default)]
pub struct Snapshot {
    pub n: usize,
    pub ty: Vec<i32>,
    pub svid: Vec<i32>,
    pub cn0: Vec<f32>,
    pub elev: Vec<f32>,
    pub az: Vec<f32>,
    pub carrier: Vec<f32>,
    pub used: Vec<bool>,
}

impl Snapshot {
    fn new(n: usize) -> Self {
        Snapshot {
            n,
            ty: vec![0; n],
            svid: vec![0; n],
            cn0: vec![0.0; n],
            elev: vec![0.0; n],
            az: vec![0.0; n],
            carrier: vec![0.0; n],
            used: vec![false; n],
        }
    }

    pub fn used_count(&self) -> usize {
        self.used.iter().filter(|u| **u).count()
    }

    pub fn flags(&self) -> Vec<i32> {
        (0..self.n)
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
        self.cn0.iter().map(|c| (c - 1.5).max(0.0)).collect()
    }
}

fn pool() -> &'static [Sat] {
    static POOL: OnceLock<Vec<Sat>> = OnceLock::new();
    POOL.get_or_init(|| {
        let mut r = JavaRandom::new(seed());
        let mut out = Vec::new();
        for (ty, total, take) in [(T_GPS, 32, 11), (T_GLO, 24, 6), (T_GAL, 36, 6), (T_BDS, 37, 6)] {
            let mut ids: Vec<i32> = (1..=total).collect();
            r.shuffle(&mut ids);
            for id in ids.iter().take(take) {
                let sign = if r.next_bool() { 1.0 } else { -1.0 };
                let rate = sign * (0.004 + r.next_double() * 0.006);
                let az0 = r.next_double() * 360.0;
                let phase = r.next_double() * 2.0 * PI;
                let period = 600.0 + r.next_double() * 900.0;
                out.push(Sat { ty, svid: *id, az0, rate, phase, period });
            }
        }
        out
    })
}

pub fn carrier(ty: i32, svid: i32) -> f32 {
    match ty {
        T_GLO => 1_602_000_000.0 + ((svid % 14 - 7) as f32) * 562_500.0,
        T_BDS => 1_561_098_000.0,
        _ => 1_575_420_000.0,
    }
}

pub fn snapshot(fixed: bool, elapsed_ms: i64) -> Snapshot {
    let pool = pool();
    let g = gnss_extras(elapsed_ms);
    let total = pool.len();
    let want = total.min((g[0] + 3 + (g[0] & 1)) as usize);
    let now_sec = elapsed_ms / 1000;
    let t = now_sec as f64;
    let mut el = vec![0.0f64; total];
    let mut score = vec![0.0f64; total];
    for (i, s) in pool.iter().enumerate() {
        el[i] = 8.0 + 77.0 * (0.5 + 0.5 * (s.phase + t / s.period).sin());
        score[i] = el[i] + (hash_unit(s.ty as i64 * 1000 + s.svid as i64, now_sec / 5) - 0.5) * 20.0;
    }
    let mut order: Vec<usize> = (0..total).collect();
    order.sort_by(|a, b| score[*b].total_cmp(&score[*a]));
    let mut out = Snapshot::new(want);
    let used_left = if fixed { g[0] as usize } else { 0 };
    let mut used_flag = vec![false; want];
    if used_left > 0 && want > 0 {
        let weight = |k: usize| {
            let s = &pool[order[k]];
            el[order[k]] + (hash_unit(s.ty as i64 * 100 + s.svid as i64, now_sec) - 0.5) * 15.0
        };
        let mut used_order: Vec<usize> = (0..want).collect();
        used_order.sort_by(|a, b| weight(*b).total_cmp(&weight(*a)));
        for k in 0..used_left.min(want) {
            used_flag[used_order[k]] = true;
        }
    }
    for k in 0..want {
        let i = order[k];
        let s = &pool[i];
        let norm = (el[i] - 8.0) / 77.0;
        let c = g[1] as f64 - 7.0 + 14.0 * norm + (hash_unit(s.ty as i64 * 100 + s.svid as i64, t as i64) - 0.5) * 2.4;
        out.ty[k] = s.ty;
        out.svid[k] = s.svid;
        out.cn0[k] = c.max(14.0).min(47.0) as f32;
        out.elev[k] = el[i] as f32;
        out.az[k] = (((s.az0 + s.rate * t) % 360.0 + 360.0) % 360.0) as f32;
        out.carrier[k] = carrier(s.ty, s.svid);
        out.used[k] = used_flag[k];
    }
    out
}

#[derive(Clone, Copy, Debug)]
pub struct ClockData {
    pub time_ns: i64,
    pub full_bias_ns: i64,
    pub bias_ns: f64,
    pub bias_uncertainty_ns: f64,
    pub leap_second: i32,
}

#[derive(Clone, Copy, Debug)]
pub struct Meas {
    pub svid: i32,
    pub constellation: i32,
    pub cn0: f64,
    pub state: i32,
    pub received_sv_time_ns: i64,
    pub received_sv_time_uncertainty_ns: i64,
    pub rate: f64,
    pub rate_uncertainty: f64,
    pub carrier_hz: f32,
    pub multipath: i32,
}

pub fn measurements(s: &Snapshot, wall_ms: i64, time_ns: i64) -> (ClockData, Vec<Meas>) {
    let gps_ns = (wall_ms - GPS_EPOCH_MS + LEAP_S as i64 * 1000) * 1_000_000;
    let clock = ClockData { time_ns, full_bias_ns: time_ns - gps_ns, bias_ns: 0.0, bias_uncertainty_ns: 4.0, leap_second: LEAP_S };
    let tow = gps_ns % WEEK_NS;
    let bucket = time_ns / 1_000_000_000;
    let list = (0..s.n)
        .map(|i| {
            let norm = (s.elev[i] as f64 - 8.0) / 77.0;
            let travel = ((20_200_000.0 + (1.0 - norm) * 5_000_000.0) / LIGHT * 1e9) as i64;
            let base = (((s.svid[i] * 37 % 1600) - 800) as f64) * 0.9;
            let jitter = (hash_unit(s.ty[i] as i64 * 100 + s.svid[i] as i64, bucket) - 0.5) * 6.0;
            Meas {
                svid: s.svid[i],
                constellation: s.ty[i],
                cn0: s.cn0[i] as f64,
                state: 15,
                received_sv_time_ns: tow - travel,
                received_sv_time_uncertainty_ns: 30,
                rate: base + jitter,
                rate_uncertainty: 0.2,
                carrier_hz: s.carrier[i],
                multipath: 2,
            }
        })
        .collect();
    (clock, list)
}

pub const ANTENNA_PHASE_CENTER: [f64; 6] = [1.4, 0.3, -0.9, 0.3, 2.2, 0.4];
pub const ANTENNA_MHZ: f64 = 1575.42;

pub fn status_ttff_ms(elapsed_ms: i64) -> i64 {
    700 + (hash_unit(71, elapsed_ms / 60_000) * 1500.0) as i64
}

pub fn feed_delay_ms(kind: i64, elapsed_ms: i64) -> i64 {
    let (base, span) = match kind {
        72 => (150, 200.0),
        73 | 74 => (1000, 200.0),
        _ => (200, 200.0),
    };
    base + (hash_unit(kind, elapsed_ms / 60_000) * span) as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pool_has_expected_shape() {
        let p = pool();
        assert_eq!(p.len(), 29);
        for ty in [T_GPS, T_GLO, T_GAL, T_BDS] {
            let mut ids: Vec<i32> = p.iter().filter(|s| s.ty == ty).map(|s| s.svid).collect();
            let n = ids.len();
            ids.sort();
            ids.dedup();
            assert_eq!(ids.len(), n);
        }
        assert!(p.iter().filter(|s| s.ty == T_GPS).all(|s| (1..=32).contains(&s.svid)));
    }

    #[test]
    fn snapshot_is_consistent() {
        for t in (0..400_000i64).step_by(7919) {
            let s = snapshot(true, t);
            let g = gnss_extras(t);
            assert!(s.n >= 5 && s.n <= 29);
            assert_eq!(s.used_count(), (g[0] as usize).min(s.n));
            for i in 0..s.n {
                assert!(s.cn0[i] >= 14.0 && s.cn0[i] <= 47.0);
                assert!(s.elev[i] >= 8.0 && s.elev[i] <= 85.0);
                assert!(s.az[i] >= 0.0 && s.az[i] < 360.0);
            }
            assert_eq!(snapshot(false, t).used_count(), 0);
            assert_eq!(s.flags().len(), s.n);
        }
    }

    #[test]
    fn snapshot_stable_within_second_grid() {
        let a = snapshot(true, 100_000);
        let b = snapshot(true, 100_000);
        assert_eq!(a.svid, b.svid);
        assert_eq!(a.cn0, b.cn0);
    }

    #[test]
    fn flags_pack_svid_and_type() {
        let mut s = Snapshot::new(1);
        s.svid[0] = 7;
        s.ty[0] = T_GAL;
        s.used[0] = true;
        let f = s.flags()[0];
        assert_eq!(f >> 12, 7);
        assert_eq!((f >> 8) & 0xF, T_GAL);
        assert_eq!(f & 4, 4);
    }

    #[test]
    fn measurements_match_snapshot() {
        let s = snapshot(true, 123_000);
        let (clock, m) = measurements(&s, 1_760_000_000_000, 123_000_000_000);
        assert_eq!(m.len(), s.n);
        assert_eq!(clock.leap_second, 18);
        assert!(m.iter().all(|x| x.received_sv_time_ns > 0 && x.received_sv_time_ns < WEEK_NS));
        assert!(clock.full_bias_ns < 0);
    }

    #[test]
    fn glonass_carrier_varies() {
        assert_ne!(carrier(T_GLO, 1), carrier(T_GLO, 2));
        assert_eq!(carrier(T_GPS, 5), 1_575_420_000.0);
    }
}
