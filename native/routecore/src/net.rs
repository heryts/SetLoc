use crate::json::{self, Json};
use crate::store::{find, to_double};
use crate::text::coords_label;

pub const KIND_PHOTON: i32 = 0;
pub const KIND_NOMINATIM: i32 = 1;

type Obj = [(String, Json)];

fn text_of(o: &Obj, k: &str) -> String {
    match find(o, k) {
        Some(Json::Str(s)) => s.clone(),
        Some(Json::Num(n)) => match n.i {
            Some(i) => i.to_string(),
            None => n.f.to_string(),
        },
        Some(Json::Bool(b)) => b.to_string(),
        _ => String::new(),
    }
}

fn blank(s: &str) -> bool {
    s.trim().is_empty()
}

fn put_i32(v: &mut Vec<u8>, x: i32) {
    v.extend_from_slice(&x.to_be_bytes());
}

fn put_f64(v: &mut Vec<u8>, x: f64) {
    v.extend_from_slice(&x.to_bits().to_be_bytes());
}

fn put_text(v: &mut Vec<u8>, s: &str) {
    put_i32(v, s.len() as i32);
    v.extend_from_slice(s.as_bytes());
}

pub fn parse_route(text: &str) -> Option<Vec<u8>> {
    let root = json::parse(text)?;
    let top = root.as_obj()?;
    let route = find(top, "routes")
        .and_then(Json::as_arr)
        .and_then(|a| a.first())
        .and_then(Json::as_obj);
    let mut out = Vec::new();
    match route {
        None => out.push(0),
        Some(r) => {
            let encoded = find(r, "polyline")
                .and_then(Json::as_obj)
                .map(|p| text_of(p, "encodedPolyline"))
                .unwrap_or_default();
            let duration = text_of(r, "duration");
            let seconds = duration
                .strip_suffix('s')
                .unwrap_or(&duration)
                .parse::<f64>()
                .ok()
                .filter(|f| f.is_finite())
                .unwrap_or(0.0);
            out.push(1);
            put_f64(&mut out, seconds);
            put_text(&mut out, &encoded);
        }
    }
    Some(out)
}

pub fn error_message(text: &str) -> Option<String> {
    let root = json::parse(text)?;
    let e = find(root.as_obj()?, "error")?.as_obj()?;
    Some(text_of(e, "message"))
}

pub fn parse_elevation(text: &str, n: usize, google: bool) -> Option<Vec<f32>> {
    let root = json::parse(text)?;
    let o = root.as_obj()?;
    let arr = if google {
        match find(o, "status") {
            Some(Json::Str(s)) if s == "OK" => {}
            _ => return None,
        }
        find(o, "results")?.as_arr()?
    } else {
        find(o, "elevation")?.as_arr()?
    };
    if arr.len() != n {
        return None;
    }
    arr.iter()
        .map(|v| {
            let e = if google { find(v.as_obj()?, "elevation")? } else { v };
            to_double(e).map(|d| d as f32)
        })
        .collect()
}

pub struct Place {
    pub label: String,
    pub lat: f64,
    pub lon: f64,
}

fn photon(root: &Json, fallback: &str) -> Option<Vec<Place>> {
    let mut out = Vec::new();
    let features = match find(root.as_obj()?, "features").and_then(Json::as_arr) {
        Some(f) => f,
        None => return Some(out),
    };
    for v in features {
        let f = match v.as_obj() {
            Some(f) => f,
            None => continue,
        };
        let coords = match find(f, "geometry")
            .and_then(Json::as_obj)
            .and_then(|g| find(g, "coordinates"))
            .and_then(Json::as_arr)
        {
            Some(c) => c,
            None => continue,
        };
        let props = find(f, "properties").and_then(Json::as_obj);
        let prop = |k: &str| props.map(|p| text_of(p, k)).unwrap_or_default();
        let mut name = prop("name");
        if blank(&name) {
            name = fallback.to_string();
        }
        let mut city = prop("city");
        if blank(&city) {
            city = prop("county");
        }
        let mut parts = vec![name.clone()];
        for c in [city, prop("state"), prop("country")] {
            if !blank(&c) && c != name && !parts[1..].contains(&c) {
                parts.push(c);
            }
        }
        let lat = match coords.get(1).and_then(to_double) {
            Some(x) => x,
            None => continue,
        };
        let lon = match coords.first().and_then(to_double) {
            Some(x) => x,
            None => continue,
        };
        if !(-90.0..=90.0).contains(&lat) || !(-180.0..=180.0).contains(&lon) {
            continue;
        }
        out.push(Place { label: parts.join(", "), lat, lon });
    }
    Some(out)
}

fn nominatim(root: &Json) -> Option<Vec<Place>> {
    let mut out = Vec::new();
    for v in root.as_arr()? {
        let r = match v.as_obj() {
            Some(r) => r,
            None => continue,
        };
        let lat = match find(r, "lat").and_then(to_double) {
            Some(x) => x,
            None => continue,
        };
        let lon = match find(r, "lon").and_then(to_double) {
            Some(x) => x,
            None => continue,
        };
        out.push(Place { label: text_of(r, "display_name"), lat, lon });
    }
    Some(out)
}

pub fn encode_places(places: &[Place]) -> Vec<u8> {
    let mut out = Vec::new();
    put_i32(&mut out, places.len() as i32);
    for p in places {
        put_text(&mut out, &p.label);
        put_f64(&mut out, p.lat);
        put_f64(&mut out, p.lon);
    }
    out
}

pub fn distinct_by_label(places: Vec<Place>) -> Vec<Place> {
    let mut seen: Vec<String> = Vec::new();
    let mut out = Vec::with_capacity(places.len());
    for p in places {
        if !seen.contains(&p.label) {
            seen.push(p.label.clone());
            out.push(p);
        }
    }
    out
}

pub fn places_of(kind: i32, text: &str, fallback: &str) -> Option<Vec<Place>> {
    let root = json::parse(text)?;
    let places = match kind {
        KIND_PHOTON => photon(&root, fallback)?,
        KIND_NOMINATIM => {
            let mut v = nominatim(&root)?;
            for p in v.iter_mut() {
                if blank(&p.label) {
                    p.label = coords_label(p.lat, p.lon);
                }
            }
            v
        }
        _ => return None,
    };
    Some(distinct_by_label(places))
}

pub fn parse_places(kind: i32, text: &str, fallback: &str) -> Option<Vec<u8>> {
    places_of(kind, text, fallback).map(|p| encode_places(&p))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn route_fields(b: &[u8]) -> (f64, String) {
        assert_eq!(b[0], 1);
        let d = f64::from_bits(u64::from_be_bytes(b[1..9].try_into().unwrap()));
        let n = i32::from_be_bytes(b[9..13].try_into().unwrap()) as usize;
        (d, String::from_utf8(b[13..13 + n].to_vec()).unwrap())
    }

    fn places(b: &[u8]) -> Vec<(String, f64, f64)> {
        let n = i32::from_be_bytes(b[0..4].try_into().unwrap());
        let mut p = 4;
        let mut out = Vec::new();
        for _ in 0..n {
            let l = i32::from_be_bytes(b[p..p + 4].try_into().unwrap()) as usize;
            p += 4;
            let s = String::from_utf8(b[p..p + l].to_vec()).unwrap();
            p += l;
            let la = f64::from_bits(u64::from_be_bytes(b[p..p + 8].try_into().unwrap()));
            let lo = f64::from_bits(u64::from_be_bytes(b[p + 8..p + 16].try_into().unwrap()));
            p += 16;
            out.push((s, la, lo));
        }
        assert_eq!(p, b.len());
        out
    }

    fn body(duration: Option<&str>, encoded: Option<&str>) -> String {
        let mut f = Vec::new();
        if let Some(d) = duration {
            f.push(format!("\"duration\":\"{}\"", d));
        }
        if let Some(e) = encoded {
            f.push(format!("\"polyline\":{{\"encodedPolyline\":\"{}\"}}", e));
        }
        format!("{{\"routes\":[{{{}}}]}}", f.join(","))
    }

    #[test]
    fn route_valid() {
        let (d, e) = route_fields(&parse_route(&body(Some("120s"), Some("_p~iF~ps|U"))).unwrap());
        assert_eq!(d, 120.0);
        assert_eq!(e, "_p~iF~ps|U");
        assert_eq!(route_fields(&parse_route(&body(Some("123.5s"), Some("a"))).unwrap()).0, 123.5);
    }

    #[test]
    fn route_duration_fallback() {
        for d in [Some("abc"), None, Some(""), Some("nan"), Some("infs")] {
            assert_eq!(route_fields(&parse_route(&body(d, Some("a"))).unwrap()).0, 0.0);
        }
    }

    #[test]
    fn route_missing_polyline_is_empty() {
        assert_eq!(route_fields(&parse_route(&body(Some("10s"), None)).unwrap()).1, "");
    }

    #[test]
    fn route_not_found() {
        for t in ["{}", "{\"routes\":[]}", "{\"routes\":null}", "{\"routes\":[null]}", "{\"routes\":{}}"] {
            assert_eq!(parse_route(t), Some(vec![0]), "{}", t);
        }
    }

    #[test]
    fn route_malformed() {
        for t in ["", "[]", "{\"routes\":[{", "<html>502</html>", "null", "1"] {
            assert_eq!(parse_route(t), None, "{}", t);
        }
    }

    #[test]
    fn error_message_cases() {
        assert_eq!(error_message("{\"error\":{\"message\":\"quota\"}}"), Some("quota".to_string()));
        assert_eq!(error_message("{\"error\":{}}"), Some(String::new()));
        assert_eq!(error_message("{\"error\":\"x\"}"), None);
        assert_eq!(error_message("{}"), None);
        assert_eq!(error_message("<html>"), None);
    }

    #[test]
    fn elevation_google() {
        let t = "{\"status\":\"OK\",\"results\":[{\"elevation\":10.5},{\"elevation\":3}]}";
        assert_eq!(parse_elevation(t, 2, true), Some(vec![10.5, 3.0]));
        assert_eq!(parse_elevation(t, 3, true), None);
        assert_eq!(parse_elevation("{\"status\":\"OVER_QUERY_LIMIT\",\"results\":[]}", 0, true), None);
        assert_eq!(parse_elevation("{\"status\":\"OK\",\"results\":[{}]}", 1, true), None);
        assert_eq!(parse_elevation("{\"status\":\"OK\",\"results\":[{\"elevation\":null}]}", 1, true), None);
        assert_eq!(parse_elevation("{\"results\":[{\"elevation\":1}]}", 1, true), None);
        assert_eq!(parse_elevation("[]", 0, true), None);
    }

    #[test]
    fn elevation_open_meteo() {
        assert_eq!(parse_elevation("{\"elevation\":[1.5,2,-3]}", 3, false), Some(vec![1.5, 2.0, -3.0]));
        assert_eq!(parse_elevation("{\"elevation\":[1.5]}", 2, false), None);
        assert_eq!(parse_elevation("{\"elevation\":[null]}", 1, false), None);
        assert_eq!(parse_elevation("{}", 0, false), None);
    }

    #[test]
    fn photon_places() {
        let t = "{\"features\":[\
            {\"geometry\":{\"coordinates\":[110.36,-7.8]},\"properties\":{\"name\":\"Tugu\",\"city\":\"Yogyakarta\",\"state\":\"DIY\",\"country\":\"Indonesia\"}},\
            {\"geometry\":{\"coordinates\":[110.4,-7.7]},\"properties\":{\"name\":\"Kota\",\"city\":\"Kota\",\"county\":\"X\",\"country\":\"Indonesia\"}},\
            {\"geometry\":{\"coordinates\":[1,2]},\"properties\":{\"county\":\"Sleman\",\"state\":\"Sleman\"}},\
            {\"geometry\":{\"coordinates\":[200,2]},\"properties\":{\"name\":\"bad\"}},\
            {\"geometry\":{\"coordinates\":[1]},\"properties\":{\"name\":\"bad\"}},\
            {\"properties\":{\"name\":\"nogeom\"}},\
            7]}";
        let r = places(&parse_places(KIND_PHOTON, t, "q").unwrap());
        assert_eq!(
            r,
            vec![
                ("Tugu, Yogyakarta, DIY, Indonesia".to_string(), -7.8, 110.36),
                ("Kota, Indonesia".to_string(), -7.7, 110.4),
                ("q, Sleman".to_string(), 2.0, 1.0),
            ]
        );
    }

    #[test]
    fn photon_without_features_is_empty() {
        assert_eq!(places(&parse_places(KIND_PHOTON, "{}", "q").unwrap()), vec![]);
        assert_eq!(parse_places(KIND_PHOTON, "[]", "q"), None);
        assert_eq!(parse_places(KIND_PHOTON, "", "q"), None);
    }

    #[test]
    fn nominatim_places() {
        let t = "[{\"lat\":\"-7.8\",\"lon\":\"110.3\",\"display_name\":\"Jogja\"},\
            {\"lat\":1.5,\"lon\":2.5},\
            {\"lat\":\"x\",\"lon\":\"1\"},\
            {\"lon\":\"1\"},\
            null]";
        assert_eq!(
            places(&parse_places(KIND_NOMINATIM, t, "").unwrap()),
            vec![("Jogja".to_string(), -7.8, 110.3), ("1.50000, 2.50000".to_string(), 1.5, 2.5)]
        );
        assert_eq!(parse_places(KIND_NOMINATIM, "{}", ""), None);
        assert_eq!(parse_places(KIND_NOMINATIM, "[", ""), None);
        assert_eq!(parse_places(9, "[]", ""), None);
    }

    #[test]
    fn places_are_distinct_by_label_keeping_first() {
        let t = "[{\"lat\":\"1\",\"lon\":\"2\",\"display_name\":\"A\"},\
            {\"lat\":\"3\",\"lon\":\"4\",\"display_name\":\"B\"},\
            {\"lat\":\"5\",\"lon\":\"6\",\"display_name\":\"A\"},\
            {\"lat\":\"1\",\"lon\":\"2\"},\
            {\"lat\":\"1\",\"lon\":\"2\",\"display_name\":\"  \"}]";
        assert_eq!(
            places(&parse_places(KIND_NOMINATIM, t, "").unwrap()),
            vec![
                ("A".to_string(), 1.0, 2.0),
                ("B".to_string(), 3.0, 4.0),
                ("1.00000, 2.00000".to_string(), 1.0, 2.0),
            ]
        );
        let p = "{\"features\":[\
            {\"geometry\":{\"coordinates\":[1,2]},\"properties\":{\"name\":\"X\"}},\
            {\"geometry\":{\"coordinates\":[3,4]},\"properties\":{\"name\":\"X\"}}]}";
        assert_eq!(places(&parse_places(KIND_PHOTON, p, "q").unwrap()), vec![("X".to_string(), 2.0, 1.0)]);
    }
}
