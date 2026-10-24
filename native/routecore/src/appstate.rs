use crate::prefs;
use std::fs;
use std::io;
use std::os::unix::fs::{chown, MetadataExt, PermissionsExt};
use std::path::Path;
use std::sync::Mutex;

pub const FILE: &str = "setloc_state";

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Bool,
    Int,
    Num(&'static str),
    Lat,
    Lng,
    Acc,
}

const SCHEMA: &[(&str, Kind)] = &[
    ("start", Kind::Bool),
    ("lat", Kind::Lat),
    ("lng", Kind::Lng),
    ("random", Kind::Bool),
    ("acc", Kind::Acc),
    ("spd", Kind::Num("0")),
    ("brg", Kind::Num("-1")),
    ("alt", Kind::Num("0")),
    ("altv", Kind::Num("0")),
    ("rr", Kind::Num("8")),
    ("route_active", Kind::Bool),
    ("route_play", Kind::Bool),
    ("route_t0", Kind::Int),
    ("route_tick", Kind::Int),
    ("route_n", Kind::Int),
    ("route_hold_end", Kind::Int),
    ("route_bc", Kind::Int),
    ("route_tlw", Kind::Int),
];

static LOCK: Mutex<()> = Mutex::new(());

fn kind(key: &str) -> Option<Kind> {
    SCHEMA.iter().find(|e| e.0 == key).map(|e| e.1)
}

fn coord(v: &str, max: f64) -> Option<String> {
    let d: f64 = v.trim().parse().ok()?;
    if d.is_finite() && d.abs() <= max {
        Some(prefs::coord(d))
    } else {
        None
    }
}

fn normalize(k: Kind, v: &str) -> Option<String> {
    match k {
        Kind::Bool => match v {
            "1" | "true" => Some("1".into()),
            "0" | "false" => Some("0".into()),
            _ => None,
        },
        Kind::Int => v.trim().parse::<i64>().ok().map(|n| n.to_string()),
        Kind::Num(default) => match v.trim().parse::<f64>() {
            Ok(d) if d.is_finite() => Some(d.to_string()),
            Ok(_) => Some(default.to_string()),
            Err(_) => None,
        },
        Kind::Lat => coord(v, 90.0),
        Kind::Lng => coord(v, 180.0),
        Kind::Acc => {
            let ok = !v.is_empty()
                && v.len() <= 12
                && v.bytes().all(|b| b.is_ascii_digit() || b == b'.')
                && v.matches('.').count() <= 1
                && v.bytes().any(|b| b.is_ascii_digit());
            ok.then(|| v.to_string())
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct State {
    pairs: Vec<(String, String)>,
}

impl State {
    pub fn parse(text: &str) -> State {
        let mut s = State::default();
        for line in text.lines() {
            if let Some((k, v)) = line.split_once(':') {
                if kind(k).is_some() {
                    s.put(k, v.trim_end_matches('\r'));
                }
            }
        }
        s
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.pairs.iter().find(|e| e.0 == key).map(|e| e.1.as_str())
    }

    pub fn flag(&self, key: &str) -> bool {
        self.get(key) == Some("1")
    }

    pub fn int(&self, key: &str) -> Option<i64> {
        self.get(key)?.parse().ok()
    }

    pub fn render(&self) -> String {
        let mut out = String::new();
        for (k, v) in &self.pairs {
            out.push_str(k);
            out.push(':');
            out.push_str(v);
            out.push('\n');
        }
        out
    }

    fn put(&mut self, key: &str, val: &str) {
        match self.pairs.iter_mut().find(|e| e.0 == key) {
            Some(e) => e.1 = val.to_string(),
            None => self.pairs.push((key.to_string(), val.to_string())),
        }
    }

    fn drop_key(&mut self, key: &str) {
        self.pairs.retain(|e| e.0 != key);
    }

    pub fn apply(&mut self, patch: &str) -> bool {
        let mut ops: Vec<(&str, Option<String>)> = Vec::new();
        for line in patch.lines().filter(|l| !l.is_empty()) {
            if let Some(key) = line.strip_prefix('-') {
                if kind(key).is_none() {
                    return false;
                }
                ops.push((key, None));
                continue;
            }
            let Some((key, val)) = line.split_once(':') else {
                return false;
            };
            let Some(k) = kind(key) else {
                return false;
            };
            let Some(v) = normalize(k, val) else {
                return false;
            };
            ops.push((key, Some(v)));
        }
        for (key, val) in ops {
            match val {
                Some(v) => self.put(key, &v),
                None => self.drop_key(key),
            }
        }
        true
    }

    pub fn route_state(&self, boot_now: i64, now_ms: i64) -> i32 {
        prefs::route_state(
            self.flag("route_play"),
            self.int("route_t0").unwrap_or(0),
            self.int("route_n").unwrap_or(1),
            self.int("route_tick").unwrap_or(0),
            self.int("route_hold_end").unwrap_or(0),
            self.int("route_bc").unwrap_or(-1),
            boot_now,
            now_ms,
        )
    }
}

pub fn load(path: &Path) -> State {
    fs::read_to_string(path).map(|t| State::parse(&t)).unwrap_or_default()
}

pub fn save(path: &Path, st: &State, prepare: &dyn Fn(&Path)) -> io::Result<()> {
    let tmp = format!("{}.tmp", path.display());
    let tmp = Path::new(&tmp);
    fs::write(tmp, st.render())?;
    match fs::metadata(path) {
        Ok(m) => {
            let _ = chown(tmp, Some(m.uid()), Some(m.gid()));
            let _ = fs::set_permissions(tmp, fs::Permissions::from_mode(m.mode() & 0o7777));
        }
        Err(_) => {
            let _ = fs::set_permissions(tmp, fs::Permissions::from_mode(0o600));
        }
    }
    prepare(tmp);
    fs::rename(tmp, path).inspect_err(|_| {
        let _ = fs::remove_file(tmp);
    })
}

pub fn update(path: &Path, patch: &str, prepare: &dyn Fn(&Path)) -> bool {
    let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut st = load(path);
    st.apply(patch) && save(path, &st, prepare).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("appstate-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d.join(FILE)
    }

    fn st(patch: &str) -> State {
        let mut s = State::default();
        assert!(s.apply(patch), "{}", patch);
        s
    }

    #[test]
    fn coords_use_seven_decimals_and_range() {
        let s = st("lat:-7.7829\nlng:110.0");
        assert_eq!(s.get("lat"), Some("-7.7829000"));
        assert_eq!(s.get("lng"), Some("110.0000000"));
        let mut s = State::default();
        assert!(!s.apply("lat:91"));
        assert!(!s.apply("lng:-180.5"));
        assert!(!s.apply("lat:NaN"));
        assert!(!s.apply("lat:abc"));
        assert_eq!(s, State::default());
    }

    #[test]
    fn numbers_never_use_exponent_and_bad_floats_fall_back() {
        let s = st("spd:1.0E-4\nalt:-12.5\nbrg:NaN\nrr:Infinity\naltv:3");
        assert_eq!(s.get("spd"), Some("0.0001"));
        assert_eq!(s.get("alt"), Some("-12.5"));
        assert_eq!(s.get("brg"), Some("-1"));
        assert_eq!(s.get("rr"), Some("8"));
        assert_eq!(s.get("altv"), Some("3"));
        let mut s = State::default();
        assert!(!s.apply("spd:fast"));
    }

    #[test]
    fn booleans_and_ints() {
        let s = st("start:true\nrandom:0\nroute_play:1\nroute_t0:12345\nroute_bc:-1");
        assert!(s.flag("start"));
        assert!(!s.flag("random"));
        assert_eq!(s.int("route_t0"), Some(12345));
        assert_eq!(s.int("route_bc"), Some(-1));
        let mut s = State::default();
        assert!(!s.apply("start:yes"));
        assert!(!s.apply("route_n:1.5"));
    }

    #[test]
    fn accuracy_accepts_plain_decimals_only() {
        assert_eq!(st("acc:12.5").get("acc"), Some("12.5"));
        for bad in ["acc:", "acc:.", "acc:-5", "acc:1e3", "acc:1.2.3", "acc:abc", "acc:1234567890123"] {
            assert!(!State::default().apply(bad), "{}", bad);
        }
    }

    #[test]
    fn patch_is_all_or_nothing_and_rejects_unknown_keys() {
        let mut s = st("lat:1\nlng:2");
        let before = s.clone();
        assert!(!s.apply("lat:5\nlng:oops"));
        assert!(!s.apply("lat:5\nnope:1"));
        assert!(!s.apply("-nope"));
        assert!(!s.apply("lat"));
        assert_eq!(s, before);
    }

    #[test]
    fn removal_and_overwrite() {
        let mut s = st("route_t0:1\nroute_n:2\nroute_tlw:48\nlat:3");
        assert!(s.apply("-route_t0\n-route_n\n-route_tlw\nlat:4"));
        assert_eq!(s.get("route_t0"), None);
        assert_eq!(s.get("lat"), Some("4.0000000"));
        assert_eq!(s.render(), "lat:4.0000000\n");
        assert!(s.apply(""));
    }

    #[test]
    fn parse_ignores_unknown_and_malformed_lines() {
        let s = State::parse("lat:1.5\r\nbogus:1\nnocolon\nstart:1\n");
        assert_eq!(s.get("lat"), Some("1.5"));
        assert_eq!(s.get("bogus"), None);
        assert!(s.flag("start"));
        assert_eq!(State::parse(&s.render()), s);
    }

    #[test]
    fn update_roundtrips_through_file_and_keeps_other_keys() {
        let p = tmp("rt");
        assert!(update(&p, "start:1\nlat:-7.25\nlng:110.5", &|_| {}));
        assert!(update(&p, "spd:3.5", &|_| {}));
        assert!(!update(&p, "lat:999", &|_| {}));
        let s = load(&p);
        assert!(s.flag("start"));
        assert_eq!(s.get("lat"), Some("-7.2500000"));
        assert_eq!(s.get("spd"), Some("3.5"));
        assert!(!Path::new(&format!("{}.tmp", p.display())).exists());
        let _ = fs::remove_dir_all(p.parent().unwrap());
    }

    #[test]
    fn update_keeps_mode_and_runs_prepare_before_rename() {
        let p = tmp("mode");
        fs::write(&p, "start:0\n").unwrap();
        fs::set_permissions(&p, fs::Permissions::from_mode(0o640)).unwrap();
        let seen = Mutex::new(String::new());
        assert!(update(&p, "start:1", &|t| *seen.lock().unwrap() = t.display().to_string()));
        assert_eq!(*seen.lock().unwrap(), format!("{}.tmp", p.display()));
        assert_eq!(fs::metadata(&p).unwrap().mode() & 0o777, 0o640);
        let _ = fs::remove_dir_all(p.parent().unwrap());
    }

    #[test]
    fn concurrent_updates_do_not_lose_keys() {
        let p = tmp("mt");
        assert!(update(&p, "start:0", &|_| {}));
        let keys = ["spd", "alt", "altv", "rr", "brg", "route_n", "route_t0", "route_tick"];
        std::thread::scope(|sc| {
            for (i, k) in keys.iter().enumerate() {
                let p = &p;
                sc.spawn(move || {
                    for j in 0..20 {
                        assert!(update(p, &format!("{}:{}", k, i * 100 + j), &|_| {}));
                    }
                });
            }
        });
        let s = load(&p);
        for (i, k) in keys.iter().enumerate() {
            assert_eq!(s.get(k).and_then(|v| v.parse::<usize>().ok()), Some(i * 100 + 19), "{}", k);
        }
        let _ = fs::remove_dir_all(p.parent().unwrap());
    }

    #[test]
    fn route_state_reads_fields_with_legacy_defaults() {
        assert_eq!(State::default().route_state(7, 0), prefs::ROUTE_NONE);
        let s = st("route_play:1\nroute_t0:100\nroute_n:5\nroute_tick:500\nroute_hold_end:1\nroute_bc:7");
        assert_eq!(s.route_state(7, 2999), prefs::ROUTE_LIVE);
        assert_eq!(s.route_state(7, 3001), prefs::ROUTE_ENDED_OFF);
        assert_eq!(s.route_state(6, 2999), prefs::ROUTE_NONE);
    }
}
