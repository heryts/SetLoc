use crate::jfmt::{fixed, form_encode};
use crate::geo::Pt;

const MODES: [&str; 4] = ["WALK", "BICYCLE", "DRIVE", "DRIVE"];
const MODE_MOTOR: usize = 2;
const GOOGLE: &str = "https://maps.googleapis.com/maps/api/elevation/json";
const OPEN_METEO: &str = "https://api.open-meteo.com/v1/elevation";

pub const ELEV_CACHE_SIZE: usize = 8;

fn waypoint(out: &mut String, p: Pt) {
    out.push_str("{\"location\":{\"latLng\":{\"latitude\":");
    out.push_str(&p.0.to_string());
    out.push_str(",\"longitude\":");
    out.push_str(&p.1.to_string());
    out.push_str("}}}");
}

pub fn route_body(stops: &[Pt], mode: i32, traffic_aware: bool) -> Option<String> {
    if stops.len() < 2 || stops.iter().any(|p| !p.0.is_finite() || !p.1.is_finite()) {
        return None;
    }
    let idx = mode.clamp(0, MODES.len() as i32 - 1) as usize;
    let travel = MODES[idx];
    let mut out = String::with_capacity(256);
    out.push_str("{\"origin\":");
    waypoint(&mut out, stops[0]);
    out.push_str(",\"destination\":");
    waypoint(&mut out, stops[stops.len() - 1]);
    out.push_str(",\"travelMode\":\"");
    out.push_str(travel);
    out.push_str("\",\"polylineEncoding\":\"ENCODED_POLYLINE\"");
    if idx == MODE_MOTOR {
        out.push_str(",\"routeModifiers\":{\"avoidTolls\":true}");
    }
    if travel == "DRIVE" && traffic_aware {
        out.push_str(",\"routingPreference\":\"TRAFFIC_AWARE\"");
    }
    if stops.len() > 2 {
        out.push_str(",\"intermediates\":[");
        for (i, p) in stops[1..stops.len() - 1].iter().enumerate() {
            if i > 0 {
                out.push(',');
            }
            waypoint(&mut out, *p);
        }
        out.push(']');
    }
    out.push('}');
    Some(out)
}

pub fn elevation_url_google(encoded: &str, key: &str) -> String {
    format!("{}?locations=enc:{}&key={}", GOOGLE, form_encode(encoded), key)
}

pub fn elevation_url_open_meteo(pts: &[Pt]) -> String {
    let lat: Vec<String> = pts.iter().map(|p| fixed(p.0, 5)).collect();
    let lng: Vec<String> = pts.iter().map(|p| fixed(p.1, 5)).collect();
    format!("{}?latitude={}&longitude={}", OPEN_METEO, lat.join(","), lng.join(","))
}

pub struct Lru {
    cap: usize,
    items: Vec<(String, Vec<f32>)>,
}

impl Lru {
    pub const fn new(cap: usize) -> Self {
        Lru { cap, items: Vec::new() }
    }

    pub fn get(&mut self, key: &str) -> Option<Vec<f32>> {
        let i = self.items.iter().position(|(k, _)| k == key)?;
        let e = self.items.remove(i);
        let v = e.1.clone();
        self.items.push(e);
        Some(v)
    }

    pub fn put(&mut self, key: &str, value: Vec<f32>) {
        if let Some(i) = self.items.iter().position(|(k, _)| k == key) {
            self.items.remove(i);
        }
        self.items.push((key.to_string(), value));
        while self.items.len() > self.cap {
            self.items.remove(0);
        }
    }

    pub fn len(&self) -> usize {
        self.items.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::json::{self, Json};
    use crate::store::find;

    fn parsed(s: &str) -> Json {
        json::parse(s).expect("valid json")
    }

    fn latlng(o: &Json, key: &str) -> (f64, f64) {
        let loc = find(o.as_obj().unwrap(), key).and_then(Json::as_obj).unwrap();
        let ll = find(find(loc, "location").and_then(Json::as_obj).unwrap(), "latLng").and_then(Json::as_obj).unwrap();
        let num = |k: &str| crate::store::to_double(find(ll, k).unwrap()).unwrap();
        (num("latitude"), num("longitude"))
    }

    fn text(o: &Json, key: &str) -> Option<String> {
        match find(o.as_obj().unwrap(), key) {
            Some(Json::Str(s)) => Some(s.clone()),
            _ => None,
        }
    }

    #[test]
    fn body_two_stops_walk() {
        let b = route_body(&[(-7.8, 110.36), (-7.7, 110.4)], 0, true).unwrap();
        assert_eq!(
            b,
            "{\"origin\":{\"location\":{\"latLng\":{\"latitude\":-7.8,\"longitude\":110.36}}},\
             \"destination\":{\"location\":{\"latLng\":{\"latitude\":-7.7,\"longitude\":110.4}}},\
             \"travelMode\":\"WALK\",\"polylineEncoding\":\"ENCODED_POLYLINE\"}"
        );
        let v = parsed(&b);
        assert_eq!(latlng(&v, "origin"), (-7.8, 110.36));
        assert_eq!(latlng(&v, "destination"), (-7.7, 110.4));
        assert_eq!(text(&v, "routingPreference"), None);
    }

    #[test]
    fn body_modes_and_modifiers() {
        let s = [(1.0, 2.0), (3.0, 4.0)];
        let walk = route_body(&s, 0, false).unwrap();
        assert!(walk.contains("\"travelMode\":\"WALK\"") && !walk.contains("routeModifiers"));
        let bike = route_body(&s, 1, true).unwrap();
        assert!(bike.contains("\"travelMode\":\"BICYCLE\"") && !bike.contains("routingPreference"));
        let motor = route_body(&s, 2, true).unwrap();
        assert!(motor.contains("\"travelMode\":\"DRIVE\""));
        assert!(motor.contains(",\"routeModifiers\":{\"avoidTolls\":true}"));
        assert!(motor.ends_with(",\"routingPreference\":\"TRAFFIC_AWARE\"}"));
        let car = route_body(&s, 3, true).unwrap();
        assert!(car.contains("\"travelMode\":\"DRIVE\"") && !car.contains("routeModifiers"));
        assert!(car.contains("\"routingPreference\":\"TRAFFIC_AWARE\""));
        let car_off = route_body(&s, 3, false).unwrap();
        assert!(!car_off.contains("routingPreference"));
        for v in [&walk, &bike, &motor, &car, &car_off] {
            parsed(v);
        }
    }

    #[test]
    fn body_mode_is_clamped() {
        let s = [(1.0, 2.0), (3.0, 4.0)];
        assert_eq!(route_body(&s, -5, false), route_body(&s, 0, false));
        assert_eq!(route_body(&s, 99, false), route_body(&s, 3, false));
    }

    #[test]
    fn body_intermediates_keep_order() {
        let s = [(0.0, 0.0), (1.0, 1.5), (2.0, 2.5), (3.0, 3.5), (9.0, 9.0)];
        let b = route_body(&s, 2, false).unwrap();
        let v = parsed(&b);
        let mid = find(v.as_obj().unwrap(), "intermediates").and_then(Json::as_arr).unwrap();
        assert_eq!(mid.len(), 3);
        let got: Vec<(f64, f64)> = mid
            .iter()
            .map(|m| {
                let ll = find(find(m.as_obj().unwrap(), "location").and_then(Json::as_obj).unwrap(), "latLng")
                    .and_then(Json::as_obj)
                    .unwrap();
                let num = |k: &str| crate::store::to_double(find(ll, k).unwrap()).unwrap();
                (num("latitude"), num("longitude"))
            })
            .collect();
        assert_eq!(got, vec![(1.0, 1.5), (2.0, 2.5), (3.0, 3.5)]);
        assert_eq!(latlng(&v, "origin"), (0.0, 0.0));
        assert_eq!(latlng(&v, "destination"), (9.0, 9.0));
    }

    #[test]
    fn body_no_intermediates_for_two_stops() {
        let b = route_body(&[(1.0, 2.0), (3.0, 4.0)], 2, false).unwrap();
        assert!(!b.contains("intermediates"));
    }

    #[test]
    fn body_small_and_large_numbers_stay_plain_json() {
        let b = route_body(&[(0.00001, -0.0000005), (123456789.0, 1e-9)], 0, false).unwrap();
        assert!(!b.contains("e-") && !b.contains("e+"));
        let v = parsed(&b);
        assert_eq!(latlng(&v, "origin"), (0.00001, -0.0000005));
        assert_eq!(latlng(&v, "destination"), (123456789.0, 1e-9));
    }

    #[test]
    fn body_rejects_bad_input() {
        assert_eq!(route_body(&[], 0, false), None);
        assert_eq!(route_body(&[(1.0, 2.0)], 0, false), None);
        assert_eq!(route_body(&[(1.0, 2.0), (f64::NAN, 2.0)], 0, false), None);
        assert_eq!(route_body(&[(f64::INFINITY, 2.0), (1.0, 2.0)], 0, false), None);
    }

    #[test]
    fn google_url_encodes_polyline() {
        assert_eq!(
            elevation_url_google("_p~iF~ps|U", "KEY"),
            "https://maps.googleapis.com/maps/api/elevation/json?locations=enc:_p%7EiF%7Eps%7CU&key=KEY"
        );
    }

    #[test]
    fn open_meteo_url_uses_five_decimals() {
        assert_eq!(
            elevation_url_open_meteo(&[(-7.8, 110.123456), (0.0, -1.000004)]),
            "https://api.open-meteo.com/v1/elevation?latitude=-7.80000,0.00000&longitude=110.12346,-1.00000"
        );
    }

    #[test]
    fn lru_evicts_least_recently_used() {
        let mut c = Lru::new(3);
        c.put("a", vec![1.0]);
        c.put("b", vec![2.0]);
        c.put("c", vec![3.0]);
        assert_eq!(c.get("a"), Some(vec![1.0]));
        c.put("d", vec![4.0]);
        assert_eq!(c.len(), 3);
        assert_eq!(c.get("b"), None);
        assert_eq!(c.get("a"), Some(vec![1.0]));
        assert_eq!(c.get("c"), Some(vec![3.0]));
        assert_eq!(c.get("d"), Some(vec![4.0]));
    }

    #[test]
    fn lru_put_existing_refreshes_and_replaces() {
        let mut c = Lru::new(2);
        c.put("a", vec![1.0]);
        c.put("b", vec![2.0]);
        c.put("a", vec![9.0]);
        c.put("c", vec![3.0]);
        assert_eq!(c.get("b"), None);
        assert_eq!(c.get("a"), Some(vec![9.0]));
        assert_eq!(c.len(), 2);
    }

    #[test]
    fn lru_default_capacity_is_eight() {
        let mut c = Lru::new(ELEV_CACHE_SIZE);
        for i in 0..20 {
            c.put(&i.to_string(), vec![i as f32]);
        }
        assert_eq!(c.len(), 8);
        assert_eq!(c.get("11"), None);
        assert_eq!(c.get("12"), Some(vec![12.0]));
        assert_eq!(c.get("19"), Some(vec![19.0]));
    }
}
