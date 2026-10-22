use crate::keys::SALT;

pub fn enc(plain: &str) -> String {
    let key = SALT.as_bytes();
    let mut out = String::with_capacity(plain.len() * 2);
    for (i, c) in plain.chars().enumerate() {
        let v = ((c as u32 & 0xFF) + key[i % key.len()] as u32) % 256;
        out.push_str(&format!("{:02x}", v));
    }
    out
}

pub fn dec(hex: &str) -> Option<String> {
    let key = SALT.as_bytes();
    let b = hex.as_bytes();
    let mut out = String::with_capacity(b.len() / 2);
    let mut i = 0;
    while i + 1 < b.len() {
        let pair = std::str::from_utf8(&b[i..i + 2]).ok()?;
        let x = u32::from_str_radix(pair, 16).ok()?;
        let k = key[(i / 2) % key.len()] as u32;
        out.push(((x + 256 - k) % 256) as u8 as char);
        i += 2;
    }
    Some(out)
}

pub fn tag(logical: &str) -> String {
    let mut h: u32 = 0x811C_9DC5;
    for b in SALT.bytes().chain(logical.bytes()) {
        h = (h ^ b as u32).wrapping_mul(16_777_619) & 0x7FFF_FFFF;
    }
    format!("{:06x}", h & 0x00FF_FFFF)
}
