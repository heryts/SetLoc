use crate::jfmt::fixed;

pub fn format_distance(m: f64) -> String {
    if m < 1000.0 {
        format!("{} m", m as i32)
    } else {
        format!("{} km", fixed(m / 1000.0, 1))
    }
}

pub fn format_duration(sec: f64) -> String {
    let s = sec as i64;
    if s < 60 {
        return format!("{} s", s);
    }
    let m = s.wrapping_add(30) / 60;
    if m < 60 {
        format!("{} min", m)
    } else {
        format!("{} h {} min", m / 60, m % 60)
    }
}

pub fn coords_label(lat: f64, lon: f64) -> String {
    format!("{}, {}", fixed(lat, 5), fixed(lon, 5))
}

pub fn address_text(line: Option<&str>, lat: f64, lon: f64) -> String {
    let a = match line {
        Some(a) if !a.is_empty() => a,
        _ => return coords_label(lat, lon),
    };
    let (head, rest) = match a.split_once(',') {
        Some((h, r)) => (h, r.trim_start()),
        None => (a, ""),
    };
    if rest.is_empty() {
        head.to_string()
    } else {
        format!("{}\n{}", head, rest)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn duration_rounds_to_minutes_without_jumping_to_60_min() {
        assert_eq!(format_duration(45.0), "45 s");
        assert_eq!(format_duration(60.0), "1 min");
        assert_eq!(format_duration(3569.0), "59 min");
        assert_eq!(format_duration(3570.0), "1 h 0 min");
        assert_eq!(format_duration(3599.0), "1 h 0 min");
        assert_eq!(format_duration(3600.0), "1 h 0 min");
        assert_eq!(format_duration(3660.0), "1 h 1 min");
        assert_eq!(format_duration(4250.0), "1 h 11 min");
        assert_eq!(format_duration(7170.0), "2 h 0 min");
        assert_eq!(format_duration(9000.0), "2 h 30 min");
    }

    #[test]
    fn duration_edge_values() {
        assert_eq!(format_duration(0.0), "0 s");
        assert_eq!(format_duration(59.9), "59 s");
        assert_eq!(format_duration(-5.0), "-5 s");
        assert_eq!(format_duration(f64::NAN), "0 s");
        assert_eq!(format_duration(89.0), "1 min");
        assert_eq!(format_duration(90.0), "2 min");
        let _ = format_duration(f64::INFINITY);
    }

    #[test]
    fn distance_meters_and_kilometers() {
        assert_eq!(format_distance(999.7), "999 m");
        assert_eq!(format_distance(1000.0), "1.0 km");
        assert_eq!(format_distance(12345.0), "12.3 km");
    }

    #[test]
    fn distance_edge_values() {
        assert_eq!(format_distance(0.0), "0 m");
        assert_eq!(format_distance(-3.9), "-3 m");
        assert_eq!(format_distance(12250.0), "12.3 km");
        assert_eq!(format_distance(1049.0), "1.0 km");
        assert_eq!(format_distance(1050.0), "1.1 km");
        assert_eq!(format_distance(f64::NAN), "NaN km");
    }

    #[test]
    fn coords_label_has_five_decimals() {
        assert_eq!(coords_label(-7.8, 110.36), "-7.80000, 110.36000");
        assert_eq!(coords_label(0.0, 0.0), "0.00000, 0.00000");
    }

    #[test]
    fn address_splits_first_line() {
        assert_eq!(address_text(Some("Jl. Malioboro, Yogyakarta, Indonesia"), 0.0, 0.0), "Jl. Malioboro\nYogyakarta, Indonesia");
        assert_eq!(address_text(Some("Tugu"), 0.0, 0.0), "Tugu");
        assert_eq!(address_text(Some("Tugu,"), 0.0, 0.0), "Tugu");
        assert_eq!(address_text(Some("Tugu,   "), 0.0, 0.0), "Tugu");
        assert_eq!(address_text(Some("A,   B"), 0.0, 0.0), "A\nB");
        assert_eq!(address_text(Some(",B"), 0.0, 0.0), "\nB");
    }

    #[test]
    fn address_falls_back_to_coordinates() {
        assert_eq!(address_text(None, -7.8, 110.36), "-7.80000, 110.36000");
        assert_eq!(address_text(Some(""), 1.5, 2.5), "1.50000, 2.50000");
    }
}
