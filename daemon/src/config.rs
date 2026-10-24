use crate::codec::numok;
use routecore::appstate::State;
use routecore::timeline::{cursor, read_tokens};
use std::path::{Path, PathBuf};

pub const DEFAULT_LAT: &str = "-7.7829";
pub const DEFAULT_LNG: &str = "110.3671";

#[derive(Clone, Default)]
pub struct Base {
    pub e: u8,
    pub r: u8,
    pub lat: String,
    pub lng: String,
    pub spd: String,
    pub brg: String,
    pub alt: String,
    pub acc: String,
    pub altv: String,
    pub rr: String,
}

#[derive(Clone)]
pub struct Route {
    pub t0: i64,
    pub tk: i64,
    pub nn: i64,
    pub he: i64,
    pub tlw: usize,
    pub tf: PathBuf,
    pub rnd: bool,
    pub trk: bool,
    pub t_lat: String,
    pub t_lng: String,
    pub t_alt: String,
}

pub struct Derived {
    pub base: Base,
    pub route: Option<Route>,
}

fn num_or(s: Option<&str>, d: &str) -> String {
    match s {
        Some(v) if numok(v) => v.to_string(),
        _ => d.to_string(),
    }
}

fn parse_i64(s: Option<&str>) -> Option<i64> {
    let v = s?;
    if numok(v) { v.parse().ok() } else { None }
}

pub fn timeline_path(pref: &Path) -> PathBuf {
    pref.with_file_name("route_timeline.txt")
}

pub fn derive(st: &State, pref: &Path, cur_bc: &str, trk_disabled: bool) -> Option<Derived> {
    let mut lat = st.get("lat").unwrap_or("").to_string();
    let mut lng = st.get("lng").unwrap_or("").to_string();
    let rnd = match st.get("random") {
        None | Some("") => true,
        Some(v) => v == "1",
    };
    let acc = match st.get("acc") {
        Some(a) if !a.is_empty() && a.bytes().all(|b| b.is_ascii_digit() || b == b'.') => a.to_string(),
        _ => "10".to_string(),
    };
    if lat.is_empty() { lat = DEFAULT_LAT.into(); }
    if lng.is_empty() { lng = DEFAULT_LNG.into(); }
    if !numok(&lat) || !numok(&lng) {
        return None;
    }
    let route_active = st.flag("route_active");
    let base = Base {
        e: st.flag("start") as u8,
        r: (rnd && !route_active) as u8,
        lat,
        lng,
        spd: num_or(st.get("spd"), "0"),
        brg: st.get("brg").unwrap_or("").to_string(),
        alt: num_or(st.get("alt"), "0"),
        acc,
        altv: num_or(st.get("altv"), "0"),
        rr: num_or(st.get("rr"), "8"),
    };
    let mut route = None;
    if st.flag("route_play") {
        let t0 = parse_i64(st.get("route_t0"));
        let tk = parse_i64(st.get("route_tick"));
        let nn = parse_i64(st.get("route_n"));
        let he = match st.get("route_hold_end") {
            None | Some("") => Some(0),
            v => parse_i64(v),
        };
        let bc = st.get("route_bc").unwrap_or("");
        let tlw = st.get("route_tlw").and_then(|v| if numok(v) { v.parse::<usize>().ok() } else { None }).unwrap_or(0);
        let tf = timeline_path(pref);
        if let (Some(t0), Some(tk), Some(nn), Some(he)) = (t0, tk, nn, he) {
            if tf.is_file() && bc == cur_bc && tk > 0 && nn > 0 {
                let mut rt = Route { t0, tk, nn, he, tlw, tf, rnd, trk: false, t_lat: String::new(), t_lng: String::new(), t_alt: String::new() };
                if tlw > 0 && !trk_disabled {
                    if let Some(t) = read_tokens(&rt.tf, tlw, 1) {
                        if t.len() >= 5 && numok(&t[0]) && numok(&t[1]) && numok(&t[4]) {
                            rt.trk = true;
                            rt.t_lat = t[0].clone();
                            rt.t_lng = t[1].clone();
                            rt.t_alt = t[4].clone();
                        }
                    }
                }
                route = Some(rt);
            }
        }
    }
    Some(Derived { base, route })
}

pub struct Applied {
    pub e: u8,
    pub r: u8,
    pub lat: String,
    pub lng: String,
    pub spd: String,
    pub brg: String,
    pub alt: String,
    pub pl: &'static str,
    pub idx: i64,
}

pub fn route_apply(b: &Base, rt: &Route, now_cs: i64) -> Applied {
    let cur = cursor(now_cs, rt.t0, rt.tk, rt.nn);
    let (idx, ended, el) = (cur.idx, cur.ended, cur.elapsed_ms);
    let mut a = static_apply(b);
    a.idx = idx;
    if let Some(t) = read_tokens(&rt.tf, rt.tlw, idx) {
        if t.len() >= 5 && t[..5].iter().all(|x| numok(x)) {
            a.lat = t[0].clone();
            a.lng = t[1].clone();
            a.spd = t[2].clone();
            a.brg = t[3].clone();
            a.alt = t[4].clone();
            a.e = 1;
            a.pl = ";pl=1";
            if ended {
                a.r = rt.rnd as u8;
                if rt.he > 0 && el >= (rt.nn - 1) * rt.tk + rt.he {
                    a.e = 0;
                    a.pl = "";
                }
            } else {
                a.r = 0;
            }
            if rt.trk && a.pl == ";pl=1" {
                a.pl = ";tk=1;pl=1";
                a.lat = rt.t_lat.clone();
                a.lng = rt.t_lng.clone();
                a.spd = "0".into();
                a.brg = String::new();
                a.alt = rt.t_alt.clone();
            }
        }
    }
    a
}

pub fn compose(b: &Base, a: &Applied) -> String {
    let mut x = format!(";spd={};alt={};altv={};rr={}", a.spd, a.alt, b.altv, b.rr);
    if numok(&a.brg) {
        x.push_str(&format!(";brg={}", a.brg));
    }
    format!("enabled={};lat={};lng={};acc={};random={}{}{}", a.e, a.lat, a.lng, b.acc, a.r, x, a.pl)
}

pub fn static_apply(b: &Base) -> Applied {
    Applied { e: b.e, r: b.r, lat: b.lat.clone(), lng: b.lng.clone(), spd: b.spd.clone(), brg: b.brg.clone(), alt: b.alt.clone(), pl: "", idx: 0 }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    const PX: &str = "start:1\nlat:-7.25\nlng:110.5\nacc:12\nrandom:0\nspd:3.5\nbrg:90\n";
    const ROUTE: &str = "route_play:1\nroute_t0:1000\nroute_tick:1000\nroute_n:3\nroute_bc:7\n";

    fn state(text: &str) -> State {
        State::parse(text)
    }

    fn dir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("setlocd-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(d.join("files")).unwrap();
        d
    }

    #[test]
    fn static_config() {
        let d = derive(&state(PX), Path::new("/x/files/setloc_state"), "1", false).unwrap();
        let a = static_apply(&d.base);
        assert_eq!(
            compose(&d.base, &a),
            "enabled=1;lat=-7.25;lng=110.5;acc=12;random=0;spd=3.5;alt=0;altv=0;rr=8;brg=90"
        );
    }

    #[test]
    fn route_progress() {
        let dir = dir("t");
        fs::write(dir.join("files/route_timeline.txt"), "1 2 3 4 5\n6 7 8 9 10\n11 12 13 14 15\n").unwrap();
        let pref = dir.join("files/setloc_state");
        let d = derive(&state(&format!("{}{}", PX, ROUTE)), &pref, "7", false).unwrap();
        let rt = d.route.as_ref().unwrap();
        let a = route_apply(&d.base, rt, 1000);
        assert_eq!((a.lat.as_str(), a.pl, a.idx), ("1", ";pl=1", 1));
        let a = route_apply(&d.base, rt, 1100);
        assert_eq!((a.lat.as_str(), a.idx), ("6", 2));
        let a = route_apply(&d.base, rt, 5000);
        assert_eq!((a.lat.as_str(), a.idx), ("11", 3));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn route_apply_reads_routecore_timeline() {
        use routecore::timeline::{build, format, Rng, LINE_WIDTH};
        let dir = dir("rc");
        let pts = [(-7.7, 110.4), (-7.69, 110.41), (-7.68, 110.40)];
        let ticks = build(&pts, 40.0, -1.0, &[10.0, 20.0], 0.0, &mut Rng::new(5));
        fs::write(dir.join("files/route_timeline.txt"), format(&ticks)).unwrap();
        let text = format!(
            "{}route_play:1\nroute_t0:1000\nroute_tick:500\nroute_n:{}\nroute_bc:7\nroute_tlw:{}\n",
            PX,
            ticks.len(),
            LINE_WIDTH
        );
        let d = derive(&state(&text), &dir.join("files/setloc_state"), "7", true).unwrap();
        let rt = d.route.as_ref().unwrap();
        assert_eq!(rt.tlw, LINE_WIDTH);
        for k in [0usize, 1, ticks.len() / 2, ticks.len() - 1] {
            let a = route_apply(&d.base, rt, 1000 + (k as i64) * 50);
            assert_eq!(a.idx, k as i64 + 1);
            let t = &ticks[k];
            assert!((a.lat.parse::<f64>().unwrap() - t.lat).abs() < 1e-6);
            assert!((a.lng.parse::<f64>().unwrap() - t.lng).abs() < 1e-6);
            assert!((a.alt.parse::<f64>().unwrap() - t.alt as f64).abs() < 0.006);
        }
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stale_boot_count_ignored() {
        let text = format!("{}route_play:1\nroute_bc:7\n", PX);
        let d = derive(&state(&text), Path::new("/x/files/setloc_state"), "8", false).unwrap();
        assert!(d.route.is_none());
    }

    #[test]
    fn timeline_sits_next_to_state_file() {
        assert_eq!(
            timeline_path(Path::new("/d/user/0/app/files/setloc_state")),
            PathBuf::from("/d/user/0/app/files/route_timeline.txt")
        );
    }

    #[test]
    fn empty_random_defaults_true() {
        let d = derive(&state("random:\nlat:1\nlng:2\n"), Path::new("/x/files/setloc_state"), "1", false).unwrap();
        assert_eq!(d.base.r, 1);
    }

    #[test]
    fn missing_values_use_defaults_and_bad_coords_fail() {
        let d = derive(&State::default(), Path::new("/x/files/setloc_state"), "1", false).unwrap();
        assert_eq!(compose(&d.base, &static_apply(&d.base)), "enabled=0;lat=-7.7829;lng=110.3671;acc=10;random=1;spd=0;alt=0;altv=0;rr=8");
        assert!(derive(&state("lat:abc\nlng:1\n"), Path::new("/x/files/setloc_state"), "1", false).is_none());
        let d = derive(&state("lat:1\nlng:2\nacc:-5\n"), Path::new("/x/files/setloc_state"), "1", false).unwrap();
        assert_eq!(d.base.acc, "10");
    }

    #[test]
    fn route_active_disables_random() {
        let d = derive(&state("lat:1\nlng:2\nrandom:1\nroute_active:1\n"), Path::new("/x/files/setloc_state"), "1", false).unwrap();
        assert_eq!(d.base.r, 0);
    }
}
