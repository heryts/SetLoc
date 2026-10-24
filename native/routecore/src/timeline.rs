use crate::geo::{bearing, lerp_lng, meters, route_length, Pt};
use std::fmt::Write;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

pub const TICK_MS: i64 = 500;
pub const LINE_WIDTH: usize = 48;
pub const MAX_TICKS: f64 = 250_000.0;
const SPEED_VARIATION: f64 = 0.08;
const SPEED_PULL: f64 = 0.1;
const SPEED_STEP: f64 = 0.22;
const MIN_SPEED_MS: f64 = 0.3;
const ACCEL_WALK: f64 = 1.0;
const ACCEL_VEHICLE: f64 = 1.8;
const BRAKE_FACTOR: f64 = 1.4;
const WALK_MAX_MS: f64 = 3.0;
const CORNER_MIN_SEG_M: f64 = 2.0;
const CORNER_FLOOR_MS: f64 = 1.5;
const CORNER_LOOK_M: f64 = 20.0;
const CORNER_SOFT: f64 = 0.95;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Tick {
    pub lat: f64,
    pub lng: f64,
    pub spd: f32,
    pub brg: f32,
    pub alt: f32,
    pub pct: i32,
}

pub struct Rng {
    s: u64,
    spare: Option<f64>,
}

impl Rng {
    pub fn new(seed: u64) -> Rng {
        Rng { s: seed, spare: None }
    }

    fn next_u64(&mut self) -> u64 {
        self.s = self.s.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.s;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    fn unit(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }

    pub fn gaussian(&mut self) -> f64 {
        if let Some(v) = self.spare.take() {
            return v;
        }
        loop {
            let v1 = 2.0 * self.unit() - 1.0;
            let v2 = 2.0 * self.unit() - 1.0;
            let s = v1 * v1 + v2 * v2;
            if s < 1.0 && s != 0.0 {
                let m = (-2.0 * s.ln() / s).sqrt();
                self.spare = Some(v2 * m);
                return v1 * m;
            }
        }
    }
}

pub fn too_long(points: &[Pt], speed_kmh: f32) -> bool {
    let v = speed_kmh.max(0.5) as f64 / 3.6;
    route_length(points) / v / (TICK_MS as f64 / 1000.0) * 1.3 > MAX_TICKS
}

pub fn altitude_at(elev: &[f32], manual: f32, fraction: f64) -> f32 {
    if elev.is_empty() {
        return manual;
    }
    if elev.len() == 1 {
        return elev[0];
    }
    let x = fraction.clamp(0.0, 1.0) * (elev.len() - 1) as f64;
    let i = (x as usize).min(elev.len() - 2);
    let f = (x - i as f64) as f32;
    elev[i] + (elev[i + 1] - elev[i]) * f
}

fn corner_caps(points: &[Pt], cum: &[f64], speed_ms: f64, brake: f64) -> Vec<f64> {
    let n = points.len();
    let mut cap = vec![f64::MAX; n];
    cap[n - 1] = 0.0;
    for i in 1..n - 1 {
        let mut j = i - 1;
        while j > 0 && cum[i] - cum[j] < CORNER_LOOK_M {
            j -= 1;
        }
        let mut k = i + 1;
        while k < n - 1 && cum[k] - cum[i] < CORNER_LOOK_M {
            k += 1;
        }
        if cum[i] - cum[j] < CORNER_MIN_SEG_M || cum[k] - cum[i] < CORNER_MIN_SEG_M {
            continue;
        }
        let mut turn = (bearing(points[i], points[k]) - bearing(points[j], points[i])).abs() as f64;
        if turn > 180.0 {
            turn = 360.0 - turn;
        }
        let c = CORNER_FLOOR_MS.min(speed_ms).max(speed_ms * (1.0 - turn / 180.0).sqrt());
        if c < speed_ms * CORNER_SOFT {
            cap[i] = c;
        }
    }
    for i in (0..n - 1).rev() {
        let reach = (cap[i + 1] * cap[i + 1] + 2.0 * brake * (cum[i + 1] - cum[i])).sqrt();
        if reach < cap[i] {
            cap[i] = reach;
        }
    }
    cap
}

pub fn build(points: &[Pt], speed_kmh: f32, speed_var_kmh: f32, elev: &[f32], manual: f32, rng: &mut Rng) -> Vec<Tick> {
    if points.len() < 2 {
        return Vec::new();
    }
    let mut cum = vec![0.0f64; points.len()];
    for i in 1..points.len() {
        cum[i] = cum[i - 1] + meters(points[i - 1], points[i]);
    }
    let total = *cum.last().unwrap();
    let speed_ms = speed_kmh as f64 / 3.6;
    let var_ms = if speed_var_kmh < 0.0 { speed_ms * SPEED_VARIATION } else { speed_var_kmh as f64 / 3.6 };
    let accel = if speed_ms < WALK_MAX_MS { ACCEL_WALK } else { ACCEL_VEHICLE };
    let brake = accel * BRAKE_FACTOR;
    let min_ms = 0.05f64.max(MIN_SPEED_MS.min(speed_ms));
    let caps = corner_caps(points, &cum, speed_ms, brake);
    let dt = TICK_MS as f64 / 1000.0;
    let mut out: Vec<Tick> = Vec::new();
    let mut brg = bearing(points[0], points[1]);
    out.push(Tick { lat: points[0].0, lng: points[0].1, spd: 0.0, brg, alt: altitude_at(elev, manual, 0.0), pct: 0 });
    let mut travelled = 0.0f64;
    let mut seg = 1usize;
    let mut dev = 0.0f64;
    let mut cur = 0.0f64;
    while travelled < total {
        dev = dev * (1.0 - SPEED_PULL) + rng.gaussian() * var_ms * SPEED_STEP;
        dev = dev.max(-var_ms).min(var_ms);
        let ahead = (cum[seg] - travelled).max(0.0);
        let ceiling = (brake * brake * dt * dt + caps[seg] * caps[seg] + 2.0 * brake * ahead).sqrt() - brake * dt;
        let prev = cur;
        cur = (speed_ms + dev).min(prev + accel * dt).min(ceiling).max(min_ms).max(prev - brake * dt);
        let before = travelled;
        travelled = total.min(travelled + cur * dt);
        let shown = ((travelled - before) / dt) as f32;
        while seg < cum.len() - 1 && cum[seg] < travelled {
            seg += 1;
        }
        let seg_len = cum[seg] - cum[seg - 1];
        let f = if seg_len <= 0.0 { 1.0 } else { ((travelled - cum[seg - 1]) / seg_len).clamp(0.0, 1.0) };
        let a = points[seg - 1];
        let b = points[seg];
        if seg_len > 0.5 {
            brg = bearing(a, b);
        }
        let frac = if total > 0.0 { travelled / total } else { 1.0 };
        out.push(Tick {
            lat: a.0 + (b.0 - a.0) * f,
            lng: lerp_lng(a.1, b.1, f),
            spd: shown,
            brg,
            alt: altitude_at(elev, manual, frac),
            pct: (frac * 100.0) as i32,
        });
    }
    let end = points[points.len() - 1];
    out.push(Tick { lat: end.0, lng: end.1, spd: 0.0, brg, alt: altitude_at(elev, manual, 1.0), pct: 100 });
    out
}

pub fn format_line(out: &mut String, t: &Tick) -> usize {
    let before = out.len();
    let _ = writeln!(out, "{:11.6} {:11.6} {:7.3} {:6.2} {:8.2}", t.lat, t.lng, t.spd as f64, t.brg as f64, t.alt as f64);
    out.len() - before
}

pub fn format(ticks: &[Tick]) -> String {
    let mut s = String::with_capacity(ticks.len() * LINE_WIDTH);
    for t in ticks {
        format_line(&mut s, t);
    }
    s
}

pub fn uniform_width(ticks: &[Tick]) -> bool {
    let mut s = String::with_capacity(LINE_WIDTH + 8);
    ticks.iter().all(|t| {
        s.clear();
        format_line(&mut s, t) == LINE_WIDTH
    })
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Cursor {
    pub idx: i64,
    pub ended: bool,
    pub elapsed_ms: i64,
}

pub fn cursor(now_cs: i64, t0_cs: i64, tick_ms: i64, count: i64) -> Cursor {
    let el = (now_cs - t0_cs) * 10;
    let mut idx = if el < 0 { 1 } else { el / tick_ms.max(1) + 1 };
    let ended = idx >= count;
    if ended {
        idx = count;
    }
    Cursor { idx, ended, elapsed_ms: el }
}

pub fn read_tokens(path: &Path, width: usize, idx: i64) -> Option<Vec<String>> {
    if idx < 1 {
        return None;
    }
    let mut f = File::open(path).ok()?;
    let line = if width > 0 {
        let mut buf = vec![0u8; width];
        f.seek(SeekFrom::Start((idx as u64 - 1) * width as u64)).ok()?;
        let n = f.read(&mut buf).ok()?;
        String::from_utf8_lossy(&buf[..n]).into_owned()
    } else {
        let mut all = String::new();
        f.read_to_string(&mut all).ok()?;
        all.lines().nth(idx as usize - 1)?.to_string()
    };
    Some(line.split_whitespace().map(String::from).collect())
}

pub fn read_window(path: &Path, width: usize, start: i64, count: i64) -> Vec<u8> {
    if start < 1 || count < 1 {
        return Vec::new();
    }
    let mut f = match File::open(path) {
        Ok(f) => f,
        Err(_) => return Vec::new(),
    };
    if f.seek(SeekFrom::Start((start as u64 - 1) * width as u64)).is_err() {
        return Vec::new();
    }
    let mut buf = Vec::new();
    let _ = f.take(count as u64 * width as u64).read_to_end(&mut buf);
    buf
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_file(tag: &str, body: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("routecore-{}-{}", tag, std::process::id()));
        std::fs::write(&p, body).unwrap();
        p
    }

    #[test]
    fn cursor_clamps_and_marks_end() {
        assert_eq!(cursor(1000, 1000, 500, 4), Cursor { idx: 1, ended: false, elapsed_ms: 0 });
        assert_eq!(cursor(900, 1000, 500, 4), Cursor { idx: 1, ended: false, elapsed_ms: -1000 });
        assert_eq!(cursor(1100, 1000, 500, 4), Cursor { idx: 3, ended: false, elapsed_ms: 1000 });
        assert_eq!(cursor(1150, 1000, 500, 4), Cursor { idx: 4, ended: true, elapsed_ms: 1500 });
        assert_eq!(cursor(9000, 1000, 500, 4), Cursor { idx: 4, ended: true, elapsed_ms: 80000 });
    }

    #[test]
    fn reader_matches_writer() {
        let pts = [(-7.7, 110.4), (-7.69, 110.41), (-7.68, 110.40)];
        let ticks = build(&pts, 40.0, -1.0, &[10.0, 20.0], 0.0, &mut Rng::new(5));
        let p = temp_file("rw", &format(&ticks));
        for k in [0usize, 1, ticks.len() / 2, ticks.len() - 1] {
            let fixed = read_tokens(&p, LINE_WIDTH, k as i64 + 1).unwrap();
            let lined = read_tokens(&p, 0, k as i64 + 1).unwrap();
            assert_eq!(fixed, lined);
            assert_eq!(fixed.len(), 5);
            assert!((fixed[0].parse::<f64>().unwrap() - ticks[k].lat).abs() < 1e-6);
        }
        assert!(read_tokens(&p, LINE_WIDTH, 0).is_none());
        assert!(read_tokens(&p, LINE_WIDTH, ticks.len() as i64 + 1).map_or(true, |t| t.is_empty()));
        let w = read_window(&p, LINE_WIDTH, 2, 3);
        assert_eq!(w.len(), 3 * LINE_WIDTH);
        assert_eq!(w, format(&ticks[1..4]).into_bytes());
        assert!(read_window(&p, LINE_WIDTH, 0, 3).is_empty());
        let _ = std::fs::remove_file(&p);
    }


    const ORIGIN: Pt = (-7.7, 110.4);

    fn offset(p: Pt, m: f64, brg: f64) -> Pt {
        (
            p.0 + m * brg.to_radians().cos() / 111320.0,
            p.1 + m * brg.to_radians().sin() / (111320.0 * p.0.to_radians().cos()),
        )
    }

    fn tl(pts: &[Pt], kmh: f32, var: f32) -> Vec<Tick> {
        build(pts, kmh, var, &[0.0, 0.0], 0.0, &mut Rng::new(12345))
    }

    fn near(t: &[Tick], p: Pt) -> Tick {
        *t.iter().min_by(|a, b| meters((a.lat, a.lng), p).partial_cmp(&meters((b.lat, b.lng), p)).unwrap()).unwrap()
    }

    #[test]
    fn starts_and_stops_at_zero_speed() {
        let t = tl(&[ORIGIN, offset(ORIGIN, 1000.0, 0.0)], 50.0, -1.0);
        assert_eq!(t[0].spd, 0.0);
        assert_eq!(t.last().unwrap().spd, 0.0);
        assert_eq!(t.last().unwrap().pct, 100);
    }

    #[test]
    fn accel_and_brake_bounded() {
        let a = offset(ORIGIN, 1000.0, 0.0);
        let b = offset(a, 1000.0, 90.0);
        let t = tl(&[ORIGIN, a, b], 50.0, -1.0);
        for i in 1..t.len() {
            assert!((t[i].spd - t[i - 1].spd).abs() / 0.5 <= 3.2);
        }
    }

    #[test]
    fn sharp_corner_slows_vehicle() {
        let a = offset(ORIGIN, 500.0, 0.0);
        let b = offset(a, 500.0, 170.0);
        let t = tl(&[ORIGIN, a, b], 50.0, 0.0);
        assert!(near(&t, a).spd < 6.0);
    }

    #[test]
    fn walking_ends_at_destination() {
        let a = offset(ORIGIN, 200.0, 0.0);
        let b = offset(a, 200.0, 90.0);
        let t = tl(&[ORIGIN, a, b], 5.0, 0.0);
        assert!(t.iter().map(|x| x.spd).fold(0.0, f32::max) < 1.6);
        let l = t.last().unwrap();
        assert!(meters((l.lat, l.lng), b) < 1.0);
    }

    #[test]
    fn very_low_speed_not_exceeded() {
        let t = tl(&[ORIGIN, offset(ORIGIN, 30.0, 0.0)], 0.5, 0.0);
        assert!(t.iter().map(|x| x.spd).fold(0.0, f32::max) <= 0.5 / 3.6 + 1e-4);
        assert_eq!(t.last().unwrap().spd, 0.0);
    }

    #[test]
    fn identical_points_two_ticks() {
        assert_eq!(tl(&[ORIGIN, ORIGIN], 5.0, -1.0).len(), 2);
    }

    #[test]
    fn braking_bounded_on_many_routes() {
        let a = offset(ORIGIN, 400.0, 0.0);
        let b = offset(a, 300.0, 100.0);
        let c = offset(b, 150.0, 200.0);
        let pts = [ORIGIN, a, b, c];
        for seed in 0..200u64 {
            let t = build(&pts, 110.0, -1.0, &[0.0, 0.0], 0.0, &mut Rng::new(seed));
            for i in 1..t.len() {
                assert!((t[i].spd - t[i - 1].spd).abs() / 0.5 <= 2.6);
            }
        }
    }

    #[test]
    fn dense_noisy_polyline_keeps_target_speed() {
        let mut pts = vec![ORIGIN];
        for i in 1..=1500 {
            let q = offset(ORIGIN, 3.0 * i as f64, 35.0);
            pts.push(((q.0 * 1e5).round() / 1e5, (q.1 * 1e5).round() / 1e5));
        }
        let t = tl(&pts, 50.0, 0.0);
        let mid = &t[t.len() / 4..3 * t.len() / 4];
        let mean = mid.iter().map(|x| x.spd as f64).sum::<f64>() / mid.len() as f64;
        assert!(mean >= 0.97 * 50.0 / 3.6);
    }

    #[test]
    fn right_angle_on_dense_polyline_slows() {
        let mut pts = vec![ORIGIN];
        let mut p = ORIGIN;
        for _ in 0..100 {
            p = offset(p, 3.0, 0.0);
            pts.push(p);
        }
        let corner = p;
        for _ in 0..100 {
            p = offset(p, 3.0, 90.0);
            pts.push(p);
        }
        let t = tl(&pts, 50.0, 0.0);
        assert!(near(&t, corner).spd < 11.0);
    }

    #[test]
    fn lines_fixed_width_even_for_extremes() {
        let routes: [Vec<Pt>; 2] = [
            vec![(-89.9, -179.5), (-89.8, -179.4)],
            vec![(60.1, 170.2), (60.2, 170.4), (60.25, 171.0)],
        ];
        for pts in routes.iter() {
            let t = build(pts, 40.0, -1.0, &[-3.0, 1234.5, 8848.0], 0.0, &mut Rng::new(7));
            let s = format(&t);
            assert_eq!(s.len(), t.len() * LINE_WIDTH);
            let lines: Vec<&str> = s.split('\n').collect();
            assert_eq!(lines.len(), t.len() + 1);
            assert!(lines[..t.len()].iter().all(|l| l.len() == LINE_WIDTH - 1));
            assert!(uniform_width(&t));
        }
    }

    #[test]
    fn altitude_interpolated_along_route() {
        let a = (-7.7, 110.4);
        let b = (-7.7 + 1000.0 / 111320.0, 110.4);
        let t = build(&[a, b], 36.0, 0.0, &[0.0, 100.0, 0.0], 0.0, &mut Rng::new(1));
        assert!(t[0].alt.abs() < 0.01 && t.last().unwrap().alt.abs() < 0.01);
        let at = |p: i32| t.iter().find(|x| x.pct == p).unwrap().alt;
        assert!((at(25) - 50.0).abs() <= 3.0);
        assert!((at(50) - 100.0).abs() <= 4.0);
        assert!((at(75) - 50.0).abs() <= 3.0);
    }

    #[test]
    fn manual_altitude_without_elevations() {
        let t = build(&[ORIGIN, offset(ORIGIN, 100.0, 0.0)], 20.0, 0.0, &[], 42.5, &mut Rng::new(1));
        assert!(t.iter().all(|x| x.alt == 42.5));
    }

    #[test]
    fn progress_monotonic_and_ends_at_100() {
        let t = tl(&[(-7.7, 110.4), (-7.69, 110.41), (-7.68, 110.40)], 30.0, -1.0);
        for i in 1..t.len() {
            assert!(t[i].pct >= t[i - 1].pct);
        }
        assert!(t.iter().all(|x| (0..=100).contains(&x.pct)));
        assert_eq!(t.last().unwrap().pct, 100);
    }

    #[test]
    fn distance_accurate_near_and_far() {
        assert!((meters((0.0, 0.0), (1.0, 0.0)) - 111195.0).abs() < 300.0);
        let a: Pt = (45.0, 7.0);
        for d in [0.0005, 0.2, 0.55, 0.58, 0.6, 3.0] {
            let b: Pt = (45.0, 7.0 + d);
            let (dl, dg) = ((b.0 - a.0).to_radians(), (b.1 - a.1).to_radians());
            let h = (dl / 2.0).sin().powi(2) + a.0.to_radians().cos() * b.0.to_radians().cos() * (dg / 2.0).sin().powi(2);
            let r = 2.0 * 6371000.0 * h.sqrt().asin();
            assert!((meters(a, b) - r).abs() <= r * 0.001 + 0.05);
        }
    }

    #[test]
    fn same_seed_same_timeline() {
        let pts = [ORIGIN, offset(ORIGIN, 800.0, 40.0)];
        let a = build(&pts, 50.0, -1.0, &[], 0.0, &mut Rng::new(99));
        let b = build(&pts, 50.0, -1.0, &[], 0.0, &mut Rng::new(99));
        assert_eq!(a, b);
    }

    #[test]
    fn too_long_flags_huge_routes() {
        assert!(!too_long(&[ORIGIN, offset(ORIGIN, 1000.0, 0.0)], 50.0));
        assert!(too_long(&[ORIGIN, offset(ORIGIN, 5_000_000.0, 0.0)], 5.0));
    }

    #[test]
    fn lerp_lng_crosses_antimeridian() {
        assert!((lerp_lng(179.0, -179.0, 0.5).abs() - 180.0).abs() < 1e-9);
    }

    #[test]
    fn route_across_antimeridian_does_not_jump_to_lng_zero() {
        let pts = vec![(-17.0, 179.995), (-17.0, -179.995)];
        let t = tl(&pts, 40.0, 0.0);
        assert!(t.len() > 2);
        assert!(t.iter().all(|x| x.lng.abs() > 179.9));
        for w in t.windows(2) {
            assert!(meters((w[0].lat, w[0].lng), (w[1].lat, w[1].lng)) < 20.0);
        }
    }
}
