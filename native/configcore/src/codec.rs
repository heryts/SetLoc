pub const SALT: &str = env!("GPSZ_SALT");

pub fn tag(logical: &str) -> String {
    let s = format!("{}{}", SALT, logical);
    let mut h: u32 = 0x811C_9DC5;
    for b in s.bytes() {
        h = ((h ^ b as u32).wrapping_mul(16_777_619)) & 0x7FFF_FFFF;
    }
    format!("{:06x}", h & 0x00FF_FFFF)
}

pub fn enc(plain: &str) -> String {
    let mut out = String::with_capacity(plain.len() * 2);
    let salt = SALT.as_bytes();
    for (i, &b) in plain.as_bytes().iter().enumerate() {
        let cv = b as u16;
        let kv = salt[i % salt.len()] as u16;
        out.push_str(&format!("{:02x}", (cv + kv) % 256));
    }
    out
}

pub fn dec(hex: &str) -> Option<String> {
    if hex.len() % 2 != 0 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let salt = SALT.as_bytes();
    let mut out = Vec::with_capacity(hex.len() / 2);
    for i in 0..hex.len() / 2 {
        let xv = u16::from_str_radix(&hex[i * 2..i * 2 + 2], 16).ok()?;
        let kv = salt[i % salt.len()] as u16;
        let v = ((xv as i32 - kv as i32).rem_euclid(256)) as u8;
        out.push(v);
    }
    Some(String::from_utf8_lossy(&out).into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enc_dec_roundtrip() {
        let s = "enabled=1;lat=-7.25;lng=110.5";
        assert_eq!(dec(&enc(s)).as_deref(), Some(s));
    }

    #[test]
    fn tag_is_six_hex() {
        let t = tag("cfg");
        assert_eq!(t.len(), 6);
        assert!(t.bytes().all(|b| b.is_ascii_hexdigit()));
    }

    #[test]
    fn dec_rejects_odd_length() {
        assert_eq!(dec("abc"), None);
    }
}
