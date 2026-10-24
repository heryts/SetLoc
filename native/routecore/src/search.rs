use crate::net::{encode_places, Place, KIND_NOMINATIM, KIND_PHOTON};
use crate::jfmt::form_encode;
use crate::text::coords_label;

const PHOTON_URL: &str = "https://photon.komoot.io/api/?limit=5&q=";
const NOMINATIM_URL: &str = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=";

pub const NO_SOURCE: i32 = -1;

pub enum Query {
    Text(String),
    Places(Vec<Place>),
}

fn digits(b: &[u8], mut i: usize, min: usize, max: usize) -> Option<usize> {
    let start = i;
    while i < b.len() && i - start < max && b[i].is_ascii_digit() {
        i += 1;
    }
    if i - start >= min {
        Some(i)
    } else {
        None
    }
}

fn number(b: &[u8], mut i: usize) -> Option<usize> {
    if i < b.len() && (b[i] == b'-' || b[i] == b'+') {
        i += 1;
    }
    i = digits(b, i, 1, 3)?;
    if i < b.len() && b[i] == b'.' {
        i = digits(b, i + 1, 1, usize::MAX)?;
    }
    Some(i)
}

pub fn is_coords_shaped(text: &str) -> bool {
    let b = text.as_bytes();
    let mut i = match number(b, 0) {
        Some(i) => i,
        None => return false,
    };
    if i >= b.len() || b[i] != b',' {
        return false;
    }
    i += 1;
    while i < b.len() && b[i] == b' ' {
        i += 1;
    }
    matches!(number(b, i), Some(end) if end == b.len())
}

pub fn parse_coords(text: &str) -> Option<(f64, f64)> {
    let mut parts = text.split(',');
    let (a, b) = (parts.next()?, parts.next()?);
    if parts.next().is_some() {
        return None;
    }
    let la = a.trim().parse::<f64>().ok()?;
    let lo = b.trim().parse::<f64>().ok()?;
    if (-90.0..=90.0).contains(&la) && (-180.0..=180.0).contains(&lo) {
        Some((la, lo))
    } else {
        None
    }
}

pub fn query(input: &str) -> Query {
    let text = input.trim();
    if is_coords_shaped(text) {
        return Query::Places(match parse_coords(text) {
            Some((lat, lon)) => vec![Place { label: coords_label(lat, lon), lat, lon }],
            None => Vec::new(),
        });
    }
    Query::Text(text.to_string())
}

pub fn encode_query(q: &Query) -> Vec<u8> {
    match q {
        Query::Text(t) => {
            let mut out = vec![0u8];
            out.extend_from_slice(&(t.len() as i32).to_be_bytes());
            out.extend_from_slice(t.as_bytes());
            out
        }
        Query::Places(p) => {
            let mut out = vec![1u8];
            out.extend_from_slice(&encode_places(p));
            out
        }
    }
}

pub fn url(kind: i32, text: &str) -> Option<String> {
    let base = match kind {
        KIND_PHOTON => PHOTON_URL,
        KIND_NOMINATIM => NOMINATIM_URL,
        _ => return None,
    };
    Some(format!("{}{}", base, form_encode(text)))
}

pub fn next_source(kind: i32) -> i32 {
    match kind {
        NO_SOURCE => KIND_PHOTON,
        KIND_PHOTON => KIND_NOMINATIM,
        _ => NO_SOURCE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn labels(q: Query) -> Vec<(String, f64, f64)> {
        match q {
            Query::Places(p) => p.into_iter().map(|p| (p.label, p.lat, p.lon)).collect(),
            Query::Text(t) => panic!("text query: {}", t),
        }
    }

    #[test]
    fn coords_normal_with_and_without_spaces() {
        assert_eq!(parse_coords("1.5, 2.5"), Some((1.5, 2.5)));
        assert_eq!(parse_coords("-7.7,110.4"), Some((-7.7, 110.4)));
        assert_eq!(parse_coords(" 10 ,  -20 "), Some((10.0, -20.0)));
    }

    #[test]
    fn coords_exact_bounds_accepted() {
        assert_eq!(parse_coords("90,180"), Some((90.0, 180.0)));
        assert_eq!(parse_coords("-90,-180"), Some((-90.0, -180.0)));
    }

    #[test]
    fn coords_out_of_range_rejected() {
        for t in ["90.1,0", "-90.1,0", "0,180.1", "0,-180.1", "91,181"] {
            assert_eq!(parse_coords(t), None, "{}", t);
        }
    }

    #[test]
    fn coords_not_numbers_rejected() {
        for t in ["abc,def", "1,def", "abc,1", "NaN,1", "1,Infinity", "nan,1", "inf,1", ",5", "5,"] {
            assert_eq!(parse_coords(t), None, "{}", t);
        }
    }

    #[test]
    fn coords_wrong_part_count_does_not_panic() {
        for t in ["", "1", "1.5", "1,2,3", ","] {
            assert_eq!(parse_coords(t), None, "{}", t);
        }
    }

    #[test]
    fn shape_matches_the_old_regex() {
        for t in ["1,2", "1.5, 2.5", "-7.7,110.4", "+1,+2", "123,456", "1.5,   2", "0.000001,0.000001", "-90,-180"] {
            assert!(is_coords_shaped(t), "{}", t);
        }
        for t in [
            "", "1", "1,", ",1", "1 ,2", "1, -", "1234,5", "1,2345", "1.,2", ".5,2", "1,2.", "1,2 ", " 1,2", "1,2,3", "1;2", "a,b", "1,\t2", "--1,2",
            "1.5e3,2", "１,２",
        ] {
            assert!(!is_coords_shaped(t), "{:?}", t);
        }
    }

    #[test]
    fn query_with_valid_coords_returns_one_place() {
        assert_eq!(labels(query(" -7.8, 110.36 ")), vec![("-7.80000, 110.36000".to_string(), -7.8, 110.36)]);
    }

    #[test]
    fn query_with_out_of_range_coords_returns_nothing() {
        assert_eq!(labels(query("999,999")), vec![]);
        assert_eq!(labels(query("91,0")), vec![]);
    }

    #[test]
    fn query_text_is_trimmed() {
        match query("  Tugu Jogja \n") {
            Query::Text(t) => assert_eq!(t, "Tugu Jogja"),
            _ => panic!(),
        }
        match query("1,2,3") {
            Query::Text(t) => assert_eq!(t, "1,2,3"),
            _ => panic!(),
        }
    }

    #[test]
    fn encoded_query_layout() {
        assert_eq!(encode_query(&Query::Text("ab".into())), vec![0, 0, 0, 0, 2, b'a', b'b']);
        let b = encode_query(&query("1,2"));
        assert_eq!(b[0], 1);
        assert_eq!(i32::from_be_bytes(b[1..5].try_into().unwrap()), 1);
        let none = encode_query(&query("999,999"));
        assert_eq!(none, vec![1, 0, 0, 0, 0]);
    }

    #[test]
    fn urls_are_form_encoded() {
        assert_eq!(url(KIND_PHOTON, "Tugu Jogja & é").unwrap(), "https://photon.komoot.io/api/?limit=5&q=Tugu+Jogja+%26+%C3%A9");
        assert_eq!(
            url(KIND_NOMINATIM, "a,b").unwrap(),
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=a%2Cb"
        );
        assert_eq!(url(7, "x"), None);
    }

    #[test]
    fn fallback_order_is_photon_then_nominatim() {
        assert_eq!(next_source(NO_SOURCE), KIND_PHOTON);
        assert_eq!(next_source(KIND_PHOTON), KIND_NOMINATIM);
        assert_eq!(next_source(KIND_NOMINATIM), NO_SOURCE);
        assert_eq!(next_source(42), NO_SOURCE);
    }
}
