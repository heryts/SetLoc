use crate::num::{jtrim, plain_decimal, plain_integer};
use crate::track::Track;
use std::fs::{self, File};
use std::io::Read;

pub const FIELD_MAX: usize = 4;
const FIELDS_FILE_MAX: u64 = 8192;
const TRACK_FILE_MAX: u64 = 16 << 20;

pub fn parse_fields(s: &str) -> Option<[Option<String>; FIELD_MAX]> {
    let mut out: [Option<String>; FIELD_MAX] = [None, None, None, None];
    for line in s.split('\n') {
        let (k, v) = match line.split_once(':') {
            Some(p) => p,
            None => continue,
        };
        let v = jtrim(v);
        if v.is_empty() || v == "null" {
            continue;
        }
        let slot = match jtrim(k) {
            "cfg" => 0,
            "sys" => 1,
            "net" => 2,
            "bt" => 3,
            _ => continue,
        };
        out[slot] = Some(v.to_string());
    }
    if out[0].is_none() {
        None
    } else {
        Some(out)
    }
}

pub fn read_fields(path: &str) -> Option<[Option<String>; FIELD_MAX]> {
    let mut buf = Vec::new();
    File::open(path).ok()?.take(FIELDS_FILE_MAX).read_to_end(&mut buf).ok()?;
    if buf.is_empty() {
        return None;
    }
    parse_fields(&String::from_utf8_lossy(&buf))
}

pub fn read_track_file(path: &str) -> Option<Track> {
    let len = fs::metadata(path).ok()?.len();
    if len == 0 || len > TRACK_FILE_MAX {
        return None;
    }
    let mut buf = Vec::with_capacity(len as usize);
    File::open(path).ok()?.take(len).read_to_end(&mut buf).ok()?;
    if buf.len() as u64 != len {
        return None;
    }
    let s: String = buf.iter().map(|&b| if b < 0x80 { b as char } else { '\u{FFFD}' }).collect();
    parse_track_file(&s, len as i64)
}

pub fn parse_track_file(s: &str, file_len: i64) -> Option<Track> {
    let nl = s.find('\n')?;
    let mut t0: Option<i64> = None;
    let mut tk: i64 = 0;
    let mut n: i64 = 0;
    for kv in s[..nl].split(';') {
        let (k, v) = match kv.split_once('=') {
            Some(p) => p,
            None => continue,
        };
        let v = jtrim(v);
        match jtrim(k) {
            "t" => {
                if !plain_integer(v) {
                    return None;
                }
                t0 = Some(v.parse().ok()?);
            }
            "k" => {
                if !plain_integer(v) {
                    return None;
                }
                tk = v.parse::<i32>().ok()? as i64;
            }
            "n" => {
                if !plain_integer(v) {
                    return None;
                }
                n = v.parse::<i32>().ok()? as i64;
            }
            _ => {}
        }
    }
    let t0 = t0?;
    if tk <= 0 || n <= 0 || n > file_len / 10 {
        return None;
    }
    let n = n as usize;
    let mut la = Vec::with_capacity(n);
    let mut lo = Vec::with_capacity(n);
    let mut al = Vec::with_capacity(n);
    let mut sp = Vec::with_capacity(n);
    let mut br = Vec::with_capacity(n);
    let mut tok = s[nl + 1..].split(|c: char| matches!(c, ' ' | '\t' | '\n' | '\r' | '\u{c}')).filter(|x| !x.is_empty());
    let mut next = || -> Option<&str> {
        let x = tok.next()?;
        if plain_decimal(x) {
            Some(x)
        } else {
            None
        }
    };
    for _ in 0..n {
        la.push(next()?.parse().ok()?);
        lo.push(next()?.parse().ok()?);
        sp.push(next()?.parse().ok()?);
        br.push(next()?.parse().ok()?);
        al.push(next()?.parse().ok()?);
    }
    Some(Track { t0, wrote: t0, tk, la, lo, al, sp, br })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn tmp(name: &str, body: &[u8]) -> String {
        let p = std::env::temp_dir().join(format!("configcore_{}_{}", std::process::id(), name));
        File::create(&p).unwrap().write_all(body).unwrap();
        p.to_string_lossy().into_owned()
    }

    #[test]
    fn read_fields_from_file() {
        let p = tmp("f1", b"cfg:enabled=1\nsys:2\n");
        let f = read_fields(&p).unwrap();
        assert_eq!(f[0].as_deref(), Some("enabled=1"));
        assert_eq!(f[1].as_deref(), Some("2"));
        assert!(read_fields(&tmp("f2", b"")).is_none());
        assert!(read_fields(&tmp("f3", b"sys:1\n")).is_none());
        assert!(read_fields("/nonexistent/gpsz").is_none());
    }

    #[test]
    fn read_fields_stops_at_limit() {
        let mut body = b"cfg:enabled=1\n".to_vec();
        for i in 0..2000 {
            body.extend_from_slice(format!("zzz:{}\n", i).as_bytes());
        }
        body.extend_from_slice(b"sys:9\n");
        let f = read_fields(&tmp("f4", &body)).unwrap();
        assert_eq!(f[0].as_deref(), Some("enabled=1"));
        assert!(f[1].is_none());
    }

    #[test]
    fn read_track_file_cases() {
        let t = read_track_file(&tmp("t1", b"t=1000;k=500;n=1\n1 2 3 4 5\n")).unwrap();
        assert_eq!((t.t0, t.tk, t.la.len()), (1000, 500, 1));
        assert!(read_track_file(&tmp("t2", b"t=1;k=500;n=3\n1 2\n")).is_none());
        assert!(read_track_file(&tmp("t3", b"")).is_none());
        assert!(read_track_file("/nonexistent/gpsz").is_none());
        assert!(read_track_file(&tmp("t4", b"t=1000;k=500;n=1\n1 2 3 \xff 5\n")).is_none());
    }

    #[test]
    fn fields_are_read_and_trimmed() {
        let f = parse_fields("cfg:enabled=1;lat=1;lng=2\nsys: 2 \nnet:0\r\nbt:1\n").unwrap();
        assert_eq!(f[0].as_deref(), Some("enabled=1;lat=1;lng=2"));
        assert_eq!(f[1].as_deref(), Some("2"));
        assert_eq!(f[2].as_deref(), Some("0"));
        assert_eq!(f[3].as_deref(), Some("1"));
    }

    #[test]
    fn null_and_empty_values_are_skipped() {
        let f = parse_fields("cfg:enabled=0\nsys:null\nnet:\n").unwrap();
        assert!(f[1].is_none() && f[2].is_none() && f[3].is_none());
    }

    #[test]
    fn no_cfg_or_empty_is_none() {
        assert!(parse_fields("sys:1\nnet:0\n").is_none());
        assert!(parse_fields("").is_none());
        assert!(parse_fields("cfg:null\n").is_none());
    }

    #[test]
    fn lines_without_colon_and_unknown_keys_are_ignored() {
        let f = parse_fields("sampah\nzzz:1\ncfg:a=1\n").unwrap();
        assert_eq!(f[0].as_deref(), Some("a=1"));
    }

    #[test]
    fn last_duplicate_wins_and_value_keeps_inner_colons() {
        let f = parse_fields("cfg:a=1\ncfg:b=2:3\n").unwrap();
        assert_eq!(f[0].as_deref(), Some("b=2:3"));
    }

    const FILE: &str = "t=1000;k=500;n=2\n1.5 2.5 3 90 10\n4.5 5.5 6 180 20\n";

    #[test]
    fn track_file_reads_rows_in_la_lo_sp_br_al_order() {
        let t = parse_track_file(FILE, FILE.len() as i64).unwrap();
        assert_eq!((t.t0, t.wrote, t.tk, t.n()), (1000, 1000, 500, 2));
        assert_eq!(t.la, vec![1.5, 4.5]);
        assert_eq!(t.lo, vec![2.5, 5.5]);
        assert_eq!(t.sp, vec![3.0, 6.0]);
        assert_eq!(t.br, vec![90.0, 180.0]);
        assert_eq!(t.al, vec![10.0, 20.0]);
    }

    #[test]
    fn track_file_header_rules() {
        let ok = |h: &str| {
            let s = format!("{}\n1 2 3 4 5\n", h);
            parse_track_file(&s, s.len() as i64).is_some()
        };
        assert!(ok("t=1;k=1;n=1"));
        assert!(ok(" t = 1 ; k=1;n=1;x=zz;junk"));
        assert!(!ok("k=1;n=1"));
        assert!(!ok("t=1;k=0;n=1"));
        assert!(!ok("t=1;k=1;n=0"));
        assert!(!ok("t=1;k=1"));
        assert!(!ok("t=abc;k=1;n=1"));
        assert!(!ok("t=1;k=3000000000;n=1"));
        assert!(!ok("t=1;k=1;n=1.5"));
    }

    #[test]
    fn track_file_needs_newline_and_enough_rows() {
        assert!(parse_track_file("t=1;k=1;n=1", 100).is_none());
        let short = "t=1;k=1;n=2\n1 2 3 4 5\n";
        assert!(parse_track_file(short, 1000).is_none());
        let bad = "t=1;k=1;n=1\n1 2 3 4 5d\n";
        assert!(parse_track_file(bad, 1000).is_none());
        let nan = "t=1;k=1;n=1\n1 2 3 4 NaN\n";
        assert!(parse_track_file(nan, 1000).is_none());
    }

    #[test]
    fn track_file_row_count_is_bounded_by_file_length() {
        let s = "t=1;k=1;n=3\n1 2 3 4 5\n1 2 3 4 5\n1 2 3 4 5\n";
        assert!(parse_track_file(s, s.len() as i64).is_some());
        assert!(parse_track_file(s, 29).is_none());
        assert!(parse_track_file(s, 30).is_some());
    }

    #[test]
    fn track_file_accepts_any_java_whitespace_between_tokens() {
        let s = "t=1;k=1;n=1\n1\t2\r\n3  4\u{c}5";
        assert!(parse_track_file(s, 1000).is_some());
    }

    #[test]
    fn extra_tokens_after_n_rows_are_ignored() {
        let s = "t=1;k=1;n=1\n1 2 3 4 5 9 9 9\n";
        let t = parse_track_file(s, 1000).unwrap();
        assert_eq!(t.n(), 1);
    }
}
