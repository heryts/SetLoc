use crate::file::{read_fields, FIELD_MAX};
use crate::track;
use std::fs;
use std::time::UNIX_EPOCH;

pub struct Watch {
    fields: [Option<String>; FIELD_MAX],
    seen: Option<String>,
    check_at: i64,
}

fn stat(path: &str) -> (i64, i64) {
    match fs::metadata(path) {
        Ok(m) => {
            let mt = m
                .modified()
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map_or(0, |d| d.as_millis() as i64);
            (mt, m.len() as i64)
        }
        Err(_) => (0, 0),
    }
}

impl Watch {
    pub const fn new() -> Watch {
        Watch { fields: [None, None, None, None], seen: None, check_at: 0 }
    }

    pub fn reload(&mut self, paths: &[&str], now: i64, interval: i64, empty: bool) {
        if now.wrapping_sub(self.check_at) < interval {
            return;
        }
        self.check_at = now;
        for path in paths {
            let (mt, len) = stat(path);
            if len <= 0 {
                continue;
            }
            let sig = format!("{}:{}:{}", path, mt, len);
            if self.seen.as_deref() == Some(sig.as_str()) {
                return;
            }
            let f = match read_fields(path) {
                Some(f) => f,
                None => continue,
            };
            self.fields = f;
            self.seen = Some(sig);
            return;
        }
        if empty {
            self.fields = [None, None, None, None];
            self.seen = None;
        }
    }

    pub fn load(&mut self, paths: &[&str]) -> bool {
        for path in paths {
            if let Some(f) = read_fields(path) {
                self.fields = f;
                return true;
            }
        }
        false
    }

    pub fn cfg(&self) -> Option<&str> {
        self.fields[0].as_deref()
    }

    fn pick<'a>(&'a self, v: Option<&'a str>, slot: usize, use_file: bool) -> Option<&'a str> {
        if v.is_none() && use_file && self.fields[0].is_some() {
            self.fields[slot].as_deref()
        } else {
            v
        }
    }

    pub fn or_cfg(&self, v: Option<String>, use_file: bool) -> Option<String> {
        match v {
            None => self.pick(None, 0, use_file).map(str::to_string),
            some => some,
        }
    }

    pub fn flags(&self, sys: Option<&str>, net: Option<&str>, bt: Option<&str>, use_file: bool) -> i32 {
        track::flags(self.pick(sys, 1, use_file), self.pick(net, 2, use_file), self.pick(bt, 3, use_file))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn tmp(name: &str, body: &str) -> String {
        let p = std::env::temp_dir().join(format!("fw_{}_{}", std::process::id(), name));
        fs::File::create(&p).unwrap().write_all(body.as_bytes()).unwrap();
        p.to_string_lossy().into_owned()
    }

    #[test]
    fn load_and_pick() {
        let p = tmp("a", "cfg:enabled=1\nsys:2\nnet:0\n");
        let mut w = Watch::new();
        assert!(w.load(&[p.as_str()]));
        assert_eq!(w.cfg(), Some("enabled=1"));
        assert_eq!(w.or_cfg(None, true).as_deref(), Some("enabled=1"));
        assert_eq!(w.or_cfg(None, false), None);
        assert_eq!(w.or_cfg(Some("x".into()), true).as_deref(), Some("x"));
        assert_eq!(w.flags(None, None, None, true), track::flags(Some("2"), Some("0"), None));
        assert_eq!(w.flags(None, None, None, false), track::flags(None, None, None));
        assert_eq!(w.flags(Some("1"), None, Some("1"), true), track::flags(Some("1"), Some("0"), Some("1")));
        let _ = fs::remove_file(&p);
    }

    #[test]
    fn reload_interval_seen_and_clear() {
        let p = tmp("b", "cfg:a=1\n");
        let mut w = Watch::new();
        w.reload(&[p.as_str()], 10_000, 2_000, false);
        assert_eq!(w.cfg(), Some("a=1"));
        fs::write(&p, "cfg:a=22\n").unwrap();
        w.reload(&[p.as_str()], 10_500, 2_000, false);
        assert_eq!(w.cfg(), Some("a=1"));
        w.reload(&[p.as_str()], 12_500, 2_000, false);
        assert_eq!(w.cfg(), Some("a=22"));
        let _ = fs::remove_file(&p);
        w.reload(&[p.as_str()], 20_000, 2_000, false);
        assert_eq!(w.cfg(), Some("a=22"));
        w.reload(&[p.as_str()], 30_000, 250, true);
        assert_eq!(w.cfg(), None);
        assert_eq!(w.flags(None, None, None, true), track::flags(None, None, None));
    }

    #[test]
    fn load_missing() {
        let mut w = Watch::new();
        assert!(!w.load(&["/nonexistent/gpsz_cfg_zz"]));
        assert_eq!(w.cfg(), None);
    }
}
