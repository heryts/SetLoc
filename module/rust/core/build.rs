use std::env;
use std::fs;
use std::path::PathBuf;

const LOGICAL: [&str; 7] = ["cfg", "sys", "beat", "trk", "net", "bt", "live"];
const NAMES: [&str; 7] = ["KEY", "KEY_SYS", "KEY_BEAT", "KEY_TRK", "KEY_NET", "KEY_BT", "KEY_LIVE"];

fn tag(salt: &str, logical: &str) -> String {
    let mut h: u32 = 0x811C_9DC5;
    for b in format!("{}{}", salt, logical).bytes() {
        h = (h ^ b as u32).wrapping_mul(16_777_619) & 0x7FFF_FFFF;
    }
    format!("{:06x}", h & 0x00FF_FFFF)
}

fn main() {
    let root = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let salt_path = match env::var("GPSZ_SALT_FILE") {
        Ok(p) => PathBuf::from(p),
        Err(_) => root.join("..").join("..").join("..").join(".salt"),
    };
    println!("cargo:rerun-if-changed={}", salt_path.display());
    println!("cargo:rerun-if-env-changed=GPSZ_SALT_FILE");
    let salt = fs::read_to_string(&salt_path).expect("salt");
    let salt = salt.trim();
    assert!(salt.len() == 12 && salt.bytes().all(|b| b.is_ascii_alphanumeric()));
    let mut out = format!("pub const SALT: &str = \"{}\";\n", salt);
    for (name, logical) in NAMES.iter().zip(LOGICAL.iter()) {
        out.push_str(&format!("pub const {}: &str = \"{}\";\n", name, tag(salt, logical)));
    }
    fs::write(PathBuf::from(env::var("OUT_DIR").unwrap()).join("keys.rs"), out).unwrap();
}
