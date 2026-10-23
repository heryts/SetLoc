use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    let root = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let path = root.join("..").join("..").join(".salt");
    println!("cargo:rerun-if-changed={}", path.display());
    let salt = fs::read_to_string(&path).expect("salt");
    let salt = salt.trim();
    assert!(salt.len() == 12 && salt.bytes().all(|b| b.is_ascii_alphanumeric()));
    println!("cargo:rustc-env=GPSZ_SALT={}", salt);
}
