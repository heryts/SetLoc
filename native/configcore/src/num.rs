pub fn jtrim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= ' ')
}

pub fn plain_integer(v: &str) -> bool {
    let b = v.as_bytes();
    let mut i = 0;
    if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
        i += 1;
    }
    let s = i;
    while i < b.len() && b[i].is_ascii_digit() {
        i += 1;
    }
    i > s && i == b.len()
}

pub fn plain_decimal(v: &str) -> bool {
    let b = v.as_bytes();
    let mut i = 0;
    if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
        i += 1;
    }
    let mut digits = 0;
    while i < b.len() && b[i].is_ascii_digit() {
        i += 1;
        digits += 1;
    }
    if i < b.len() && b[i] == b'.' {
        i += 1;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
            digits += 1;
        }
    }
    if digits == 0 {
        return false;
    }
    if i < b.len() && (b[i] == b'e' || b[i] == b'E') {
        i += 1;
        if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
            i += 1;
        }
        let s = i;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        if i == s {
            return false;
        }
    }
    i == b.len()
}

pub fn max0(x: f32) -> f32 {
    if x > 0.0 {
        x
    } else {
        0.0
    }
}

pub fn min100(x: f32) -> f32 {
    if x < 100.0 {
        x
    } else {
        100.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decimal_grammar() {
        for ok in ["0", "-7.5", "+3", "5.", ".5", "-.5e3", "1E+9", "007", "1e0"] {
            assert!(plain_decimal(ok), "{}", ok);
        }
        for bad in ["", "-", ".", "e5", "1e", "1e+", "5d", "5f", "NaN", "Infinity", "0x1p3", " 5", "5 ", "1_0", "--1", "1.2.3"] {
            assert!(!plain_decimal(bad), "{}", bad);
        }
    }

    #[test]
    fn integer_grammar() {
        for ok in ["0", "-7", "+3", "0012"] {
            assert!(plain_integer(ok), "{}", ok);
        }
        for bad in ["", "-", "1.0", "1e3", " 1", "1 ", "0x10"] {
            assert!(!plain_integer(bad), "{}", bad);
        }
    }

    #[test]
    fn trim_matches_java() {
        assert_eq!(jtrim(" \t a b \n"), "a b");
        assert_eq!(jtrim("\u{1}x\u{1f}"), "x");
        assert_eq!(jtrim("\u{a0}x"), "\u{a0}x");
    }

    #[test]
    fn signed_zero_clamps_to_positive() {
        assert!(max0(-0.0).is_sign_positive());
        assert_eq!(max0(-3.0), 0.0);
        assert_eq!(min100(250.0), 100.0);
    }
}
