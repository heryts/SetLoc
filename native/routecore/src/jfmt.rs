pub fn fixed(x: f64, decimals: usize) -> String {
    if x.is_nan() {
        return "NaN".to_string();
    }
    if x.is_infinite() {
        return if x < 0.0 { "-Infinity" } else { "Infinity" }.to_string();
    }
    let sci = format!("{:e}", x.abs());
    let (mant, exp) = sci.split_once('e').unwrap_or((&sci, "0"));
    let exp: i64 = exp.parse().unwrap_or(0);
    let digits: Vec<u8> = mant.bytes().filter(|b| b.is_ascii_digit()).map(|b| b - b'0').collect();
    let point = exp + 1;
    let mut int: Vec<u8> = Vec::new();
    let mut frac: Vec<u8> = Vec::new();
    if point <= 0 {
        int.push(0);
        frac.extend(std::iter::repeat(0).take((-point) as usize));
        frac.extend_from_slice(&digits);
    } else if point as usize >= digits.len() {
        int.extend_from_slice(&digits);
        int.extend(std::iter::repeat(0).take(point as usize - digits.len()));
    } else {
        int.extend_from_slice(&digits[..point as usize]);
        frac.extend_from_slice(&digits[point as usize..]);
    }
    let round_up = frac.get(decimals).map_or(false, |d| *d >= 5);
    frac.truncate(decimals);
    while frac.len() < decimals {
        frac.push(0);
    }
    if round_up {
        let mut carry = true;
        for d in frac.iter_mut().rev().chain(int.iter_mut().rev()) {
            if !carry {
                break;
            }
            if *d == 9 {
                *d = 0;
            } else {
                *d += 1;
                carry = false;
            }
        }
        if carry {
            int.insert(0, 1);
        }
    }
    let mut out = String::with_capacity(int.len() + decimals + 2);
    if x.is_sign_negative() {
        out.push('-');
    }
    out.extend(int.iter().map(|d| (b'0' + d) as char));
    if decimals > 0 {
        out.push('.');
        out.extend(frac.iter().map(|d| (b'0' + d) as char));
    }
    out
}

pub fn form_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for b in s.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => out.push(b as char),
            b' ' => out.push('+'),
            _ => {
                out.push('%');
                out.push(char::from_digit((b >> 4) as u32, 16).unwrap().to_ascii_uppercase());
                out.push(char::from_digit((b & 15) as u32, 16).unwrap().to_ascii_uppercase());
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixed_basic() {
        assert_eq!(fixed(0.0, 5), "0.00000");
        assert_eq!(fixed(-7.8, 5), "-7.80000");
        assert_eq!(fixed(110.123456, 5), "110.12346");
        assert_eq!(fixed(12.345, 1), "12.3");
        assert_eq!(fixed(1.0, 1), "1.0");
        assert_eq!(fixed(1234567.0, 1), "1234567.0");
        assert_eq!(fixed(1e21, 1), "1000000000000000000000.0");
        assert_eq!(fixed(5e-324, 5), "0.00000");
    }

    #[test]
    fn fixed_half_up_on_decimal_digits() {
        assert_eq!(fixed(1.005, 2), "1.01");
        assert_eq!(fixed(0.125, 2), "0.13");
        assert_eq!(fixed(12.25, 1), "12.3");
        assert_eq!(fixed(0.015625, 5), "0.01563");
        assert_eq!(fixed(9.95, 1), "10.0");
        assert_eq!(fixed(99.999999, 5), "100.00000");
        assert_eq!(fixed(0.999996, 5), "1.00000");
    }

    #[test]
    fn fixed_sign_and_specials() {
        assert_eq!(fixed(-0.0, 5), "-0.00000");
        assert_eq!(fixed(-0.000001, 5), "-0.00000");
        assert_eq!(fixed(f64::NAN, 1), "NaN");
        assert_eq!(fixed(f64::INFINITY, 1), "Infinity");
        assert_eq!(fixed(f64::NEG_INFINITY, 1), "-Infinity");
    }

    #[test]
    fn form_encode_matches_url_encoder() {
        assert_eq!(form_encode("abc XYZ-09._*"), "abc+XYZ-09._*");
        assert_eq!(form_encode("_p~iF~ps|U"), "_p%7EiF%7Eps%7CU");
        assert_eq!(form_encode("a&b=c/d?e"), "a%26b%3Dc%2Fd%3Fe");
        assert_eq!(form_encode("Yogyakarta é"), "Yogyakarta+%C3%A9");
        assert_eq!(form_encode("`\\@"), "%60%5C%40");
        assert_eq!(form_encode(""), "");
    }
}
