use std::env;
use std::fs;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use crate::jtest;
use crate::keystore;
use crate::{tempdir, which, TempDir, R};

pub(crate) fn tag(salt: &str, logical: &str) -> String {
    let s = format!("{}{}", salt, logical);
    let mut h: u32 = 0x811C_9DC5;
    for b in s.bytes() {
        h = ((h ^ b as u32).wrapping_mul(16_777_619)) & 0x7FFF_FFFF;
    }
    format!("{:06x}", h & 0x00FF_FFFF)
}

pub(crate) fn enc(salt: &str, s: &str) -> String {
    let k = salt.as_bytes();
    let mut out = String::new();
    for (i, b) in s.bytes().enumerate() {
        out.push_str(&format!("{:02x}", (b as u16 + k[i % k.len()] as u16) % 256));
    }
    out
}

pub(crate) fn dec(salt: &str, h: &str) -> Option<String> {
    let h = h.trim();
    if h.is_empty() || h.len() % 2 != 0 || !h.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let k = salt.as_bytes();
    let mut raw: Vec<u8> = Vec::new();
    for i in 0..h.len() / 2 {
        let p = u8::from_str_radix(&h[i * 2..i * 2 + 2], 16).ok()?;
        raw.push(((p as u16 + 256 - k[i % k.len()] as u16) % 256) as u8);
    }
    Some(String::from_utf8_lossy(&raw).into_owned())
}

fn sl(ms: u64) {
    thread::sleep(Duration::from_millis(ms));
}

fn cmdline(pid: u32) -> String {
    match fs::read(format!("/proc/{}/cmdline", pid)) {
        Ok(b) => {
            let v: Vec<u8> = b.into_iter().map(|c| if c == 0 { b' ' } else { c }).collect();
            String::from_utf8_lossy(&v).into_owned()
        }
        Err(_) => String::new(),
    }
}

fn all_procs() -> Vec<(u32, String)> {
    let mut v: Vec<(u32, String)> = Vec::new();
    if let Ok(rd) = fs::read_dir("/proc") {
        for e in rd.filter_map(|e| e.ok()) {
            let name = e.file_name().to_string_lossy().to_string();
            if let Ok(pid) = name.parse::<u32>() {
                let c = cmdline(pid);
                if !c.is_empty() {
                    v.push((pid, c));
                }
            }
        }
    }
    v
}

fn sig(s: &str, pid: u32) {
    let _ = Command::new("sh")
        .arg("-c")
        .arg("kill -\"$1\" \"$2\" 2>/dev/null")
        .arg("sh")
        .arg(s)
        .arg(pid.to_string())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

fn kill9(pid: u32) {
    sig("9", pid);
}

fn kill_group(pgid: &str) {
    let _ = Command::new("sh")
        .arg("-c")
        .arg("kill -9 -- \"-$1\" 2>/dev/null")
        .arg("sh")
        .arg(pgid)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

fn kill_bridges(prefix: &str) {
    let needle = format!("{}/", prefix);
    for round in 0..2 {
        for (pid, c) in all_procs() {
            if let Some(i) = c.find(&needle) {
                let rest = &c[i..];
                if let Some(j) = rest.find("setlocd") {
                    if round == 1 || rest[j..].contains("super") {
                        kill9(pid);
                    }
                }
            }
        }
    }
}

fn live_pids(lock: &Path) -> Vec<u32> {
    let mut v: Vec<u32> = Vec::new();
    if let Ok(rd) = fs::read_dir(lock) {
        for e in rd.filter_map(|e| e.ok()) {
            let name = e.file_name().to_string_lossy().to_string();
            if let Ok(pid) = name.parse::<u32>() {
                if cmdline(pid).contains("setlocd daemon") {
                    v.push(pid);
                }
            }
        }
    }
    v.sort();
    v
}

fn super_pid(dir: &Path) -> Option<u32> {
    let t = fs::read_to_string(dir.join(".super.pid")).ok()?;
    t.trim().parse::<u32>().ok()
}

fn super_alive(dir: &Path) -> bool {
    match super_pid(dir) {
        Some(p) => cmdline(p).contains("setlocd super"),
        None => false,
    }
}

fn super_count(dir: &Path) -> usize {
    let needle = format!("{}/setlocd super", dir.display());
    all_procs().iter().filter(|(_, c)| c.contains(&needle)).count()
}

fn write_exec(p: &Path, text: &str) -> R<()> {
    fs::write(p, text).map_err(|e| format!("{}: {}", p.display(), e))?;
    fs::set_permissions(p, fs::Permissions::from_mode(0o755)).map_err(|e| format!("{}: {}", p.display(), e))
}

fn touch(p: &Path) {
    let _ = fs::write(p, "");
}

fn read_line_value(text: &str, prefix: &str, end: &str) -> Option<String> {
    for l in text.lines() {
        if let Some(rest) = l.strip_prefix(prefix) {
            if let Some(n) = rest.find(end) {
                return Some(rest[..n].to_string());
            }
        }
    }
    None
}

fn config_salt(text: &str) -> Option<String> {
    for l in text.lines() {
        if let Some(i) = l.find("SALT = \"") {
            let rest = &l[i + 8..];
            if let Some(n) = rest.find('"') {
                return Some(rest[..n].to_string());
            }
        }
    }
    None
}

fn sk_value(text: &str, key: &str) -> Option<String> {
    let line = text.lines().find(|l| l.starts_with("const SK = "))?;
    let needle = format!("{}: '", key);
    let mut from = 0;
    let mut found: Option<String> = None;
    while let Some(i) = line[from..].find(&needle) {
        let at = from + i;
        let prev_ok = at == 0 || matches!(line.as_bytes()[at - 1], b'{' | b' ' | b',');
        let start = at + needle.len();
        if prev_ok {
            let hex: String = line[start..].chars().take_while(|c| c.is_ascii_hexdigit()).collect();
            if line[start + hex.len()..].starts_with('\'') {
                found = Some(hex);
            }
        }
        from = start;
    }
    found
}

fn mtime(p: &Path) -> i64 {
    fs::metadata(p).map(|m| m.mtime()).unwrap_or(-1)
}

const PROP_JAVA: &str = "import java.io.*;\nimport java.util.*;\npublic class Prop { public static void main(String[] a) throws Exception { Properties p = new Properties(); try (FileInputStream f = new FileInputStream(a[0])) { p.load(f); } System.out.print(p.getProperty(a[1])); } }\n";

struct H {
    root: PathBuf,
    assets: PathBuf,
    jsrc: PathBuf,
    salt: String,
    td: TempDir,
    path: String,
    extra: Vec<(String, String)>,
    pass: u32,
    fail: u32,
    filters: Vec<String>,
    daemon: Option<bool>,
}

impl Drop for H {
    fn drop(&mut self) {
        kill_bridges(&self.td.0.to_string_lossy());
    }
}

impl H {
    fn t(&self) -> PathBuf {
        self.td.0.clone()
    }

    fn m(&self) -> PathBuf {
        self.td.0.join("mod")
    }

    fn want(&self, name: &str) -> bool {
        if self.filters.is_empty() {
            return true;
        }
        let group = match name {
            "unsafe" | "idmap" | "lshook" => "java",
            "beat" | "lochook" | "setguard" | "syshook" | "parity" | "gnsswire" | "native" => "native",
            _ => "",
        };
        self.filters.iter().any(|f| f == name || (!group.is_empty() && f == group))
    }

    fn ok(&mut self, n: &str) {
        self.pass += 1;
        println!("  ok   {}", n);
    }

    fn bad(&mut self, n: &str) {
        self.fail += 1;
        println!("  FAIL {}", n);
    }

    fn check(&mut self, n: &str, c: bool) {
        if c {
            self.ok(n);
        } else {
            self.bad(n);
        }
    }

    fn setenv(&self, c: &mut Command) {
        c.env("PATH", &self.path);
        for (k, v) in &self.extra {
            c.env(k, v);
        }
    }

    fn quiet(&self, c: &mut Command) -> bool {
        self.setenv(c);
        c.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
        c.status().map(|s| s.success()).unwrap_or(false)
    }

    fn capture(&self, c: &mut Command) -> String {
        self.setenv(c);
        c.stdin(Stdio::null()).stderr(Stdio::null());
        match c.output() {
            Ok(o) => String::from_utf8_lossy(&o.stdout).into_owned(),
            Err(_) => String::new(),
        }
    }

    fn bridge(&self, dir: &Path, args: &[&str]) -> bool {
        let mut c = Command::new("sh");
        c.arg(dir.join("bridge.sh")).args(args);
        self.quiet(&mut c)
    }

    fn bridge_out(&self, dir: &Path, args: &[&str]) -> String {
        let mut c = Command::new("sh");
        c.arg(dir.join("bridge.sh")).args(args);
        self.capture(&mut c)
    }

    fn timed(&self, dir: &Path, args: &[&str]) -> u64 {
        let s = Instant::now();
        self.bridge(dir, args);
        s.elapsed().as_secs()
    }

    fn bg_bridge(&self, dir: &Path, mode: &str) {
        let mut c = Command::new("sh");
        c.arg("-c")
            .arg("( setsid sh \"$1\" \"$2\" >/dev/null 2>&1 & )")
            .arg("sh")
            .arg(dir.join("bridge.sh"))
            .arg(mode);
        self.quiet(&mut c);
    }

    fn bg_service(&self, s: &Path) {
        let mut c = Command::new("sh");
        c.arg("-c")
            .arg("( setsid sh -c 'echo $$ > \"$1/pgid\"; exec sh \"$1/service.sh\"' sh \"$1\" >/dev/null 2>&1 & )")
            .arg("sh")
            .arg(s);
        self.quiet(&mut c);
    }

    fn make_fakes(&self) -> R<()> {
        let b = self.t().join("bin");
        let st = self.t().join("st");
        fs::create_dir_all(&b).map_err(|e| e.to_string())?;
        fs::create_dir_all(&st).map_err(|e| e.to_string())?;
        write_exec(&b.join("cmd"), "#!/bin/sh\n[ \"$1\" = settings ] && shift && exec settings \"$@\"; exit 0\n")?;
        for n in ["pm", "am", "dumpsys", "logcat"] {
            write_exec(&b.join(n), "#!/bin/sh\nexit 0\n")?;
        }
        write_exec(
            &b.join("settings"),
            &format!(
                "#!/bin/sh\nD=\"{}\"\ncase \"$1\" in\n  get) cat \"$D/$3\" 2>/dev/null || echo null ;;\n  put) printf '%s' \"$4\" > \"$D/$3\" ;;\nesac\n",
                st.display()
            ),
        )?;
        write_exec(
            &b.join("getprop"),
            &format!("#!/bin/sh\ncat \"{}/bootflag\" 2>/dev/null || echo 1\n", self.t().display()),
        )
    }

    fn daemon_bin(&self) -> R<PathBuf> {
        if let Some(b) = crate::env_set("SETLOCD_BIN") {
            return Ok(PathBuf::from(b));
        }
        if which("cargo").is_none() {
            return Err("butuh cargo atau SETLOCD_BIN".into());
        }
        crate::status(
            Command::new("cargo")
                .args(["build", "--release", "-q"])
                .env_remove("CARGO_TARGET_DIR")
                .current_dir(self.root.join("daemon")),
            "build daemon",
        )?;
        Ok(self.root.join("daemon/target/release/setlocd"))
    }

    fn need_daemon(&mut self) -> bool {
        if let Some(r) = self.daemon {
            return r;
        }
        let r = self.prep_daemon();
        self.daemon = Some(r);
        r
    }

    fn prep_daemon(&mut self) -> bool {
        let bin = match self.daemon_bin() {
            Ok(b) => b,
            Err(e) => {
                self.bad(&format!("daemon: {}", e));
                return false;
            }
        };
        let m = self.m();
        if fs::create_dir_all(&m).is_err() {
            self.bad("daemon: folder uji tidak bisa dibuat");
            return false;
        }
        for f in ["bridge.sh", "gpsz.sh"] {
            if fs::copy(self.assets.join(f), m.join(f)).is_err() {
                self.bad(&format!("daemon: {} tidak bisa disalin", f));
                return false;
            }
        }
        if fs::copy(&bin, m.join("setlocd")).is_err() {
            self.bad(&format!("daemon: {} tidak bisa disalin", bin.display()));
            return false;
        }
        let _ = fs::set_permissions(m.join("setlocd"), fs::Permissions::from_mode(0o755));
        touch(&m.join("disable_super"));
        true
    }

    fn sec_sintaks(&mut self) {
        println!("[sintaks]");
        let mut files: Vec<PathBuf> = Vec::new();
        for d in [self.assets.clone(), self.assets.join("customize.d")] {
            if let Ok(rd) = fs::read_dir(&d) {
                for e in rd.filter_map(|e| e.ok()) {
                    let p = e.path();
                    if p.extension().is_some_and(|x| x == "sh") {
                        files.push(p);
                    }
                }
            }
        }
        files.sort();
        for f in files {
            let name = f.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
            let ok = Command::new("sh").arg("-n").arg(&f).status().map(|s| s.success()).unwrap_or(false);
            self.check(&format!("sh -n {}", name), ok);
        }
    }

    fn sec_daemon(&mut self) {
        let m = self.m();
        let lock = m.join(".daemon.lock");

        println!("[daemon: arbitrasi]");
        for _ in 0..6 {
            self.bg_bridge(&m, "daemon");
        }
        sl(4000);
        let c = live_pids(&lock).len() == 1;
        self.check("6 daemon bersamaan -> tepat 1 hidup", c);

        println!("[daemon: SIGKILL dan supervisor]");
        let w = live_pids(&lock).first().copied();
        if let Some(p) = w {
            kill9(p);
        }
        sl(300);
        self.bridge(&m, &["ensure", "super"]);
        sl(2000);
        let l = live_pids(&lock);
        let c1 = l.len() == 1;
        let c2 = l.first().copied() != w;
        self.check("ensure super menghidupkan lagi", c1);
        self.check("pid baru berbeda", c2);

        println!("[daemon: TERM]");
        if let Some(p) = live_pids(&lock).first().copied() {
            sig("TERM", p);
        }
        sl(7000);
        let c1 = live_pids(&lock).is_empty();
        let c2 = !m.join("bridge.log").exists();
        let c3 = !lock.exists();
        self.check("tidak ada daemon hidup", c1);
        self.check("tidak ada bridge.log", c2);
        self.check("folder kunci bersih", c3);

        println!("[ensure: waktu]");
        let dt = self.timed(&m, &["ensure", "super"]);
        let c = dt <= 5 && live_pids(&lock).len() == 1;
        self.check("ensure saat folder kosong menghidupkan daemon (<= 5 dtk)", c);
        let dt = self.timed(&m, &["ensure"]);
        self.check("ensure saat daemon hidup cepat (<= 1 dtk)", dt <= 1);

        println!("[daemon: folder kunci dihapus saat daemon hidup]");
        let w = live_pids(&lock).first().copied();
        let _ = fs::remove_dir_all(&lock);
        let dt = self.timed(&m, &["ensure", "super"]);
        sl(1000);
        let l = live_pids(&lock);
        let c1 = l.len() == 1 && l.first().copied() == w && lock.is_dir();
        self.check("ensure tidak menyalakan daemon kedua (pid sama, tetap 1)", c1);
        self.check("ensure menunggu paling lama ~3 dtk", dt <= 3);

        println!("[daemon: ensure sesudah SIGKILL tidak menunggu]");
        let w = live_pids(&lock).first().copied();
        if let Some(p) = w {
            kill9(p);
        }
        sl(300);
        let dt = self.timed(&m, &["ensure", "super"]);
        sl(1000);
        self.check("restart langsung (<= 2 dtk, tanpa jeda tunggu 2 dtk)", dt <= 2);
        let l = live_pids(&lock);
        let c = l.len() == 1 && l.first().copied() != w;
        self.check("daemon baru hidup, pid berbeda", c);

        println!("[daemon: folder kunci dihapus saat menunggu boot]");
        if let Some(p) = live_pids(&lock).first().copied() {
            sig("TERM", p);
        }
        sl(7000);
        let _ = fs::write(self.t().join("bootflag"), "0\n");
        self.bg_bridge(&m, "daemon");
        sl(2000);
        let w = live_pids(&lock).first().copied();
        let _ = fs::remove_dir_all(&lock);
        sl(2000);
        let l = live_pids(&lock);
        let c = l.first().copied() == w && w.is_some() && lock.is_dir();
        self.check("lock pulih oleh daemon yang sama (<= 2 dtk)", c);
        let _ = fs::remove_dir_all(&lock);
        self.bridge(&m, &["ensure"]);
        sl(1000);
        let l = live_pids(&lock);
        let c = l.len() == 1 && l.first().copied() == w;
        self.check("ensure saat boot tidak membuat daemon kedua", c);
        let _ = fs::write(self.t().join("bootflag"), "1\n");
        for p in live_pids(&lock) {
            sig("TERM", p);
        }
        sl(7000);
    }

    fn sec_super(&mut self) {
        println!("[supervisor: saling mengawasi]");
        let m = self.m();
        let sv = self.t().join("sv");
        let _ = fs::remove_dir_all(&sv);
        if fs::create_dir_all(&sv).is_err() {
            self.bad("supervisor: folder uji tidak bisa dibuat");
            return;
        }
        for f in ["bridge.sh", "gpsz.sh"] {
            let _ = fs::copy(self.assets.join(f), sv.join(f));
        }
        let _ = fs::copy(m.join("setlocd"), sv.join("setlocd"));
        self.extra.push(("SETLOCD_SUPER_SLEEP_MS".to_string(), "1000".to_string()));
        self.extra.push(("SETLOCD_SUPER_CHECK_CS".to_string(), "200".to_string()));
        let lock = sv.join(".daemon.lock");
        let svs = sv.to_string_lossy().to_string();

        self.bridge(&sv, &["ensure"]);
        sl(1000);
        let c = live_pids(&lock).len() == 1 && super_alive(&sv);
        self.check("ensure menghidupkan daemon dan supervisor", c);
        let c = super_count(&sv) == 1;
        self.check("hanya ada satu proses supervisor", c);

        let sw = live_pids(&lock).first().copied();
        if let Some(p) = sw {
            kill9(p);
        }
        sl(4000);
        let l = live_pids(&lock);
        let c = l.len() == 1 && l.first().copied() != sw;
        self.check("supervisor menghidupkan lagi daemon yang di-SIGKILL", c);

        sl(6000);
        let sp0 = super_pid(&sv);
        if let Some(p) = sp0 {
            kill9(p);
        }
        sl(8000);
        let c = super_alive(&sv) && super_pid(&sv) != sp0;
        self.check("daemon menghidupkan lagi supervisor yang di-SIGKILL", c);
        let c = super_count(&sv) == 1;
        self.check("supervisor pengganti tetap satu proses", c);

        touch(&sv.join("disable_super"));
        let sp0 = super_pid(&sv);
        if let Some(p) = sp0 {
            kill9(p);
        }
        sl(8000);
        let c = !super_alive(&sv);
        self.check("disable_super: supervisor tidak dihidupkan lagi oleh daemon", c);
        let sw = live_pids(&lock).first().copied();
        if let Some(p) = sw {
            kill9(p);
        }
        sl(4000);
        let c = live_pids(&lock).is_empty();
        self.check("disable_super: daemon yang di-kill tidak dihidupkan lagi", c);

        let _ = fs::remove_file(sv.join("disable_super"));
        self.bridge(&sv, &["ensure"]);
        sl(1000);
        let c = live_pids(&lock).len() == 1 && super_alive(&sv);
        self.check("ensure sesudah disable_super dihapus menghidupkan keduanya", c);
        let out = self.bridge_out(&sv, &["status"]);
        let c = out.lines().any(|l| l == "super=running");
        self.check("status melaporkan super=running", c);
        kill_bridges(&svs);
        sl(300);
        let out = self.bridge_out(&sv, &["status"]);
        let c = out.lines().any(|l| l == "super=stopped");
        self.check("status melaporkan super=stopped saat mati", c);
        for _ in 0..3 {
            self.bg_bridge(&sv, "super");
        }
        sl(3000);
        let c = super_count(&sv) == 1;
        self.check("tiga supervisor bersamaan -> tersisa satu", c);
        kill_bridges(&svs);
        sl(300);
        self.extra.clear();
    }

    fn prop_get(&self, kd: &Path, props: &Path, key: &str) -> Option<String> {
        let o = Command::new("java")
            .arg(kd.join("Prop.java"))
            .arg(props)
            .arg(key)
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output()
            .ok()?;
        if !o.status.success() {
            return None;
        }
        Some(String::from_utf8_lossy(&o.stdout).into_owned())
    }

    fn p12_ok(&self, p12: &Path, pw: &str) -> bool {
        Command::new("openssl")
            .args(["pkcs12", "-in"])
            .arg(p12)
            .args(["-passin", "env:P", "-nokeys"])
            .env("P", pw)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .map(|s| s.success())
            .unwrap_or(false)
    }

    fn ks_roundtrip(&self, kd: &Path, pw: &str, p12: &Path, with_file: bool) -> bool {
        let props = kd.join("app/keystore.properties");
        let _ = fs::remove_file(&props);
        let _ = fs::remove_file(p12);
        let key = kd.join("k.pem");
        let cert = kd.join("c.pem");
        if keystore::make_keystore(&key, &cert, p12, pw, &props).is_err() {
            return false;
        }
        if !self.p12_ok(p12, pw) {
            return false;
        }
        if self.prop_get(kd, &props, "storePassword").as_deref() != Some(pw) {
            return false;
        }
        if self.prop_get(kd, &props, "keyPassword").as_deref() != Some(pw) {
            return false;
        }
        if with_file {
            let pf = p12.to_string_lossy().to_string();
            if self.prop_get(kd, &props, "storeFile").as_deref() != Some(pf.as_str()) {
                return false;
            }
        }
        true
    }

    fn sec_keystore(&mut self) {
        println!("[keystore]");
        let have = which("openssl").is_some() && which("java").is_some() && jtest::javac_cmd().is_some();
        if !have {
            self.bad("tes keystore dilewati: openssl/java tidak ada");
            return;
        }
        let kd = self.t().join("ks");
        let _ = fs::remove_dir_all(&kd);
        if fs::create_dir_all(kd.join("app")).is_err() {
            self.bad("keystore: folder uji tidak bisa dibuat");
            return;
        }
        let _ = fs::write(kd.join("Prop.java"), PROP_JAVA);
        let made = Command::new("openssl")
            .args(["req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes", "-keyout"])
            .arg(kd.join("k.pem"))
            .arg("-out")
            .arg(kd.join("c.pem"))
            .args(["-subj", "/CN=t", "-days", "2"])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .map(|s| s.success())
            .unwrap_or(false);
        if !made {
            self.bad("keystore: openssl gagal membuat sertifikat uji");
            return;
        }
        let pw = "a\\b$c \"d\" `e` \\\\f";
        let c = self.ks_roundtrip(&kd, pw, &kd.join("o.p12"), true);
        self.check("keystore: password dengan backslash, $, kutip dan spasi utuh di p12 dan keystore.properties", c);
        let mut all = true;
        for pw in ["  lead space", " lead", "trail ", " both ", "\ttab"] {
            if !self.ks_roundtrip(&kd, pw, &kd.join("o2.p12"), false) {
                all = false;
            }
        }
        self.check("keystore: spasi/tab di awal atau akhir password utuh (p12 dan Properties)", all);
        let o3 = kd.join("o3.p12");
        let r = keystore::make_keystore(&kd.join("k.pem"), &kd.join("c.pem"), &o3, "", &kd.join("app/keystore.properties"));
        let c = r.is_err() && !o3.exists();
        self.check("keystore: password kosong ditolak", c);
    }

    fn put_setlocd(&self, dir: &Path) {
        let _ = fs::copy(self.m().join("setlocd"), dir.join("setlocd"));
        let _ = fs::set_permissions(dir.join("setlocd"), fs::Permissions::from_mode(0o755));
    }

    fn run_install(&self, modpath: &Path, old: Option<&Path>) -> String {
        let pre = if old.is_some() { "OLD_MOD=\"$3\"; " } else { "" };
        let script = format!("ui_print() {{ echo \"$*\"; }}; MODPATH=\"$1\"; {}. \"$2\"", pre);
        let mut c = Command::new("sh");
        c.arg("-c")
            .arg(script)
            .arg("sh")
            .arg(modpath)
            .arg(self.assets.join("customize.d/40-install-apk.sh"));
        if let Some(o) = old {
            c.arg(o);
        }
        self.capture(&mut c)
    }

    fn sec_install(&mut self) {
        println!("[40-install-apk.sh]");
        let m = self.m();
        let _ = fs::create_dir_all(m.join("apk"));
        let _ = fs::write(m.join("apk/SetLoc.apk"), "apk\n");
        let pm = self.t().join("bin/pm");
        let _ = write_exec(&pm, "#!/bin/sh\n[ \"$1\" = install ] && echo Success\nexit 0\n");
        let out = self.run_install(&m, None);
        let sum = m.join(".apk_sum");
        let c = out.contains("terpasang") && fs::metadata(&sum).map(|x| x.len() > 0).unwrap_or(false);
        self.check("install sukses: pesan terpasang dan .apk_sum dibuat", c);
        let _ = fs::remove_file(&sum);
        let _ = write_exec(&pm, "#!/bin/sh\n[ \"$1\" = install ] && echo \"Failure [INSTALL_FAILED]\"\nexit 0\n");
        let out = self.run_install(&m, None);
        let c = out.contains("pm install gagal") && !sum.exists();
        self.check("install gagal: pesan gagal, .apk_sum tidak dibuat", c);

        println!("[40-install-apk.sh: konfigurasi lama dipertahankan]");
        let o = self.t().join("old");
        let n = self.t().join("new");
        let _ = fs::remove_dir_all(&o);
        let _ = fs::remove_dir_all(&n);
        let _ = fs::create_dir_all(o.join("packages"));
        let _ = fs::create_dir_all(n.join("packages"));
        self.put_setlocd(&n);
        self.put_setlocd(&o);
        for f in ["com.a.app", "com.b.app", ".all", "android", ".sysoff"] {
            touch(&o.join("packages").join(f));
        }
        let _ = Command::new("touch")
            .arg("-d")
            .arg("2020-01-01 00:00:00")
            .arg(o.join("packages/android"))
            .status();
        touch(&o.join(".hb_default"));
        touch(&o.join("disable_super"));
        touch(&o.join("disable_trk"));
        let _ = fs::write(o.join(".apk_sum"), "OLDSUM\n");
        let _ = fs::write(o.join("bridge.log"), "log\n");
        let out = self.run_install(&n, Some(&o));
        let c = ["com.a.app", "com.b.app", ".all", "android", ".sysoff"].iter().all(|f| n.join("packages").join(f).exists());
        self.check("daftar app, .all, android, .sysoff ikut", c);
        let c = mtime(&n.join("packages/android")) == mtime(&o.join("packages/android"));
        self.check("mtime android dipertahankan (status live tidak berubah jadi pending)", c);
        let c = n.join(".hb_default").exists() && n.join("disable_super").exists() && n.join("disable_trk").exists();
        self.check(".hb_default, disable_super, disable_trk ikut", c);
        let c = !n.join(".apk_sum").exists() && !n.join("bridge.log").exists();
        self.check(".apk_sum lama dan bridge.log tidak ikut", c);
        let old_sum = fs::read_to_string(o.join(".apk_sum")).unwrap_or_default();
        let c = o.join("packages/com.a.app").exists() && old_sum.trim() == "OLDSUM";
        self.check("folder modul lama tidak diubah", c);
        self.check("pesan dipertahankan tercetak", out.contains("Konfigurasi lama dipertahankan"));
        let n2 = self.t().join("new2");
        let _ = fs::create_dir_all(&n2);
        self.put_setlocd(&n2);
        let none = self.t().join("tidakada");
        let out = self.run_install(&n2, Some(&none));
        let c = !n2.join("packages").exists() && !out.contains("Konfigurasi lama");
        self.check("tanpa modul lama: tidak ada yang dibuat", c);
        let out = self.run_install(&o, Some(&o));
        let c = o.join("packages/com.a.app").exists() && !out.contains("gagal menyalin");
        self.check("modul lama sama dengan tujuan: dilewati tanpa error", c);
    }

    fn svc(&self, value: Option<&str>, marker: bool) -> PathBuf {
        let s = self.t().join("svc");
        let _ = fs::remove_dir_all(&s);
        let _ = fs::create_dir_all(&s);
        for f in ["service.sh", "bridge.sh"] {
            let _ = fs::copy(self.assets.join(f), s.join(f));
        }
        let _ = fs::copy(self.m().join("setlocd"), s.join("setlocd"));
        if marker {
            touch(&s.join(".hb_default"));
        }
        let key = self.t().join("st").join(tag(&self.salt, "sys"));
        match value {
            None => {
                let _ = fs::remove_file(&key);
            }
            Some(v) => {
                let _ = fs::write(&key, format!("{}\n", enc(&self.salt, v)));
            }
        }
        self.bg_service(&s);
        sl(1500);
        if let Ok(g) = fs::read_to_string(s.join("pgid")) {
            kill_group(g.trim());
        }
        kill_bridges(&s.to_string_lossy());
        s
    }

    fn sysval(&self) -> String {
        let key = self.t().join("st").join(tag(&self.salt, "sys"));
        let raw = fs::read_to_string(&key).unwrap_or_default();
        dec(&self.salt, &raw).unwrap_or_default()
    }

    fn sec_service(&mut self) {
        println!("[service.sh: mode system hook tidak ditimpa]");
        let s = self.svc(Some("1"), false);
        let c = self.sysval() == "1" && s.join(".hb_default").exists();
        self.check("reinstall: mode penuh (1) tidak ditimpa jadi heartbeat, penanda dibuat", c);
        let s = self.svc(Some("0"), false);
        let c = self.sysval() == "0" && s.join(".hb_default").exists();
        self.check("reinstall: mode mati (0) tidak ditimpa, penanda dibuat", c);
        let s = self.svc(None, false);
        let c = self.sysval() == "2" && s.join(".hb_default").exists();
        self.check("pemasangan pertama: default heartbeat (2) diterapkan, penanda dibuat", c);
        let _ = self.svc(Some("1"), true);
        let c = self.sysval() == "1";
        self.check("penanda sudah ada: tidak menyentuh pengaturan", c);
    }

    fn rd(&self, p: &Path) -> String {
        match fs::read(p) {
            Ok(b) => String::from_utf8_lossy(&b).into_owned(),
            Err(_) => String::new(),
        }
    }

    fn sec_kunci(&mut self) {
        println!("[kunci Settings.Global dan beat terenkripsi]");
        let web = self.rd(&self.assets.join("webroot/index.html"));
        let cfg = self.rd(&self.jsrc.join("Config.java"));
        let web_salt = read_line_value(&web, "const SALT = '", "';");
        let cfg_salt = config_salt(&cfg);
        let salt = self.salt.clone();
        let c = web_salt.as_deref() == Some(salt.as_str());
        self.check("SALT WebUI sama dengan .salt (dekode state system/heartbeat)", c);
        let c = cfg_salt.as_deref() == Some(salt.as_str());
        self.check("SALT Config.java sama dengan .salt", c);
        let c = web_salt.is_some() && web_salt == cfg_salt;
        self.check("SALT WebUI sama dengan Config.java", c);
        for jk in ["cfg", "sys", "net", "bt", "trk", "live"] {
            let want = tag(&salt, jk);
            let got = sk_value(&web, jk);
            let c = got.as_deref() == Some(want.as_str());
            self.check(&format!("kunci WebUI SK.{} cocok dengan tag({})", jk, jk), c);
        }
        let c = web.contains("SK.trk} 2>/dev/null; read U");
        self.check("readCfg: perintah shell membaca cfg, trk, dan uptime hanya saat tk=1", c);
    }

    fn sec_struktur(&mut self) {
        println!("[struktur modul]");
        let lh = self.rd(&self.jsrc.join("LocationHooks.java"));
        let c = !lh.contains("WeakHashMap") && lh.contains("WeakIdentityMap<Mark> PER_OBJ");
        self.check("LocationHooks tidak memakai WeakHashMap/Collections.synchronizedMap untuk PER_OBJ", c);
        let mdir = self.root.join("module");
        let settings = self.rd(&mdir.join("settings.gradle.kts"));
        let c = settings.contains("include(\":stub\")") && settings.contains("include(\":unsafe\")") && settings.contains("include(\":zygote\")");
        self.check("settings.gradle.kts memuat :stub, :unsafe, dan :zygote", c);
        let zb = self.rd(&mdir.join("zygote/build.gradle.kts"));
        let ub = self.rd(&mdir.join("unsafe/build.gradle.kts"));
        let c = zb.contains("module = \"SunUnsafeWrapper\"") && zb.contains("implementation(project(\":unsafe\"))");
        self.check("zygote mengeluarkan SunUnsafeWrapper dan memakai :unsafe", c);
        let c = ub.contains("compileOnly(project(\":stub\"))") && !zb.contains("project(\":stub\")");
        self.check("stub hanya compileOnly (tidak ikut ke dex)", c);
        let mut srcs: Vec<PathBuf> = Vec::new();
        collect_java(&self.jsrc, &mut srcs);
        let mut stray = false;
        for f in &srcs {
            let name = f.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            let t = self.rd(f);
            let vm = t.contains("com.v7878.unsafe") || t.contains("com.v7878.vmtools") || t.contains("Hooks.hook(");
            if vm && name != "SystemHooks.java" && name != "LegacyFrame.java" {
                stray = true;
            }
            if t.contains("LegacyFrame") && name != "Frames.java" && name != "LegacyFrame.java" {
                stray = true;
            }
        }
        self.check("AndroidVMTools hanya dipakai SystemHooks, Frames.hookSys, dan LegacyFrame (hook di aplikasi tetap LSPlant)", !stray);
        let sh = self.rd(&self.jsrc.join("SystemHooks.java"));
        let ze = self.rd(&self.jsrc.join("ZygoteEntry.java"));
        let c = !sh.contains("LsNative") && !sh.contains("Frames.hook(") && sh.contains("Frames.hookSys(");
        self.check("SystemHooks tidak menyentuh LSPlant", c);
        let sys_branch = ze
            .split("if (isSystemServer()) {")
            .nth(1)
            .and_then(|r| r.split("return;").next())
            .unwrap_or("");
        let c = sys_branch.contains("loadRust()")
            && sys_branch.contains("disable_rs_sys")
            && !sys_branch.contains("LsNative")
            && !ze.contains("SysGuard");
        self.check("system_server hanya memuat Rust (configcore, lochooks) di premain, tanpa LSPlant/ShadowHook, dengan flag disable_rs_sys", c);
    }

    fn sec_webui(&mut self) {
        println!("[webui: posisi dari jendela trk]");
        if which("node").is_none() {
            self.bad("tes webui dilewati: node tidak ada");
            return;
        }
        let web = self.rd(&self.assets.join("webroot/index.html"));
        let mut js = String::new();
        let mut on = false;
        for l in web.lines() {
            if l.starts_with("function trackPos") {
                on = true;
            }
            if on {
                js.push_str(l);
                js.push('\n');
                if l == "}" {
                    break;
                }
            }
        }
        js.push_str("const r = trackPos('t=1000;k=500;a=1000;d=,-7.0,110.0,0,0,0|,-7.2,110.4,0,0,0|', 1250);\n");
        js.push_str("const e = trackPos('t=1000;k=500;a=1000;d=,-7.0,110.0,0,0,0|', 5000);\n");
        js.push_str("console.log(r[0].toFixed(3) + ' ' + r[1].toFixed(3) + ' ' + e[0].toFixed(3) + ' ' + (trackPos('t=1;k=500;d=,1,2|', 5) === null) + ' ' + (trackPos('k=500;d=,1,2,0,0,0|', 5) === null));\n");
        let f = self.t().join("tp.js");
        let _ = fs::write(&f, js);
        let o = Command::new("node").arg(&f).stdin(Stdio::null()).stderr(Stdio::null()).output();
        let got = match o {
            Ok(x) => String::from_utf8_lossy(&x.stdout).trim().to_string(),
            Err(_) => String::new(),
        };
        self.check("trackPos: interpolasi tengah tick dan tahan titik terakhir, data rusak -> null", got == "-7.100 110.200 -7.000 true true");
    }

    fn java_suite(&mut self, jc: &Option<Vec<String>>, name: &str, label: &str) {
        if !self.want(name) {
            return;
        }
        match jc {
            None => self.bad("tes Java dilewati: javac/java tidak ada"),
            Some(j) => {
                let e = jtest::Env { root: self.root.clone(), salt: self.salt.clone(), jc: j.clone() };
                let (ok, log) = jtest::run_suite(name, &e);
                if ok {
                    self.ok(label);
                } else {
                    println!("{}", log);
                    self.bad(label);
                }
            }
        }
    }

    fn cargo_suite(&mut self, krate: &str) {
        let label = format!("{}: cargo test", krate);
        let mut log = String::new();
        let manifest = self.root.join("native").join(krate).join("Cargo.toml");
        let args = vec![
            "test".to_string(),
            "-q".to_string(),
            "--manifest-path".to_string(),
            manifest.to_string_lossy().to_string(),
        ];
        match jtest::cargo(args, &mut log) {
            Ok(()) => self.ok(&label),
            Err(m) => {
                println!("{}{}", log, m);
                self.bad(&label);
            }
        }
    }

    fn sections(&mut self) {
        let shell = ["daemon", "supervisor", "install", "service"];
        let need_shell = shell.iter().any(|s| self.want(s));
        if self.want("sintaks") {
            self.sec_sintaks();
        }
        let shell_ok = !need_shell || self.need_daemon();
        if self.want("daemon") && shell_ok {
            self.sec_daemon();
        }
        if self.want("supervisor") && shell_ok {
            self.sec_super();
        }
        if self.want("keystore") {
            self.sec_keystore();
        }
        if self.want("install") && shell_ok {
            self.sec_install();
        }
        if self.want("service") && shell_ok {
            self.sec_service();
        }
        if self.want("kunci") {
            self.sec_kunci();
        }
        let jc = jtest::javac_cmd();
        if self.want("beat") {
            println!("[hook: Config.checkBeat, parse, track (beat daemon)]");
        }
        self.java_suite(&jc, "beat", "checkBeat + refresh + parse + track + baca file: freeze, off, resume, jeda poll, titik tunggal, snapshot, flag sistem, onReady sekali");
        if self.want("unsafe") || self.want("idmap") {
            println!("[unsafe: SunUnsafe milik sendiri menggantikan SunUnsafeWrapper]");
        }
        self.java_suite(&jc, "unsafe", "SunUnsafe: API identik v1.0.2, nol warning javac, bytecode valid, memori/CAS/fence jalan");
        self.java_suite(&jc, "idmap", "PER_OBJ berbasis identitas: equals/hashCode Location tidak dipanggil, entri di-GC dibersihkan");
        if self.want("struktur") {
            self.sec_struktur();
        }
        if self.want("webui") {
            self.sec_webui();
        }
        if self.want("native") || self.want("parity") || self.want("gnsswire") {
            println!("[native: configcore, lochooks, routecore]");
            if which("cargo").is_none() {
                self.bad("tes native dilewati: cargo tidak ada");
            } else {
                if self.want("native") {
                    self.cargo_suite("configcore");
                    self.cargo_suite("lochooks");
                    self.cargo_suite("routecore");
                }
                self.java_suite(&jc, "parity", "JNI lochooks asli: Fix.apply, stamp, lastKnown, snapshot sama dengan panggilan native langsung");
                self.java_suite(&jc, "gnsswire", "GnssFake lewat JNI asli: verifikasi vektor tetap, snapshot satelit, NMEA, angka measurements");
            }
        }
        if ["lshook", "lochook", "setguard", "syshook"].iter().any(|s| self.want(s)) {
            println!("[hook LSPlant]");
        }
        self.java_suite(&jc, "lshook", "PackageGuardLs lewat mesin hook palsu");
        self.java_suite(&jc, "lochook", "LocationHooks lewat mesin hook palsu (LSPlant, disable_ls_loc)");
        self.java_suite(&jc, "setguard", "SettingsGuard lewat mesin hook palsu (LSPlant, disable_ls_set)");
        self.java_suite(&jc, "syshook", "SystemHooks lewat mesin hook AndroidVMTools palsu (10 hook, mesin tidak termuat, satu method gagal)");
    }
}

pub fn run(args: &[String]) -> R<()> {
    let root = env::current_dir().map_err(|e| e.to_string())?;
    let salt = fs::read_to_string(root.join(".salt")).map_err(|e| format!(".salt: {}", e))?.trim().to_string();
    if salt.len() != 12 || !salt.bytes().all(|b| b.is_ascii_alphanumeric()) {
        return Err(".salt harus 12 karakter huruf/angka".into());
    }
    let td = tempdir("harness")?;
    let old = env::var("PATH").unwrap_or_default();
    let path = format!("{}/bin:{}", td.0.display(), old);
    let assets = root.join("module/zygote/src/main/assets");
    let jsrc = root.join("module/zygote/src/main/java/io/gpsz/zygote");
    let mut h = H {
        root,
        assets,
        jsrc,
        salt,
        td,
        path,
        extra: Vec::new(),
        pass: 0,
        fail: 0,
        filters: args.to_vec(),
        daemon: None,
    };
    h.make_fakes()?;
    h.sections();
    println!();
    println!("lulus: {}  gagal: {}", h.pass, h.fail);
    if h.fail == 0 {
        Ok(())
    } else {
        Err(format!("{} pemeriksaan gagal", h.fail))
    }
}

fn collect_java(dir: &std::path::Path, out: &mut Vec<PathBuf>) {
    if let Ok(rd) = std::fs::read_dir(dir) {
        for e in rd.filter_map(|e| e.ok()) {
            let p = e.path();
            if p.is_dir() {
                collect_java(&p, out);
            } else if p.extension().is_some_and(|x| x == "java") {
                out.push(p);
            }
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
    }

    #[test]
    fn enc_dec_roundtrip() {
        let s = "enabled=1;lat=-7.25;lng=110.5";
        assert_eq!(dec("Vzvwk4Rxj7Ps", &enc("Vzvwk4Rxj7Ps", s)).as_deref(), Some(s));
        assert_eq!(dec("Vzvwk4Rxj7Ps", ""), None);
        assert_eq!(dec("Vzvwk4Rxj7Ps", "abc"), None);
    }

    #[test]
    fn web_values_are_read() {
        let t = "x\nconst SALT = 'abc';\nconst SK = { cfg: '956349', sys: 'db078e', net: '1', bt: '2', trk: '3' };\n";
        assert_eq!(read_line_value(t, "const SALT = '", "';").as_deref(), Some("abc"));
        assert_eq!(sk_value(t, "sys").as_deref(), Some("db078e"));
        assert_eq!(sk_value(t, "cfg").as_deref(), Some("956349"));
        assert_eq!(sk_value(t, "zzz"), None);
        assert_eq!(config_salt("static final String SALT = \"Vzvwk4Rxj7Ps\";").as_deref(), Some("Vzvwk4Rxj7Ps"));
    }
}
