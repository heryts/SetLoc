use crate::json::{self, Json};
use std::fmt::Write;

pub const KIND_FAV: i32 = 0;
pub const KIND_ROUTE: i32 = 1;
pub const KIND_HISTORY: i32 = 2;

#[derive(Debug, Clone, PartialEq)]
pub struct Fav {
    pub id: i64,
    pub address: Option<String>,
    pub lat: Option<f64>,
    pub lng: Option<f64>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SavedRoute {
    pub id: i64,
    pub name: String,
    pub mode: i32,
    pub points: Vec<(f64, f64)>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct History {
    pub label: String,
    pub lat: f64,
    pub lon: f64,
}

type Obj = [(String, Json)];

pub(crate) fn find<'a>(o: &'a Obj, k: &str) -> Option<&'a Json> {
    o.iter().rev().find(|e| e.0.as_str() == k).map(|e| &e.1)
}

fn is_null(o: &Obj, k: &str) -> bool {
    matches!(find(o, k), None | Some(Json::Null))
}

fn to_long(v: &Json) -> Option<i64> {
    match v {
        Json::Num(n) => Some(n.i.unwrap_or(n.f as i64)),
        Json::Str(s) => s.trim().parse::<f64>().ok().filter(|f| f.is_finite()).map(|f| f as i64),
        _ => None,
    }
}

pub(crate) fn to_double(v: &Json) -> Option<f64> {
    match v {
        Json::Num(n) => Some(n.f),
        Json::Str(s) => s.trim().parse::<f64>().ok().filter(|f| f.is_finite()),
        _ => None,
    }
}

fn to_text(v: &Json) -> Option<String> {
    match v {
        Json::Str(s) => Some(s.clone()),
        Json::Num(n) => Some(match n.i {
            Some(i) => i.to_string(),
            None => n.f.to_string(),
        }),
        Json::Bool(b) => Some(b.to_string()),
        Json::Null => Some("null".to_string()),
        _ => None,
    }
}

fn get_long(o: &Obj, k: &str) -> Option<i64> {
    to_long(find(o, k)?)
}

fn get_int(o: &Obj, k: &str) -> Option<i32> {
    get_long(o, k).map(|v| v as i32)
}

fn get_double(o: &Obj, k: &str) -> Option<f64> {
    to_double(find(o, k)?)
}

fn get_text(o: &Obj, k: &str) -> Option<String> {
    to_text(find(o, k)?)
}

fn top_array(raw: &str) -> Option<Vec<Json>> {
    match json::parse(raw)? {
        Json::Arr(a) => Some(a),
        _ => None,
    }
}

fn fav_item(v: &Json) -> Option<Fav> {
    let o = v.as_obj()?;
    let id = get_long(o, "id")?;
    let address = if is_null(o, "a") { None } else { Some(get_text(o, "a")?) };
    let lat = if is_null(o, "la") { None } else { Some(get_double(o, "la")?) };
    let lng = if is_null(o, "ln") { None } else { Some(get_double(o, "ln")?) };
    Some(Fav { id, address, lat, lng })
}

fn route_item(v: &Json) -> Option<SavedRoute> {
    let o = v.as_obj()?;
    let raw_pts = find(o, "p")?.as_arr()?;
    let mut points = Vec::with_capacity(raw_pts.len());
    for p in raw_pts {
        let a = p.as_arr()?;
        let la = to_double(a.first()?)?;
        let ln = to_double(a.get(1)?)?;
        points.push((la, ln));
    }
    if points.len() < 2 {
        return None;
    }
    Some(SavedRoute { id: get_long(o, "id")?, name: get_text(o, "n")?, mode: get_int(o, "m")?, points })
}

fn history_item(v: &Json) -> Option<History> {
    let o = v.as_obj()?;
    Some(History { label: get_text(o, "l")?, lat: get_double(o, "la")?, lon: get_double(o, "ln")? })
}

pub fn parse_favourites(raw: &str) -> Option<Vec<Fav>> {
    let mut out: Vec<Fav> = top_array(raw)?.iter().filter_map(fav_item).collect();
    out.sort_by(|a, b| b.id.cmp(&a.id));
    Some(out)
}

pub fn parse_routes(raw: &str) -> Option<Vec<SavedRoute>> {
    let mut out: Vec<SavedRoute> = top_array(raw)?.iter().filter_map(route_item).collect();
    out.sort_by(|a, b| b.id.cmp(&a.id));
    Some(out)
}

pub fn parse_history(raw: &str) -> Vec<History> {
    match top_array(raw) {
        Some(a) => a.iter().filter_map(history_item).collect(),
        None => Vec::new(),
    }
}

fn push_text(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            c if (c as u32) < 0x20 || c == '\u{2028}' || c == '\u{2029}' => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

fn push_num(out: &mut String, v: f64) -> Option<()> {
    if !v.is_finite() {
        return None;
    }
    let _ = write!(out, "{}", v);
    Some(())
}

pub fn encode_favourites(list: &[Fav]) -> Option<String> {
    let mut out = String::from("[");
    for (i, f) in list.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        let _ = write!(out, "{{\"id\":{},\"a\":", f.id);
        match &f.address {
            Some(a) => push_text(&mut out, a),
            None => out.push_str("null"),
        }
        out.push_str(",\"la\":");
        match f.lat {
            Some(v) => push_num(&mut out, v)?,
            None => out.push_str("null"),
        }
        out.push_str(",\"ln\":");
        match f.lng {
            Some(v) => push_num(&mut out, v)?,
            None => out.push_str("null"),
        }
        out.push('}');
    }
    out.push(']');
    Some(out)
}

pub fn encode_routes(list: &[SavedRoute]) -> Option<String> {
    let mut out = String::from("[");
    for (i, r) in list.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        let _ = write!(out, "{{\"id\":{},\"n\":", r.id);
        push_text(&mut out, &r.name);
        let _ = write!(out, ",\"m\":{},\"p\":[", r.mode);
        for (j, p) in r.points.iter().enumerate() {
            if j > 0 {
                out.push(',');
            }
            out.push('[');
            push_num(&mut out, p.0)?;
            out.push(',');
            push_num(&mut out, p.1)?;
            out.push(']');
        }
        out.push_str("]}");
    }
    out.push(']');
    Some(out)
}

pub fn encode_history(list: &[History]) -> Option<String> {
    let mut out = String::from("[");
    for (i, h) in list.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str("{\"l\":");
        push_text(&mut out, &h.label);
        out.push_str(",\"la\":");
        push_num(&mut out, h.lat)?;
        out.push_str(",\"ln\":");
        push_num(&mut out, h.lon)?;
        out.push('}');
    }
    out.push(']');
    Some(out)
}

struct Out(Vec<u8>);

impl Out {
    fn u8(&mut self, v: u8) {
        self.0.push(v);
    }

    fn i32(&mut self, v: i32) {
        self.0.extend_from_slice(&v.to_be_bytes());
    }

    fn i64(&mut self, v: i64) {
        self.0.extend_from_slice(&v.to_be_bytes());
    }

    fn f64(&mut self, v: f64) {
        self.0.extend_from_slice(&v.to_bits().to_be_bytes());
    }

    fn text(&mut self, s: &str) {
        self.i32(s.len() as i32);
        self.0.extend_from_slice(s.as_bytes());
    }
}

struct In<'a> {
    b: &'a [u8],
    p: usize,
}

impl<'a> In<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let e = self.p.checked_add(n)?;
        let s = self.b.get(self.p..e)?;
        self.p = e;
        Some(s)
    }

    fn u8(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }

    fn i32(&mut self) -> Option<i32> {
        Some(i32::from_be_bytes(self.take(4)?.try_into().ok()?))
    }

    fn i64(&mut self) -> Option<i64> {
        Some(i64::from_be_bytes(self.take(8)?.try_into().ok()?))
    }

    fn f64(&mut self) -> Option<f64> {
        Some(f64::from_bits(u64::from_be_bytes(self.take(8)?.try_into().ok()?)))
    }

    fn text(&mut self) -> Option<String> {
        let n = self.i32()?;
        if n < 0 {
            return None;
        }
        String::from_utf8(self.take(n as usize)?.to_vec()).ok()
    }

    fn count(&mut self) -> Option<usize> {
        let n = self.i32()?;
        if n < 0 {
            None
        } else {
            Some(n as usize)
        }
    }

    fn done(&self) -> bool {
        self.p == self.b.len()
    }
}

const HAS_ADDRESS: u8 = 1;
const HAS_LAT: u8 = 2;
const HAS_LNG: u8 = 4;

fn favs_to_blob(list: &[Fav]) -> Vec<u8> {
    let mut o = Out(Vec::new());
    o.i32(list.len() as i32);
    for f in list {
        o.i64(f.id);
        let mut flags = 0u8;
        if f.address.is_some() {
            flags |= HAS_ADDRESS;
        }
        if f.lat.is_some() {
            flags |= HAS_LAT;
        }
        if f.lng.is_some() {
            flags |= HAS_LNG;
        }
        o.u8(flags);
        if let Some(a) = &f.address {
            o.text(a);
        }
        if let Some(v) = f.lat {
            o.f64(v);
        }
        if let Some(v) = f.lng {
            o.f64(v);
        }
    }
    o.0
}

fn favs_from_blob(b: &[u8]) -> Option<Vec<Fav>> {
    let mut r = In { b, p: 0 };
    let n = r.count()?;
    let mut out = Vec::new();
    for _ in 0..n {
        let id = r.i64()?;
        let flags = r.u8()?;
        let address = if flags & HAS_ADDRESS != 0 { Some(r.text()?) } else { None };
        let lat = if flags & HAS_LAT != 0 { Some(r.f64()?) } else { None };
        let lng = if flags & HAS_LNG != 0 { Some(r.f64()?) } else { None };
        out.push(Fav { id, address, lat, lng });
    }
    if r.done() {
        Some(out)
    } else {
        None
    }
}

fn routes_to_blob(list: &[SavedRoute]) -> Vec<u8> {
    let mut o = Out(Vec::new());
    o.i32(list.len() as i32);
    for r in list {
        o.i64(r.id);
        o.text(&r.name);
        o.i32(r.mode);
        o.i32(r.points.len() as i32);
        for p in &r.points {
            o.f64(p.0);
            o.f64(p.1);
        }
    }
    o.0
}

fn routes_from_blob(b: &[u8]) -> Option<Vec<SavedRoute>> {
    let mut r = In { b, p: 0 };
    let n = r.count()?;
    let mut out = Vec::new();
    for _ in 0..n {
        let id = r.i64()?;
        let name = r.text()?;
        let mode = r.i32()?;
        let m = r.count()?;
        let mut points = Vec::new();
        for _ in 0..m {
            points.push((r.f64()?, r.f64()?));
        }
        out.push(SavedRoute { id, name, mode, points });
    }
    if r.done() {
        Some(out)
    } else {
        None
    }
}

fn history_to_blob(list: &[History]) -> Vec<u8> {
    let mut o = Out(Vec::new());
    o.i32(list.len() as i32);
    for h in list {
        o.text(&h.label);
        o.f64(h.lat);
        o.f64(h.lon);
    }
    o.0
}

fn history_from_blob(b: &[u8]) -> Option<Vec<History>> {
    let mut r = In { b, p: 0 };
    let n = r.count()?;
    let mut out = Vec::new();
    for _ in 0..n {
        out.push(History { label: r.text()?, lat: r.f64()?, lon: r.f64()? });
    }
    if r.done() {
        Some(out)
    } else {
        None
    }
}

pub fn parse_blob(kind: i32, raw: &str) -> Option<Vec<u8>> {
    match kind {
        KIND_FAV => Some(favs_to_blob(&parse_favourites(raw)?)),
        KIND_ROUTE => Some(routes_to_blob(&parse_routes(raw)?)),
        KIND_HISTORY => Some(history_to_blob(&parse_history(raw))),
        _ => None,
    }
}

pub fn encode_blob(kind: i32, blob: &[u8]) -> Option<String> {
    match kind {
        KIND_FAV => encode_favourites(&favs_from_blob(blob)?),
        KIND_ROUTE => encode_routes(&routes_from_blob(blob)?),
        KIND_HISTORY => encode_history(&history_from_blob(blob)?),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(v: &[Fav]) -> Vec<i64> {
        v.iter().map(|f| f.id).collect()
    }

    fn rids(v: &[SavedRoute]) -> Vec<i64> {
        v.iter().map(|f| f.id).collect()
    }

    #[test]
    fn fav_valid_sorted_descending() {
        let r = parse_favourites(
            "[{\"id\":1,\"a\":\"A\",\"la\":1.5,\"ln\":2.5},{\"id\":3,\"a\":\"C\",\"la\":3.0,\"ln\":4.0},{\"id\":2,\"a\":\"B\",\"la\":5.0,\"ln\":6.0}]",
        )
        .unwrap();
        assert_eq!(ids(&r), vec![3, 2, 1]);
        let last = r.last().unwrap();
        assert_eq!(last.address.as_deref(), Some("A"));
        assert_eq!(last.lat, Some(1.5));
        assert_eq!(last.lng, Some(2.5));
    }

    #[test]
    fn fav_empty_array() {
        assert_eq!(parse_favourites("[]"), Some(vec![]));
    }

    #[test]
    fn fav_null_and_missing_become_none() {
        let r = parse_favourites("[{\"id\":7,\"a\":null,\"la\":null,\"ln\":null},{\"id\":8}]").unwrap();
        assert_eq!(r.len(), 2);
        for f in &r {
            assert_eq!(f.address, None);
            assert_eq!(f.lat, None);
            assert_eq!(f.lng, None);
        }
    }

    #[test]
    fn fav_broken_elements_skipped() {
        let r = parse_favourites("[1,\"x\",null,{\"la\":1.0},{\"id\":\"abc\"},{\"id\":2,\"a\":\"ok\",\"la\":1.0,\"ln\":2.0}]").unwrap();
        assert_eq!(r.len(), 1);
        assert_eq!(r[0].id, 2);
        assert_eq!(r[0].address.as_deref(), Some("ok"));
    }

    #[test]
    fn fav_non_numeric_coordinate_skips_element() {
        let r = parse_favourites("[{\"id\":1,\"a\":\"x\",\"la\":\"oops\",\"ln\":2.0},{\"id\":2,\"a\":\"y\",\"la\":1.0,\"ln\":2.0}]").unwrap();
        assert_eq!(ids(&r), vec![2]);
    }

    #[test]
    fn fav_numeric_strings_accepted() {
        let r = parse_favourites("[{\"id\":\"5\",\"a\":\"x\",\"la\":\"1.5\",\"ln\":2}]").unwrap();
        assert_eq!(r[0].id, 5);
        assert_eq!(r[0].lat, Some(1.5));
        assert_eq!(r[0].lng, Some(2.0));
    }

    #[test]
    fn fav_corrupt_top_level_is_none() {
        for t in [
            "",
            "garbage",
            "{}",
            "[{\"id\":1,\"a\":\"x\"",
            "[{\"id\":}",
            "[{\"id\":1,\"a\":\"x\",\"la\":1.0,\"ln\":2.0},",
        ] {
            assert_eq!(parse_favourites(t), None, "{}", t);
        }
    }

    #[test]
    fn fav_equal_ids_keep_order() {
        let r = parse_favourites("[{\"id\":1,\"a\":\"first\"},{\"id\":1,\"a\":\"second\"}]").unwrap();
        assert_eq!(r[0].address.as_deref(), Some("first"));
        assert_eq!(r[1].address.as_deref(), Some("second"));
    }

    fn route(id: i64, name: &str, mode: i32, p: &str) -> String {
        format!("{{\"id\":{},\"n\":\"{}\",\"m\":{},\"p\":{}}}", id, name, mode, p)
    }

    fn r(id: i64) -> String {
        route(id, &format!("r{}", id), 0, "[[1.0,2.0],[3.0,4.0]]")
    }

    #[test]
    fn route_valid_sorted_descending() {
        let raw = format!("[{},{},{}]", r(1), route(5, "r5", 2, "[[1.0,2.0],[3.0,4.0]]"), r(3));
        let v = parse_routes(&raw).unwrap();
        assert_eq!(rids(&v), vec![5, 3, 1]);
        assert_eq!(v[0].mode, 2);
        assert_eq!(v[0].name, "r5");
        assert_eq!(v[0].points, vec![(1.0, 2.0), (3.0, 4.0)]);
    }

    #[test]
    fn route_empty_array() {
        assert_eq!(parse_routes("[]"), Some(vec![]));
    }

    #[test]
    fn route_fewer_than_two_points_dropped() {
        let raw = format!("[{},{},{}]", route(1, "a", 0, "[]"), route(2, "b", 0, "[[1.0,2.0]]"), r(3));
        assert_eq!(rids(&parse_routes(&raw).unwrap()), vec![3]);
    }

    #[test]
    fn route_point_with_single_coordinate_drops_route() {
        let raw = format!("[{},{}]", route(1, "a", 0, "[[1.0,2.0],[3.0]]"), r(2));
        assert_eq!(rids(&parse_routes(&raw).unwrap()), vec![2]);
    }

    #[test]
    fn route_non_array_or_non_numeric_points_drop_route() {
        let raw = format!("[{},{},{}]", route(1, "a", 0, "[1,2]"), route(2, "b", 0, "[[\"a\",\"b\"],[1.0,2.0]]"), r(3));
        assert_eq!(rids(&parse_routes(&raw).unwrap()), vec![3]);
    }

    #[test]
    fn route_missing_required_field_drops_route() {
        let raw = format!(
            "[{{\"n\":\"x\",\"m\":0,\"p\":[[1.0,2.0],[3.0,4.0]]}},{{\"id\":2,\"m\":0,\"p\":[[1.0,2.0],[3.0,4.0]]}},{{\"id\":3,\"n\":\"x\",\"p\":[[1.0,2.0],[3.0,4.0]]}},{{\"id\":4,\"n\":\"x\",\"m\":0}},{}]",
            r(9)
        );
        assert_eq!(rids(&parse_routes(&raw).unwrap()), vec![9]);
    }

    #[test]
    fn route_non_object_elements_skipped() {
        let raw = format!("[1,null,\"x\",{}]", r(4));
        assert_eq!(rids(&parse_routes(&raw).unwrap()), vec![4]);
    }

    #[test]
    fn route_corrupt_top_level_is_none() {
        let tail = format!("[{},", r(1));
        for t in ["", "garbage", "{}", "[{\"id\":1,\"n\":\"x\",\"m\":0,\"p\":[[1.0,2.0]]", tail.as_str()] {
            assert_eq!(parse_routes(t), None, "{}", t);
        }
    }

    #[test]
    fn history_parse_keeps_order() {
        let v = parse_history("[{\"l\":\"B\",\"la\":1.0,\"ln\":2.0},{\"l\":\"A\",\"la\":3.0,\"ln\":4.0}]");
        assert_eq!(v.len(), 2);
        assert_eq!(v[0].label, "B");
        assert_eq!(v[1].lat, 3.0);
        assert_eq!(v[1].lon, 4.0);
    }

    #[test]
    fn history_skips_broken_and_tolerates_corrupt() {
        let v = parse_history("[1,{\"l\":\"x\"},{\"l\":\"ok\",\"la\":1.0,\"ln\":2.0},{\"l\":\"y\",\"la\":\"z\",\"ln\":2.0}]");
        assert_eq!(v.len(), 1);
        assert_eq!(v[0].label, "ok");
        assert!(parse_history("garbage").is_empty());
        assert!(parse_history("{}").is_empty());
        assert!(parse_history("").is_empty());
        assert!(parse_history("[").is_empty());
    }

    #[test]
    fn fav_json_roundtrip() {
        let list = vec![
            Fav { id: 3, address: Some("Jl. \"Merdeka\" \\ 1\n😀".into()), lat: Some(-6.2088), lng: Some(106.8456) },
            Fav { id: 2, address: None, lat: None, lng: None },
            Fav { id: 1, address: Some("é".into()), lat: Some(0.0), lng: Some(-0.5) },
        ];
        let s = encode_favourites(&list).unwrap();
        assert_eq!(parse_favourites(&s).unwrap(), list);
    }

    #[test]
    fn fav_json_shape() {
        let list = vec![Fav { id: 4, address: Some("A".into()), lat: Some(1.5), lng: None }];
        assert_eq!(encode_favourites(&list).unwrap(), "[{\"id\":4,\"a\":\"A\",\"la\":1.5,\"ln\":null}]");
        assert_eq!(encode_favourites(&[]).unwrap(), "[]");
    }

    #[test]
    fn route_json_roundtrip_and_shape() {
        let list = vec![SavedRoute { id: 2, name: "Rute \"A\"".into(), mode: 1, points: vec![(1.0, 2.5), (-3.25, 4.0)] }];
        let s = encode_routes(&list).unwrap();
        assert_eq!(s, "[{\"id\":2,\"n\":\"Rute \\\"A\\\"\",\"m\":1,\"p\":[[1,2.5],[-3.25,4]]}]");
        assert_eq!(parse_routes(&s).unwrap(), list);
    }

    #[test]
    fn history_json_roundtrip_and_shape() {
        let list = vec![History { label: "Tempat\tX".into(), lat: 1.25, lon: -2.0 }];
        let s = encode_history(&list).unwrap();
        assert_eq!(s, "[{\"l\":\"Tempat\\tX\",\"la\":1.25,\"ln\":-2}]");
        assert_eq!(parse_history(&s), list);
    }

    #[test]
    fn precise_doubles_survive() {
        let v = 106.84559999999999_f64;
        let list = vec![History { label: "x".into(), lat: v, lon: 1e-7 }];
        let back = parse_history(&encode_history(&list).unwrap());
        assert_eq!(back[0].lat, v);
        assert_eq!(back[0].lon, 1e-7);
    }

    #[test]
    fn non_finite_numbers_fail_encoding() {
        let list = vec![History { label: "x".into(), lat: f64::NAN, lon: 0.0 }];
        assert_eq!(encode_history(&list), None);
        let list = vec![SavedRoute { id: 1, name: "x".into(), mode: 0, points: vec![(f64::INFINITY, 0.0), (0.0, 0.0)] }];
        assert_eq!(encode_routes(&list), None);
    }

    #[test]
    fn control_characters_are_escaped() {
        let list = vec![History { label: "a\u{1}b\u{2028}".into(), lat: 0.0, lon: 0.0 }];
        let s = encode_history(&list).unwrap();
        assert!(s.contains("\\u0001") && s.contains("\\u2028"));
        assert_eq!(parse_history(&s), list);
    }

    #[test]
    fn blob_roundtrip_all_kinds() {
        let favs = "[{\"id\":2,\"a\":\"x\",\"la\":1.5,\"ln\":2.5},{\"id\":1}]";
        let b = parse_blob(KIND_FAV, favs).unwrap();
        assert_eq!(favs_from_blob(&b).unwrap(), parse_favourites(favs).unwrap());
        assert_eq!(encode_blob(KIND_FAV, &b).unwrap(), encode_favourites(&parse_favourites(favs).unwrap()).unwrap());

        let routes = format!("[{},{}]", r(1), r(2));
        let b = parse_blob(KIND_ROUTE, &routes).unwrap();
        assert_eq!(routes_from_blob(&b).unwrap(), parse_routes(&routes).unwrap());
        assert_eq!(encode_blob(KIND_ROUTE, &b).unwrap(), encode_routes(&parse_routes(&routes).unwrap()).unwrap());

        let hist = "[{\"l\":\"é\",\"la\":1.0,\"ln\":2.0}]";
        let b = parse_blob(KIND_HISTORY, hist).unwrap();
        assert_eq!(history_from_blob(&b).unwrap(), parse_history(hist));
        assert_eq!(encode_blob(KIND_HISTORY, &b).unwrap(), encode_history(&parse_history(hist)).unwrap());
    }

    #[test]
    fn blob_layout_is_big_endian() {
        let b = parse_blob(KIND_HISTORY, "[{\"l\":\"ab\",\"la\":1.0,\"ln\":2.0}]").unwrap();
        let mut want = vec![0, 0, 0, 1, 0, 0, 0, 2, b'a', b'b'];
        want.extend_from_slice(&1.0f64.to_bits().to_be_bytes());
        want.extend_from_slice(&2.0f64.to_bits().to_be_bytes());
        assert_eq!(b, want);
    }

    #[test]
    fn blob_rejects_truncated_extra_and_bad_kind() {
        let b = parse_blob(KIND_HISTORY, "[{\"l\":\"ab\",\"la\":1.0,\"ln\":2.0}]").unwrap();
        assert_eq!(encode_blob(KIND_HISTORY, &b[..b.len() - 1]), None);
        let mut longer = b.clone();
        longer.push(0);
        assert_eq!(encode_blob(KIND_HISTORY, &longer), None);
        assert_eq!(encode_blob(KIND_HISTORY, &[]), None);
        assert_eq!(encode_blob(KIND_HISTORY, &[0xff, 0xff, 0xff, 0xff]), None);
        assert_eq!(encode_blob(9, &b), None);
        assert_eq!(parse_blob(9, "[]"), None);
        assert_eq!(parse_blob(KIND_FAV, "garbage"), None);
        assert!(parse_blob(KIND_HISTORY, "garbage").is_some());
    }
}
