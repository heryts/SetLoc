use crate::codec::numok;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

pub const DEFAULT_LAT: &str = "-7.7829";
pub const DEFAULT_LNG: &str = "110.3671";

pub fn fval<'a>(px: &'a str, ty: &str, name: &str) -> Option<&'a str> {
    let pat = format!("<{} name=\"{}\" value=\"", ty, name);
    let rest = &px[px.find(&pat)? + pat.len()..];
    Some(&rest[..rest.find('"')?])
}

pub fn fstr<'a>(px: &'a str, name: &str) -> Option<&'a str> {
    let pat = format!("<string name=\"{}\">", name);
    let rest = &px[px.find(&pat)? + pat.len()..];
    Some(&rest[..rest.find('<')?])
}

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

fn timeline_tokens(tf: &Path, tlw: usize, idx: i64) -> Option<Vec<String>> {
    let mut f = File::open(tf).ok()?;
    if tlw > 0 {
        let mut buf = vec![0u8; tlw];
        f.seek(SeekFrom::Start((idx as u64 - 1) * tlw as u64)).ok()?;
        let n = f.read(&mut buf).ok()?;
        let line = String::from_utf8_lossy(&buf[..n]).into_owned();
        Some(line.split_whitespace().map(String::from).collect())
    } else {
        let mut s = String::new();
        f.read_to_string(&mut s).ok()?;
        let line = s.lines().nth(idx as usize - 1)?;
        Some(line.split_whitespace().map(String::from).collect())
    }
}

pub fn timeline_path(pref: &Path) -> PathBuf {
    let p = pref.to_string_lossy();
    let base = match p.rfind("/shared_prefs/") {
        Some(i) => &p[..i],
        None => &p,
    };
    PathBuf::from(format!("{}/files/route_timeline.txt", base))
}

pub fn derive(px: &str, pref: &Path, cur_bc: &str, trk_disabled: bool) -> Option<Derived> {
    let start = fval(px, "boolean", "start");
    let mut lat = fval(px, "float", "latitude").unwrap_or("").to_string();
    let mut lng = fval(px, "float", "longitude").unwrap_or("").to_string();
    if let (Some(ld), Some(lg)) = (fstr(px, "latitude_d"), fstr(px, "longitude_d")) {
        if numok(ld) && numok(lg) {
            lat = ld.to_string();
            lng = lg.to_string();
        }
    }
    let rnd = match fval(px, "boolean", "random_position") {
        None | Some("") => true,
        Some(v) => v == "true",
    };
    let acc = match fstr(px, "accuracy_settings") {
        Some(a) if !a.is_empty() && a.bytes().all(|b| b.is_ascii_digit() || b == b'.') => a.to_string(),
        _ => "10".to_string(),
    };
    if lat.is_empty() { lat = DEFAULT_LAT.into(); }
    if lng.is_empty() { lng = DEFAULT_LNG.into(); }
    if !numok(&lat) || !numok(&lng) {
        return None;
    }
    let route_active = fval(px, "boolean", "route_active") == Some("true");
    let base = Base {
        e: (start == Some("true")) as u8,
        r: (rnd && !route_active) as u8,
        lat,
        lng,
        spd: num_or(fval(px, "float", "speed_ms"), "0"),
        brg: fval(px, "float", "bearing").unwrap_or("").to_string(),
        alt: num_or(fval(px, "float", "altitude"), "0"),
        acc,
        altv: num_or(fval(px, "float", "altitude_var"), "0"),
        rr: num_or(fval(px, "float", "random_radius"), "8"),
    };
    let mut route = None;
    if fval(px, "boolean", "route_play") == Some("true") {
        let t0 = parse_i64(fval(px, "long", "route_t0"));
        let tk = parse_i64(fval(px, "long", "route_tick"));
        let nn = parse_i64(fval(px, "int", "route_n"));
        let he = match fval(px, "long", "route_hold_end") {
            None | Some("") => Some(0),
            v => parse_i64(v),
        };
        let bc = fval(px, "int", "route_bc").unwrap_or("");
        let tlw = fval(px, "int", "route_tlw").and_then(|v| if numok(v) { v.parse::<usize>().ok() } else { None }).unwrap_or(0);
        let tf = timeline_path(pref);
        if let (Some(t0), Some(tk), Some(nn), Some(he)) = (t0, tk, nn, he) {
            if tf.is_file() && bc == cur_bc && tk > 0 && nn > 0 {
                let mut rt = Route { t0, tk, nn, he, tlw, tf, rnd, trk: false, t_lat: String::new(), t_lng: String::new(), t_alt: String::new() };
                if tlw > 0 && !trk_disabled {
                    if let Some(t) = timeline_tokens(&rt.tf, tlw, 1) {
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
    let el = (now_cs - rt.t0) * 10;
    let mut idx = if el < 0 { 1 } else { el / rt.tk + 1 };
    let ended = idx >= rt.nn;
    if ended { idx = rt.nn; }
    let mut a = static_apply(b);
    a.idx = idx;
    if let Some(t) = timeline_tokens(&rt.tf, rt.tlw, idx) {
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

    const PX: &str = r#"<map>
    <boolean name="start" value="true" />
    <float name="latitude" value="-7.25" />
    <float name="longitude" value="110.5" />
    <string name="accuracy_settings">12</string>
    <boolean name="random_position" value="false" />
    <float name="speed_ms" value="3.5" />
    <float name="bearing" value="90.0" />
</map>"#;

    #[test]
    fn static_config() {
        let d = derive(PX, Path::new("/x/shared_prefs/p.xml"), "1", false).unwrap();
        let a = static_apply(&d.base);
        assert_eq!(
            compose(&d.base, &a),
            "enabled=1;lat=-7.25;lng=110.5;acc=12;random=0;spd=3.5;alt=0;altv=0;rr=8;brg=90.0"
        );
    }

    #[test]
    fn route_progress() {
        let dir = std::env::temp_dir().join(format!("setlocd-t-{}", std::process::id()));
        fs::create_dir_all(dir.join("files")).unwrap();
        fs::create_dir_all(dir.join("shared_prefs")).unwrap();
        fs::write(dir.join("files/route_timeline.txt"), "1 2 3 4 5\n6 7 8 9 10\n11 12 13 14 15\n").unwrap();
        let px = format!(
            "{}\n<boolean name=\"route_play\" value=\"true\" /><long name=\"route_t0\" value=\"1000\" /><long name=\"route_tick\" value=\"1000\" /><int name=\"route_n\" value=\"3\" /><int name=\"route_bc\" value=\"7\" />",
            PX
        );
        let pref = dir.join("shared_prefs/p.xml");
        let d = derive(&px, &pref, "7", false).unwrap();
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
    fn stale_boot_count_ignored() {
        let px = format!("{}<boolean name=\"route_play\" value=\"true\" /><int name=\"route_bc\" value=\"7\" />", PX);
        let d = derive(&px, Path::new("/x/shared_prefs/p.xml"), "8", false).unwrap();
        assert!(d.route.is_none());
    }

    #[test]
    fn timeline_path_follows_shared_prefs() {
        assert_eq!(
            timeline_path(Path::new("/d/user/0/app/shared_prefs/p.xml")),
            PathBuf::from("/d/user/0/app/files/route_timeline.txt")
        );
        assert_eq!(
            timeline_path(Path::new("/d/misc/prefs/app/p.xml")),
            PathBuf::from("/d/misc/prefs/app/p.xml/files/route_timeline.txt")
        );
    }

    #[test]
    fn empty_random_position_defaults_true() {
        let px = "<map><boolean name=\"random_position\" value=\"\" /><float name=\"latitude\" value=\"1\" /><float name=\"longitude\" value=\"2\" /></map>";
        let d = derive(px, Path::new("/x/shared_prefs/p.xml"), "1", false).unwrap();
        assert_eq!(d.base.r, 1);
    }
}
