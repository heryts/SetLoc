use std::fs;
use std::io::Read;
use std::path::Path;
use std::process::exit;

const LOGICAL: [&str; 6] = ["cfg", "sys", "beat", "trk", "net", "bt"];
const ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
const LEN: usize = 12;

const CONFIG: &str = "module/zygote/src/main/java/io/gpsz/zygote/Config.java";
const OLDKEYS: &str = "daemon/oldkeys.txt";
const SALT_FILE: &str = ".salt";

fn tag(salt: &str, logical: &str) -> String {
    let s = format!("{}{}", salt, logical);
    let mut h: u32 = 0x811C_9DC5;
    for b in s.bytes() {
        h = (h ^ b as u32).wrapping_mul(16_777_619) & 0x7FFF_FFFF;
    }
    format!("{:06x}", h & 0x00FF_FFFF)
}

fn tags(salt: &str, with_live: bool) -> Vec<String> {
    let mut v: Vec<String> = LOGICAL.iter().map(|l| tag(salt, l)).collect();
    if with_live {
        v.push(tag(salt, "live"));
    }
    v
}

fn random() -> Result<String, String> {
    let mut f = fs::File::open("/dev/urandom").map_err(|e| e.to_string())?;
    let limit = 256 - 256 % ALPHABET.len();
    let mut out = String::new();
    let mut buf = [0u8; 64];
    while out.len() < LEN {
        f.read_exact(&mut buf).map_err(|e| e.to_string())?;
        for &b in buf.iter() {
            if out.len() == LEN {
                break;
            }
            if (b as usize) < limit {
                out.push(ALPHABET[b as usize % ALPHABET.len()] as char);
            }
        }
    }
    Ok(out)
}

fn read(p: &str) -> Result<String, String> {
    fs::read_to_string(p).map_err(|e| format!("{}: {}", p, e))
}

fn write(p: &str, s: &str) -> Result<(), String> {
    fs::write(p, s).map_err(|e| format!("{}: {}", p, e))
}

fn missing(file: &str, pattern: &str) -> String {
    format!("pola tidak ditemukan di {}: {}", file, pattern)
}

fn salt_span(text: &str) -> Option<(usize, usize)> {
    let open = "SALT = \"";
    let mut from = 0;
    while let Some(i) = text[from..].find(open) {
        let start = from + i + open.len();
        match text[start..].find('"') {
            Some(n) if n >= 1 => return Some((start, start + n)),
            _ => from = start,
        }
    }
    None
}

fn salt_of(text: &str) -> Option<String> {
    salt_span(text).map(|(a, b)| text[a..b].to_string())
}

fn set_salt(text: &str, salt: &str, file: &str) -> Result<String, String> {
    let (a, b) = salt_span(text).ok_or_else(|| missing(file, "SALT = \"[^\"]+\""))?;
    Ok(format!("{}{}{}", &text[..a], salt, &text[b..]))
}

fn run(fresh: bool) -> Result<String, String> {
    let config_text = read(CONFIG)?;
    let old_salt = salt_of(&config_text).ok_or_else(|| missing(CONFIG, "SALT = \"([^\"]+)\""))?;
    let salt = if !fresh && Path::new(SALT_FILE).exists() {
        read(SALT_FILE)?.trim().to_string()
    } else {
        random()?
    };
    if salt.len() != LEN || !salt.bytes().all(|b| b.is_ascii_alphanumeric()) {
        return Err(format!(".salt harus {} karakter huruf/angka", LEN));
    }
    write(SALT_FILE, &format!("{}\n", salt))?;

    let config = set_salt(&config_text, &salt, CONFIG)?;

    let old_keys = if Path::new(OLDKEYS).exists() { read(OLDKEYS)? } else { String::new() };
    let mut legacy: Vec<String> = Vec::new();
    for k in old_keys.split_whitespace() {
        if !legacy.iter().any(|x| x == k) {
            legacy.push(k.to_string());
        }
    }
    if old_salt != salt {
        for k in tags(&old_salt, true) {
            if !legacy.contains(&k) {
                legacy.push(k);
            }
        }
    }
    let current = tags(&salt, true);
    legacy.retain(|k| !current.contains(k));
    let legacy_line = format!("{}\n", legacy.join(" "));

    write(CONFIG, &config)?;
    if let Some(d) = Path::new(OLDKEYS).parent() {
        fs::create_dir_all(d).map_err(|e| format!("{}: {}", d.display(), e))?;
    }
    write(OLDKEYS, &legacy_line)?;
    Ok(format!(
        "salt {}, key: {}",
        if old_salt == salt { "tetap" } else { "diganti" },
        tags(&salt, false).join(" ")
    ))
}

fn main() {
    let fresh = std::env::args().nth(1).is_some_and(|a| a == "--new");
    match run(fresh) {
        Ok(m) => println!("{}", m),
        Err(e) => {
            eprintln!("{}", e);
            exit(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tag_matches_repo_values() {
        assert_eq!(tag("Vzvwk4Rxj7Ps", "cfg"), "956349");
        assert_eq!(tag("Vzvwk4Rxj7Ps", "sys"), "db078e");
        assert_eq!(tag("Vzvwk4Rxj7Ps", "beat"), "c1e67d");
        assert_eq!(tag("Vzvwk4Rxj7Ps", "live"), "ca29e7");
    }

    #[test]
    fn salt_literal_is_replaced_once() {
        let t = "a SALT = \"OLD\";\nb SALT = \"OLD\";\n";
        assert_eq!(salt_of(t).as_deref(), Some("OLD"));
        assert_eq!(set_salt(t, "NEW", "x").unwrap(), "a SALT = \"NEW\";\nb SALT = \"OLD\";\n");
    }
}
