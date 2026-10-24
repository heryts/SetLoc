include!(concat!(env!("OUT_DIR"), "/keys.rs"));

pub use configcore::codec::enc;

pub fn dec(s: &str) -> Option<String> {
    let s = s.trim();
    if s.is_empty() || s == "null" {
        return None;
    }
    configcore::codec::dec(s)
}

pub fn numok(s: &str) -> bool {
    let (mant, exp) = match s.find(['e', 'E']) {
        Some(i) => (&s[..i], Some(&s[i + 1..])),
        None => (s, None),
    };
    if let Some(e) = exp {
        let digits = e.strip_prefix(['-', '+']).unwrap_or(e);
        if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
            return false;
        }
    }
    let mant = mant.strip_prefix(['-', '+']).unwrap_or(mant);
    !mant.is_empty()
        && mant.bytes().all(|b| b.is_ascii_digit() || b == b'.')
        && mant.matches('.').count() <= 1
        && mant.bytes().any(|b| b.is_ascii_digit())
}

pub fn inrange(s: &str, max: u32) -> bool {
    if s.is_empty() || s.matches('-').count() > 1 || (s.contains('-') && !s.starts_with('-')) {
        return false;
    }
    if !s.bytes().all(|b| b.is_ascii_digit() || b == b'.' || b == b'-') || s.matches('.').count() > 1 {
        return false;
    }
    let v = s.strip_prefix('-').unwrap_or(s);
    let (i, f) = match v.split_once('.') {
        Some((i, f)) => (i, f),
        None => (v, ""),
    };
    let i: u32 = if i.is_empty() { 0 } else { match i.parse() { Ok(n) => n, Err(_) => return false } };
    i < max || (i == max && f.bytes().all(|b| b == b'0'))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let s = "enabled=1;lat=-7.25;lng=110.5;acc=10;random=0";
        assert_eq!(dec(&enc(s)).as_deref(), Some(s));
    }

    #[test]
    fn dec_rejects_garbage() {
        assert_eq!(dec("null"), None);
        assert_eq!(dec("zz"), None);
        assert_eq!(dec("abc"), None);
    }

    #[test]
    fn numbers() {
        for ok in ["1", "-7.25", "1e5", "1.5E-3", "+2", ".5"] {
            assert!(numok(ok), "{ok}");
        }
        for bad in ["", "-", ".", "1.2.3", "1e", "e5", "abc", "1e+"] {
            assert!(!numok(bad), "{bad}");
        }
    }

    #[test]
    fn ranges() {
        assert!(inrange("89.999", 90));
        assert!(inrange("90", 90));
        assert!(inrange("90.000", 90));
        assert!(!inrange("90.1", 90));
        assert!(inrange("-180", 180));
        assert!(!inrange("181", 180));
        assert!(!inrange("1-2", 90));
    }
}
