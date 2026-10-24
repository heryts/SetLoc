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
