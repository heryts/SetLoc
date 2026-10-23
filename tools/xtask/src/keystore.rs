use std::fs;
use std::io::{self, BufRead, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;
use std::process::{Command, Stdio};

use crate::{status, which, R};

const ALIAS: &str = "setloc";

fn esc(s: &str) -> String {
    s.replace('\\', "\\\\")
}

fn esc_value(s: &str) -> String {
    let e = esc(s);
    match e.chars().next() {
        Some(c) if c == ' ' || c == '\t' => format!("\\{}", e),
        _ => e,
    }
}

pub fn props_text(out: &Path, pass: &str) -> String {
    format!(
        "storeFile={}\nstorePassword={}\nkeyAlias={}\nkeyPassword={}\n",
        esc(&out.to_string_lossy()),
        esc_value(pass),
        ALIAS,
        esc_value(pass)
    )
}

pub fn make_keystore(key: &Path, cert: &Path, out: &Path, pass: &str, props: &Path) -> R<()> {
    if pass.is_empty() {
        return Err("password tidak boleh kosong".into());
    }
    if which("openssl").is_none() {
        return Err("butuh openssl".into());
    }
    let mut c = Command::new("openssl");
    c.args(["pkcs12", "-export", "-inkey"])
        .arg(key)
        .arg("-in")
        .arg(cert)
        .args(["-name", ALIAS])
        .arg("-out")
        .arg(out)
        .args(["-passout", "env:KS_PASS"])
        .env("KS_PASS", pass);
    status(&mut c, "openssl pkcs12")?;
    let mut f = fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(props)
        .map_err(|e| format!("{}: {}", props.display(), e))?;
    f.write_all(props_text(out, pass).as_bytes())
        .map_err(|e| format!("{}: {}", props.display(), e))?;
    for p in [props, out] {
        fs::set_permissions(p, fs::Permissions::from_mode(0o600)).map_err(|e| format!("{}: {}", p.display(), e))?;
    }
    Ok(())
}

fn stty(arg: &str) -> bool {
    Command::new("stty")
        .arg(arg)
        .stdin(Stdio::inherit())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn read_password() -> R<String> {
    print!("Password keystore (baru): ");
    let _ = io::stdout().flush();
    let hidden = stty("-echo");
    let mut line = String::new();
    let res = io::stdin().lock().read_line(&mut line);
    if hidden {
        stty("echo");
    }
    println!();
    res.map_err(|e| format!("baca password: {}", e))?;
    if line.ends_with('\n') {
        line.pop();
    }
    Ok(line)
}

pub fn run(key: &Path, cert: &Path, out: &Path) -> R<()> {
    let pass = read_password()?;
    make_keystore(key, cert, out, &pass, Path::new("app/keystore.properties"))?;
    println!("OK: {} dan app/keystore.properties dibuat (jangan di-commit).", out.display());
    println!("SHA-1 untuk pembatasan Maps API key:");
    status(
        Command::new("openssl").args(["x509", "-in"]).arg(cert).args(["-noout", "-fingerprint", "-sha1"]),
        "openssl x509",
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backslash_is_doubled() {
        assert_eq!(esc("a\\b"), "a\\\\b");
    }

    #[test]
    fn leading_blank_is_escaped_once() {
        assert_eq!(esc_value(" a"), "\\ a");
        assert_eq!(esc_value("\ta"), "\\\ta");
        assert_eq!(esc_value("a "), "a ");
        assert_eq!(esc_value("\\ a"), "\\\\ a");
    }

    #[test]
    fn props_have_four_lines() {
        let t = props_text(Path::new("/x/y.p12"), "pw");
        assert_eq!(t, "storeFile=/x/y.p12\nstorePassword=pw\nkeyAlias=setloc\nkeyPassword=pw\n");
    }

    #[test]
    fn empty_password_is_rejected() {
        let r = make_keystore(Path::new("k"), Path::new("c"), Path::new("o"), "", Path::new("p"));
        assert!(r.is_err());
    }
}
