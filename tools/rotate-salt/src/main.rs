use std::fs;
use std::io::Read;
use std::path::Path;
use std::process::exit;

const LOGICAL: [&str; 6] = ["cfg", "sys", "beat", "trk", "net", "bt"];
const ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
const LEN: usize = 12;

const CONFIG: &str = "module/zygote/src/main/java/io/gpsz/zygote/Config.java";
const PREF: &str = "app/app/src/main/java/com/heryts/setloc/utils/PrefManager.kt";
const OLDKEYS: &str = "daemon/oldkeys.txt";
const WEB: &str = "module/zygote/src/main/assets/webroot/index.html";
const TEST: &str = "module/test/run.sh";
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

fn line_sub<F: Fn(&str) -> Option<usize>>(text: &str, matcher: F, replacement: &str) -> Option<String> {
    let mut pos = 0;
    for line in text.split_inclusive('\n') {
        if let Some(n) = matcher(line) {
            let mut out = String::with_capacity(text.len() + replacement.len());
            out.push_str(&text[..pos]);
            out.push_str(replacement);
            out.push_str(&text[pos + n..]);
            return Some(out);
        }
        pos += line.len();
    }
    None
}

fn hexrun(s: &str) -> usize {
    s.bytes().take_while(|&b| matches!(b, b'0'..=b'9' | b'a'..=b'f')).count()
}

fn m_web_salt(line: &str) -> Option<usize> {
    let p = "const SALT = '";
    let rest = line.strip_prefix(p)?;
    let n = rest.find('\'')?;
    if rest[n + 1..].starts_with(';') {
        Some(p.len() + n + 2)
    } else {
        None
    }
}

fn m_web_sk(line: &str) -> Option<usize> {
    let p = "const SK = {";
    let rest = line.strip_prefix(p)?;
    let body = rest.trim_end_matches(|c: char| c == '\n' || c == '\r');
    let n = body.rfind("};")?;
    Some(p.len() + n + 2)
}

fn m_test_salt(line: &str) -> Option<usize> {
    let p = "SALT=\"";
    let rest = line.strip_prefix(p)?;
    let n = rest.find('"')?;
    if n >= 1 {
        Some(p.len() + n + 1)
    } else {
        None
    }
}

fn m_test_tags(line: &str) -> Option<usize> {
    let first = "SYSTAG=";
    let mut rest = line.strip_prefix(first)?;
    let mut used = first.len();
    for next in ["; TRKTAG=", "; BEATTAG="] {
        let n = hexrun(rest);
        if n == 0 {
            return None;
        }
        rest = rest[n..].strip_prefix(next)?;
        used += n + next.len();
    }
    let n = hexrun(rest);
    if n == 0 {
        None
    } else {
        Some(used + n)
    }
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
    let pref = set_salt(&read(PREF)?, &salt, PREF)?;

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

    let web_text = read(WEB)?;
    let web = line_sub(&web_text, m_web_salt, &format!("const SALT = '{}';", salt))
        .ok_or_else(|| missing(WEB, "^const SALT = '[^']*';"))?;
    let sk = format!(
        "const SK = {{ cfg: '{}', sys: '{}', net: '{}', bt: '{}', trk: '{}' }};",
        tag(&salt, "cfg"),
        tag(&salt, "sys"),
        tag(&salt, "net"),
        tag(&salt, "bt"),
        tag(&salt, "trk")
    );
    let web = line_sub(&web, m_web_sk, &sk).ok_or_else(|| missing(WEB, "^const SK = \\{.*\\};"))?;

    let test_text = read(TEST)?;
    let test = line_sub(&test_text, m_test_salt, &format!("SALT=\"{}\"", salt))
        .ok_or_else(|| missing(TEST, "^SALT=\"[^\"]+\""))?;
    let test = line_sub(
        &test,
        m_test_tags,
        &format!(
            "SYSTAG={}; TRKTAG={}; BEATTAG={}",
            tag(&salt, "sys"),
            tag(&salt, "trk"),
            tag(&salt, "beat")
        ),
    )
    .ok_or_else(|| missing(TEST, "^SYSTAG=[0-9a-f]+; TRKTAG=[0-9a-f]+; BEATTAG=[0-9a-f]+"))?;

    write(CONFIG, &config)?;
    write(PREF, &pref)?;
    if let Some(d) = Path::new(OLDKEYS).parent() {
        fs::create_dir_all(d).map_err(|e| format!("{}: {}", d.display(), e))?;
    }
    write(OLDKEYS, &legacy_line)?;
    write(WEB, &web)?;
    write(TEST, &test)?;
    Ok(format!(
        "salt {}, key: {}",
        if old_salt == salt { "tetap" } else { "diganti" },
        tags(&salt, false).join(" ")
    ))
}

fn main() {
    let fresh = std::env::args().nth(1).map_or(false, |a| a == "--new");
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

    #[test]
    fn web_lines_are_matched_whole() {
        assert_eq!(m_web_salt("const SALT = 'abc';\n"), Some(19));
        assert_eq!(m_web_salt("  const SALT = 'abc';\n"), None);
        let t = "x\nconst SK = { cfg: '1' };\r\ny\n";
        let out = line_sub(t, m_web_sk, "const SK = { };").unwrap();
        assert_eq!(out, "x\nconst SK = { };\r\ny\n");
    }

    #[test]
    fn test_script_lines_are_matched() {
        assert_eq!(m_test_salt("SALT=\"abc\"\n"), Some(10));
        assert_eq!(m_test_salt("SALT=\"\"\n"), None);
        let line = "SYSTAG=db078e; TRKTAG=c724d0; BEATTAG=c1e67d\n";
        assert_eq!(m_test_tags(line), Some(line.len() - 1));
        assert_eq!(m_test_tags("SYSTAG=; TRKTAG=1; BEATTAG=2\n"), None);
    }
}
