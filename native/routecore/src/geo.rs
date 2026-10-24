pub type Pt = (f64, f64);

const EARTH_R: f64 = 6371000.0;
const NEAR_RAD: f64 = 0.01;

pub fn bearing(a: Pt, b: Pt) -> f32 {
    let la1 = a.0.to_radians();
    let la2 = b.0.to_radians();
    let d_lng = (b.1 - a.1).to_radians();
    let y = d_lng.sin() * la2.cos();
    let x = la1.cos() * la2.sin() - la1.sin() * la2.cos() * d_lng.cos();
    let deg = y.atan2(x).to_degrees();
    (((deg % 360.0) + 360.0) % 360.0) as f32
}

pub fn meters(a: Pt, b: Pt) -> f64 {
    let d_lat = (b.0 - a.0).to_radians();
    let d_lng = (b.1 - a.1).to_radians();
    if d_lat.abs() < NEAR_RAD && d_lng.abs() < NEAR_RAD {
        let x = d_lng * ((a.0 + b.0) * 0.5).to_radians().cos();
        return EARTH_R * (d_lat * d_lat + x * x).sqrt();
    }
    let h = (d_lat / 2.0).sin() * (d_lat / 2.0).sin()
        + a.0.to_radians().cos() * b.0.to_radians().cos() * (d_lng / 2.0).sin() * (d_lng / 2.0).sin();
    2.0 * EARTH_R * h.sqrt().atan2((1.0 - h).sqrt())
}

pub fn lerp_lng(a: f64, b: f64, f: f64) -> f64 {
    let mut d = b - a;
    if d > 180.0 {
        d -= 360.0;
    } else if d < -180.0 {
        d += 360.0;
    }
    let mut x = a + d * f;
    if x > 180.0 {
        x -= 360.0;
    } else if x < -180.0 {
        x += 360.0;
    }
    x
}

pub fn route_length(p: &[Pt]) -> f64 {
    let mut t = 0.0;
    for i in 1..p.len() {
        t += meters(p[i - 1], p[i]);
    }
    t
}

pub fn sample(points: &[Pt], n: i32, max: i32) -> Vec<Pt> {
    if points.len() < 2 {
        return points.to_vec();
    }
    let cnt = n.clamp(2, max.max(2)) as usize;
    let mut cum = vec![0.0f64; points.len()];
    for i in 1..points.len() {
        cum[i] = cum[i - 1] + meters(points[i - 1], points[i]);
    }
    let total = cum[cum.len() - 1];
    if total <= 0.0 {
        return vec![points[0], points[points.len() - 1]];
    }
    let mut out = Vec::with_capacity(cnt);
    let mut seg = 1usize;
    for k in 0..cnt {
        let d = total * k as f64 / (cnt - 1) as f64;
        while seg < cum.len() - 1 && cum[seg] < d {
            seg += 1;
        }
        let len = cum[seg] - cum[seg - 1];
        let f = if len <= 0.0 { 1.0 } else { ((d - cum[seg - 1]) / len).clamp(0.0, 1.0) };
        let a = points[seg - 1];
        let b = points[seg];
        out.push((a.0 + (b.0 - a.0) * f, lerp_lng(a.1, b.1, f)));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const MAX: i32 = 100;

    #[test]
    fn fewer_than_two_points_returned_as_is() {
        assert!(sample(&[], 10, MAX).is_empty());
        assert_eq!(sample(&[(1.0, 2.0)], 10, MAX), vec![(1.0, 2.0)]);
    }

    #[test]
    fn count_is_clamped() {
        let line = [(0.0, 0.0), (0.0, 0.01)];
        assert_eq!(sample(&line, 1, MAX).len(), 2);
        assert_eq!(sample(&line, 0, MAX).len(), 2);
        assert_eq!(sample(&line, -5, MAX).len(), 2);
        assert_eq!(sample(&line, 1000, MAX).len(), 100);
        assert_eq!(sample(&line, 7, MAX).len(), 7);
    }

    #[test]
    fn endpoints_are_kept() {
        let line = [(0.0, 0.0), (0.0, 0.005), (0.002, 0.01)];
        let s = sample(&line, 9, MAX);
        assert!((s[0].0 - line[0].0).abs() < 1e-9 && (s[0].1 - line[0].1).abs() < 1e-9);
        let l = s[s.len() - 1];
        assert!((l.0 - 0.002).abs() < 1e-6 && (l.1 - 0.01).abs() < 1e-6);
    }

    #[test]
    fn straight_line_is_sampled_evenly() {
        let s = sample(&[(0.0, 0.0), (0.0, 0.01)], 3, MAX);
        assert!((s[1].1 - 0.005).abs() < 1e-6);
        assert!(s[1].0.abs() < 1e-9);
    }

    #[test]
    fn identical_points_give_two_points() {
        let p = (-7.7, 110.4);
        let s = sample(&[p, p, p], 10, MAX);
        assert_eq!(s.len(), 2);
        assert_eq!(s[0], p);
        assert_eq!(s[1], p);
    }

    #[test]
    fn crossing_antimeridian_stays_near_180() {
        let s = sample(&[(0.0, 179.99), (0.0, -179.99)], 3, MAX);
        assert!((s[1].1.abs() - 180.0).abs() < 1e-6);
        assert!(s.iter().all(|p| (-180.0..=180.0).contains(&p.1)));
    }

    #[test]
    fn route_length_sums_segments() {
        let l = route_length(&[(0.0, 0.0), (1.0, 0.0), (2.0, 0.0)]);
        assert!((l - 2.0 * 111195.0).abs() < 600.0);
        assert_eq!(route_length(&[(1.0, 1.0)]), 0.0);
        assert_eq!(route_length(&[]), 0.0);
    }
}
