const DEFAULT: [f32; 4] = [5.0, 15.0, 40.0, 50.0];
const MIN: [f32; 4] = [3.0, 8.0, 15.0, 10.0];
const MAX: [f32; 4] = [7.0, 25.0, 70.0, 110.0];

pub fn auto_kmh(mode: i32, distance_m: f64, duration_s: Option<f64>) -> f32 {
    let m = mode.clamp(0, DEFAULT.len() as i32 - 1) as usize;
    if let Some(d) = duration_s {
        if d.is_finite() && distance_m.is_finite() && d > 30.0 && distance_m > 100.0 {
            let v = ((distance_m / d * 3.6) as f32).clamp(MIN[m], MAX[m]);
            return (v * 10.0).round() / 10.0;
        }
    }
    DEFAULT[m]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn without_duration_uses_mode_default() {
        assert_eq!(auto_kmh(0, 1000.0, None), 5.0);
        assert_eq!(auto_kmh(1, 1000.0, None), 15.0);
        assert_eq!(auto_kmh(2, 1000.0, None), 40.0);
        assert_eq!(auto_kmh(3, 1000.0, None), 50.0);
    }

    #[test]
    fn mode_is_clamped() {
        assert_eq!(auto_kmh(-1, 1000.0, None), 5.0);
        assert_eq!(auto_kmh(9, 1000.0, None), 50.0);
    }

    #[test]
    fn short_duration_or_distance_uses_default() {
        assert_eq!(auto_kmh(3, 5000.0, Some(30.0)), 50.0);
        assert_eq!(auto_kmh(3, 100.0, Some(600.0)), 50.0);
    }

    #[test]
    fn non_finite_input_uses_default() {
        assert_eq!(auto_kmh(3, f64::INFINITY, Some(600.0)), 50.0);
        assert_eq!(auto_kmh(3, 5000.0, Some(f64::INFINITY)), 50.0);
        assert_eq!(auto_kmh(3, f64::NAN, Some(600.0)), 50.0);
        assert_eq!(auto_kmh(3, 5000.0, Some(f64::NAN)), 50.0);
    }

    #[test]
    fn speed_from_distance_and_duration() {
        assert_eq!(auto_kmh(3, 10000.0, Some(600.0)), 60.0);
        assert_eq!(auto_kmh(3, 1234.0, Some(200.0)), 22.2);
    }

    #[test]
    fn result_is_clamped_to_mode_bounds() {
        assert_eq!(auto_kmh(0, 5000.0, Some(100.0)), 7.0);
        assert_eq!(auto_kmh(3, 200.0, Some(100.0)), 10.0);
        assert_eq!(auto_kmh(3, 50000.0, Some(600.0)), 110.0);
    }
}
