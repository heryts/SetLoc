#[derive(Clone, Debug, PartialEq)]
pub struct Track {
    pub t0: i64,
    pub wrote: i64,
    pub tk: i64,
    pub n: usize,
    pub la: Vec<f64>,
    pub lo: Vec<f64>,
    pub al: Vec<f64>,
    pub sp: Vec<f32>,
    pub br: Vec<f32>,
}

fn pf64(s: &str) -> Option<f64> {
    let t = s.trim();
    if t.is_empty() {
        None
    } else {
        t.parse().ok()
    }
}

fn pf32(s: &str) -> Option<f32> {
    let t = s.trim();
    if t.is_empty() {
        None
    } else {
        t.parse().ok()
    }
}

fn pi64(s: &str) -> Option<i64> {
    s.trim().parse().ok()
}

pub fn parse_window(s: &str) -> Option<Track> {
    let mut t0: Option<i64> = None;
    let mut a: Option<i64> = None;
    let mut tk: i64 = 0;
    let mut d: Option<&str> = None;
    for kv in s.split(';') {
        let Some(i) = kv.find('=') else { continue };
        let v = &kv[i + 1..];
        match &kv[..i] {
            "t" => t0 = Some(pi64(v)?),
            "a" => a = Some(pi64(v)?),
            "k" => tk = pi64(v)?,
            "d" => d = Some(v),
            _ => {}
        }
    }
    let d = d?;
    let t0 = t0?;
    if tk <= 0 {
        return None;
    }
    let mut la = Vec::new();
    let mut lo = Vec::new();
    let mut al = Vec::new();
    let mut sp = Vec::new();
    let mut br = Vec::new();
    for row in d.split('|') {
        let r = row.trim_start_matches(',');
        if r.is_empty() {
            continue;
        }
        let mut f: Vec<&str> = r.split(',').collect();
        while f.last().map_or(false, |x| x.is_empty()) {
            f.pop();
        }
        if f.len() < 5 {
            return None;
        }
        la.push(pf64(f[0])?);
        lo.push(pf64(f[1])?);
        sp.push(pf32(f[2])?);
        br.push(pf32(f[3])?);
        al.push(pf64(f[4])?);
    }
    if la.is_empty() {
        return None;
    }
    Some(Track { t0, wrote: a.unwrap_or(t0), tk, n: la.len(), la, lo, al, sp, br })
}

pub fn parse_file(buf: &[u8]) -> Option<Track> {
    let s = std::str::from_utf8(buf).ok()?;
    let nl = s.find('\n')?;
    let mut t0: Option<i64> = None;
    let mut tk: i64 = 0;
    let mut n: i64 = 0;
    for kv in s[..nl].split(';') {
        let Some(i) = kv.find('=') else { continue };
        let v = kv[i + 1..].trim();
        match kv[..i].trim() {
            "t" => t0 = Some(pi64(v)?),
            "k" => tk = pi64(v)?,
            "n" => n = pi64(v)?,
            _ => {}
        }
    }
    let t0 = t0?;
    if tk <= 0 || n <= 0 || n > buf.len() as i64 / 10 {
        return None;
    }
    let n = n as usize;
    let mut toks = s[nl + 1..].split_whitespace();
    let mut la = Vec::with_capacity(n);
    let mut lo = Vec::with_capacity(n);
    let mut al = Vec::with_capacity(n);
    let mut sp = Vec::with_capacity(n);
    let mut br = Vec::with_capacity(n);
    for _ in 0..n {
        la.push(pf64(toks.next()?)?);
        lo.push(pf64(toks.next()?)?);
        sp.push(pf32(toks.next()?)?);
        br.push(pf32(toks.next()?)?);
        al.push(pf64(toks.next()?)?);
    }
    Some(Track { t0, wrote: t0, tk, n, la, lo, al, sp, br })
}

pub fn merge_file(w: Option<&Track>, f: Option<&Track>) -> Option<Track> {
    let w = w?;
    let Some(f) = f else { return Some(w.clone()) };
    if f.tk != w.tk {
        return Some(w.clone());
    }
    let d = w.t0 - f.t0;
    if d < 0 || d % f.tk != 0 || (d / f.tk) as usize >= f.n {
        return Some(w.clone());
    }
    let i = (d / f.tk) as usize;
    if (f.la[i] - w.la[0]).abs() > 1e-5 || (f.lo[i] - w.lo[0]).abs() > 1e-5 {
        return Some(w.clone());
    }
    Some(Track { t0: f.t0, wrote: w.wrote, tk: f.tk, n: f.n, la: f.la.clone(), lo: f.lo.clone(), al: f.al.clone(), sp: f.sp.clone(), br: f.br.clone() })
}

pub fn lerp_angle(a: f32, b: f32, f: f64) -> f32 {
    let d = ((b as f64 - a as f64 + 540.0) % 360.0) - 180.0;
    ((a as f64 + d * f + 360.0) % 360.0) as f32
}

pub struct Sample {
    pub lat: f64,
    pub lng: f64,
    pub alt: f64,
    pub spd: f32,
    pub brg: f32,
}

pub fn sample(t: &Track, rel_ms: i64, stale: i32) -> Sample {
    let at = if rel_ms <= 0 { 0.0 } else { rel_ms as f64 / t.tk as f64 };
    let (i, f) = if at >= (t.n - 1) as f64 { (t.n - 1, 0.0) } else { (at as usize, at - (at as usize) as f64) };
    let j = if f > 0.0 { i + 1 } else { i };
    let lat = t.la[i] + (t.la[j] - t.la[i]) * f;
    let mut dl = t.lo[j] - t.lo[i];
    if dl > 180.0 {
        dl -= 360.0;
    } else if dl < -180.0 {
        dl += 360.0;
    }
    let mut lng = t.lo[i] + dl * f;
    if lng > 180.0 {
        lng -= 360.0;
    } else if lng < -180.0 {
        lng += 360.0;
    }
    let spd = if stale >= 1 { 0.0 } else { (t.sp[i] as f64 + (t.sp[j] as f64 - t.sp[i] as f64) * f).max(0.0) as f32 };
    Sample { lat, lng, alt: t.al[i] + (t.al[j] - t.al[i]) * f, spd, brg: lerp_angle(t.br[i], t.br[j], f) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn window_roundtrip() {
        let t = parse_window("t=1000;a=1500;k=1000;d=1.0,2.0,3.0,90.0,5.0|1.1,2.1,4.0,91.0,6.0").unwrap();
        assert_eq!(t.n, 2);
        assert_eq!(t.wrote, 1500);
        assert_eq!(t.tk, 1000);
        assert_eq!(t.al[1], 6.0);
    }

    #[test]
    fn window_rejects_bad() {
        assert!(parse_window("t=1;k=0;d=1,2,3,4,5").is_none());
        assert!(parse_window("t=1;k=1;d=1,2,3,4").is_none());
        assert!(parse_window("t=x;k=1;d=1,2,3,4,5").is_none());
        assert!(parse_window("k=1;d=1,2,3,4,5").is_none());
    }

    #[test]
    fn window_skips_leading_commas() {
        let t = parse_window("t=1;k=500;d=,,1,2,3,4,5").unwrap();
        assert_eq!(t.n, 1);
        assert_eq!(t.la[0], 1.0);
    }

    #[test]
    fn file_parse() {
        let t = parse_file(b"t=100;k=1000;n=2\n1 2 3 4 5\n6 7 8 9 10\n").unwrap();
        assert_eq!(t.n, 2);
        assert_eq!(t.lo[1], 7.0);
        assert!(parse_file(b"t=100;k=1000;n=3\n1 2 3 4 5\n").is_none());
        assert!(parse_file(b"t=100;k=1000;n=2").is_none());
    }

    #[test]
    fn interpolation_and_dateline() {
        let t = parse_window("t=0;k=1000;d=0,179.5,10,0,0|0,-179.5,20,90,10").unwrap();
        let s = sample(&t, 500, 0);
        assert!((s.lng.abs() - 180.0).abs() < 1e-9);
        assert!((s.spd - 15.0).abs() < 1e-5);
        assert!((s.alt - 5.0).abs() < 1e-9);
        assert!((s.brg - 45.0).abs() < 1e-4);
        assert_eq!(sample(&t, 500, 1).spd, 0.0);
        let end = sample(&t, 99_999, 0);
        assert_eq!(end.alt, 10.0);
    }

    #[test]
    fn merge_prefers_file_when_aligned() {
        let f = parse_file(b"t=0;k=1000;n=3\n0 0 1 0 0\n1 1 1 0 0\n2 2 1 0 0\n").unwrap();
        let w = parse_window("t=1000;a=5000;k=1000;d=1,1,1,0,0").unwrap();
        let m = merge_file(Some(&w), Some(&f)).unwrap();
        assert_eq!(m.t0, 0);
        assert_eq!(m.n, 3);
        assert_eq!(m.wrote, 5000);
        let off = parse_window("t=1000;a=5000;k=1000;d=9,9,1,0,0").unwrap();
        assert_eq!(merge_file(Some(&off), Some(&f)).unwrap().n, 1);
    }
}
