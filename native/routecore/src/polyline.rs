fn units(s: &str) -> Vec<i32> {
    s.encode_utf16().map(|u| u as i32 - 63).collect()
}

pub fn is_complete(encoded: &str) -> bool {
    let u = units(encoded);
    match u.last() {
        None => false,
        Some(&last) => u.iter().filter(|&&b| b < 0x20).count() % 2 == 0 && last < 0x20,
    }
}

fn read_value(u: &[i32], index: &mut usize) -> Option<i32> {
    let mut result: i32 = 0;
    let mut shift: u32 = 0;
    loop {
        let b = u[*index];
        *index += 1;
        result |= (b & 0x1f).wrapping_shl(shift);
        shift += 5;
        if !(b >= 0x20 && *index < u.len()) {
            return if b >= 0x20 { None } else { Some(if result & 1 != 0 { !(result >> 1) } else { result >> 1 }) };
        }
    }
}

pub fn decode(encoded: &str) -> Vec<(f64, f64)> {
    let u = units(encoded);
    let mut out = Vec::with_capacity(u.len() / 4);
    let mut index = 0usize;
    let mut lat: i32 = 0;
    let mut lng: i32 = 0;
    while index < u.len() {
        match read_value(&u, &mut index) {
            Some(d) => lat = lat.wrapping_add(d),
            None => break,
        }
        if index >= u.len() {
            break;
        }
        match read_value(&u, &mut index) {
            Some(d) => lng = lng.wrapping_add(d),
            None => break,
        }
        out.push((lat as f64 / 1e5, lng as f64 / 1e5));
    }
    out
}

fn round5(x: f64) -> i32 {
    ((x * 1e5 + 0.5).floor() as i64) as i32
}

fn encode_value(v: i32, out: &mut String) {
    let mut x = if v < 0 { !(v.wrapping_shl(1)) } else { v.wrapping_shl(1) };
    while x >= 0x20 {
        out.push(char::from_u32((((x & 0x1f) | 0x20) + 63) as u32).unwrap_or('?'));
        x = ((x as u32) >> 5) as i32;
    }
    out.push(char::from_u32((x + 63) as u32).unwrap_or('?'));
}

pub fn encode(points: &[(f64, f64)]) -> String {
    let mut out = String::new();
    let mut p_lat = 0i32;
    let mut p_lng = 0i32;
    for &(la, ln) in points {
        let la = round5(la);
        let ln = round5(ln);
        encode_value(la.wrapping_sub(p_lat), &mut out);
        encode_value(ln.wrapping_sub(p_lng), &mut out);
        p_lat = la;
        p_lng = ln;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const GOOGLE: &str = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";

    #[test]
    fn google_sample_decodes() {
        let p = decode(GOOGLE);
        assert_eq!(p.len(), 3);
        let want = [(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)];
        for (g, w) in p.iter().zip(want.iter()) {
            assert!((g.0 - w.0).abs() < 1e-9 && (g.1 - w.1).abs() < 1e-9, "{:?} vs {:?}", g, w);
        }
    }

    #[test]
    fn empty_gives_empty() {
        assert!(decode("").is_empty());
    }

    #[test]
    fn truncated_never_panics() {
        assert_eq!(decode("_p~iF~ps|U_").len(), 1);
        assert!(decode("_p~iF").is_empty());
        assert!(decode("?").is_empty());
        assert_eq!(decode("??").len(), 1);
        assert_eq!(decode(&GOOGLE[..GOOGLE.len() - 1]).len(), 2);
    }

    #[test]
    fn is_complete_tells_full_from_truncated() {
        assert!(is_complete(GOOGLE));
        assert!(is_complete("_p~iF~ps|U"));
        assert!(!is_complete(&GOOGLE[..GOOGLE.len() - 1]));
        assert!(!is_complete("_p~iF~ps|U_"));
        assert!(!is_complete("_p~iF"));
        assert!(!is_complete(""));
    }

    #[test]
    fn encode_matches_google_sample() {
        assert_eq!(encode(&[(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)]), GOOGLE);
    }

    #[test]
    fn encode_decode_roundtrip_incl_extremes() {
        let pts = [(0.0, 0.0), (-90.0, 180.0), (90.0, -180.0), (-7.25001, 110.12345), (-0.00001, 0.00001)];
        let back = decode(&encode(&pts));
        assert_eq!(back.len(), pts.len());
        for (a, b) in pts.iter().zip(back.iter()) {
            assert!((a.0 - b.0).abs() < 1e-5 && (a.1 - b.1).abs() < 1e-5);
        }
    }

    #[test]
    fn garbage_and_non_ascii_never_panic() {
        for s in ["\u{0}", "\u{ffff}", "\u{1f600}", "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~", " ", "\n\n"] {
            let _ = decode(s);
            let _ = is_complete(s);
        }
        let _ = encode(&[(f64::NAN, f64::INFINITY), (1e300, -1e300)]);
    }
}
