pub fn fixed(v: f64, digits: usize) -> String {
    if v.is_nan() {
        return "NaN".to_string();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity" } else { "-Infinity" }.to_string();
    }
    let neg = v.is_sign_negative();
    let exact = format!("{:.*}", digits + 40, v.abs());
    let (ip, fp) = exact.split_once('.').unwrap_or((exact.as_str(), ""));
    let mut digs: Vec<u8> = ip.bytes().chain(fp.bytes().take(digits)).collect();
    let round_up = fp.as_bytes().get(digits).map_or(false, |d| *d >= b'5');
    if round_up {
        let mut i = digs.len();
        loop {
            if i == 0 {
                digs.insert(0, b'1');
                break;
            }
            i -= 1;
            if digs[i] == b'9' {
                digs[i] = b'0';
            } else {
                digs[i] += 1;
                break;
            }
        }
    }
    let split = digs.len() - digits;
    let int_part = String::from_utf8_lossy(&digs[..split]).into_owned();
    let frac_part = String::from_utf8_lossy(&digs[split..]).into_owned();
    let zero = digs.iter().all(|d| *d == b'0');
    let mut out = String::new();
    if neg && !zero {
        out.push('-');
    }
    out.push_str(&int_part);
    if digits > 0 {
        out.push('.');
        out.push_str(&frac_part);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::fixed;

    #[test]
    fn half_up() {
        assert_eq!(fixed(2.25, 1), "2.3");
        assert_eq!(fixed(0.125, 2), "0.13");
        assert_eq!(fixed(1.0, 1), "1.0");
        assert_eq!(fixed(9.96, 1), "10.0");
        assert_eq!(fixed(-7.7829, 3), "-7.783");
        assert_eq!(fixed(-0.0004, 3), "0.000");
        assert_eq!(fixed(110.36711, 3), "110.367");
        assert_eq!(fixed(5.0, 0), "5");
    }
}
