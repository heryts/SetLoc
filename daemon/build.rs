use std::env;
use std::fs;
use std::path::PathBuf;

const LOGICAL: [&str; 7] = ["cfg", "sys", "beat", "trk", "net", "bt", "live"];

fn tag(salt: &str, logical: &str) -> String {
    let s = format!("{}{}", salt, logical);
    let mut h: u32 = 0x811C_9DC5;
    for b in s.bytes() {
        h = ((h ^ b as u32).wrapping_mul(16_777_619)) & 0x7FFF_FFFF;
    }
    format!("{:06x}", h & 0x00FF_FFFF)
}

fn main() {
    let root = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let salt_path = root.join("..").join(".salt");
    println!("cargo:rerun-if-changed={}", salt_path.display());
    let salt = fs::read_to_string(&salt_path).expect("salt");
    let salt = salt.trim();
    assert!(salt.len() == 12 && salt.bytes().all(|b| b.is_ascii_alphanumeric()));
    let kv: Vec<String> = salt.bytes().map(|b| b.to_string()).collect();
    let mut out = String::new();
    out.push_str(&format!("pub const KV: [u8; 12] = [{}];\n", kv.join(", ")));
    for (i, name) in ["KEY", "SYSKEY", "BEATKEY", "TRKKEY", "NETKEY", "BTKEY"].iter().enumerate() {
        out.push_str(&format!("pub const {}: &str = \"{}\";\n", name, tag(salt, LOGICAL[i])));
    }
    let dest = PathBuf::from(env::var("OUT_DIR").unwrap()).join("keys.rs");
    fs::write(dest, out).unwrap();
}
