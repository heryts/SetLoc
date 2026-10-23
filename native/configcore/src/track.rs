pub struct Track {
    pub t0: i64,
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
}

pub fn parse_track(s: &str) -> Option<Track> {
    let mut t0: Option<i64> = None;
    let mut tk: i64 = 0;
    let mut d: Option<&str> = None;
    for kv in s.split(';') {
        let (k, v) = kv.split_once('=')?;
        match k {
            "t" => t0 = v.parse().ok(),
            "k" => tk = v.parse().ok()?,
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
        let f: Vec<&str> = row.split(',').collect();
        if f.len() < 5 {
            return None;
        }
        la.push(f[0].parse().ok()?);
        lo.push(f[1].parse().ok()?);
        sp.push(f[2].parse().ok()?);
        br.push(f[3].parse().ok()?);
        al.push(f[4].parse().ok()?);
    }
    if la.is_empty() {
        return None;
    }
    Some(Track { t0, tk, la, lo, al, sp, br })
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
    let rel = now_ms - t.t0;
    let at = if rel <= 0 { 0.0 } else { rel as f64 / t.tk as f64 };
    let (i, f) = if at >= (n - 1) as f64 {
        (n - 1, 0.0)
    } else {
        (at as usize, at - at.floor())
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
        (t.sp[i] + (t.sp[j] - t.sp[i]) * f as f32).max(0.0)
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

#[cfg(test)]
mod tests {
    use super::*;

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
    fn stale_levels() {
        assert_eq!(stale_level(0), 0);
        assert_eq!(stale_level(3000), 1);
        assert_eq!(stale_level(60_000), 2);
    }
}
