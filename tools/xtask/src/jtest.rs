use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

use crate::harness::{enc, tag};
use crate::{env_set, tempdir, which, R};

pub struct Env {
    pub root: PathBuf,
    pub salt: String,
    pub jc: Vec<String>,
}

impl Env {
    fn jsrc(&self) -> PathBuf {
        self.root.join("module/zygote/src/main/java/io/gpsz/zygote")
    }

    fn t(&self) -> PathBuf {
        self.root.join("module/test")
    }
}

pub fn javac_cmd() -> Option<Vec<String>> {
    if which("javac").is_some() {
        return Some(vec!["javac".to_string()]);
    }
    which("java")?;
    let o = Command::new("java").arg("--list-modules").output().ok()?;
    if String::from_utf8_lossy(&o.stdout).lines().any(|l| l.starts_with("jdk.compiler@")) {
        return Some(vec![
            "java".to_string(),
            "-m".to_string(),
            "jdk.compiler/com.sun.tools.javac.Main".to_string(),
        ]);
    }
    None
}

fn lossy(b: &[u8]) -> String {
    String::from_utf8_lossy(b).into_owned()
}

fn ps(p: &Path) -> String {
    p.to_string_lossy().to_string()
}

fn sv(a: &[&str]) -> Vec<String> {
    a.iter().map(|x| x.to_string()).collect()
}

fn rd(p: &Path) -> String {
    match fs::read(p) {
        Ok(b) => lossy(&b),
        Err(_) => String::new(),
    }
}

pub fn cargo(args: Vec<String>, log: &mut String) -> R<()> {
    let o = Command::new("cargo")
        .args(&args)
        .env_remove("CARGO_TARGET_DIR")
        .output()
        .map_err(|x| format!("cargo: {}", x))?;
    log.push_str(&lossy(&o.stdout));
    log.push_str(&lossy(&o.stderr));
    if o.status.success() {
        Ok(())
    } else {
        Err(format!("cargo {} gagal", args.join(" ")))
    }
}

fn copy_tree(src: &Path, dst: &Path) -> R<()> {
    fs::create_dir_all(dst).map_err(|x| format!("{}: {}", dst.display(), x))?;
    let rdir = fs::read_dir(src).map_err(|x| format!("{}: {}", src.display(), x))?;
    for e in rdir.filter_map(|e| e.ok()) {
        let p = e.path();
        let d = dst.join(e.file_name());
        if p.is_dir() {
            copy_tree(&p, &d)?;
        } else {
            fs::copy(&p, &d).map_err(|x| format!("salin {}: {}", p.display(), x))?;
        }
    }
    Ok(())
}

fn cp(f: &Path, dir: &Path) -> R<()> {
    let name = f.file_name().ok_or_else(|| format!("nama file kosong: {}", f.display()))?;
    fs::copy(f, dir.join(name)).map(|_| ()).map_err(|x| format!("salin {}: {}", f.display(), x))
}

fn tree(e: &Env, w: &Path, stubs: &[&str], src: &[&str], extra: &[&str]) -> R<()> {
    let pk = w.join("io/gpsz/zygote");
    fs::create_dir_all(&pk).map_err(|x| format!("{}: {}", pk.display(), x))?;
    for s in stubs {
        copy_tree(&e.t().join(s), w)?;
    }
    for f in src {
        cp(&e.jsrc().join(f), &pk)?;
    }
    for f in extra {
        cp(&e.t().join(f), &pk)?;
    }
    Ok(())
}

fn java_files(dir: &Path, out: &mut Vec<PathBuf>) {
    if let Ok(rdir) = fs::read_dir(dir) {
        for e in rdir.filter_map(|e| e.ok()) {
            let p = e.path();
            if p.is_dir() {
                java_files(&p, out);
            } else if p.extension().is_some_and(|x| x == "java") {
                out.push(p);
            }
        }
    }
}

fn javac_raw(e: &Env, args: Vec<String>, cwd: Option<&Path>, log: &mut String) -> R<()> {
    let mut c = Command::new(&e.jc[0]);
    c.args(&e.jc[1..]).args(&args);
    if let Some(d) = cwd {
        c.current_dir(d);
    }
    let o = c.output().map_err(|x| format!("javac: {}", x))?;
    log.push_str(&lossy(&o.stdout));
    log.push_str(&lossy(&o.stderr));
    if o.status.success() {
        Ok(())
    } else {
        Err("kompilasi gagal".to_string())
    }
}

fn compile(e: &Env, w: &Path, flags: &[&str], log: &mut String) -> R<()> {
    let mut files: Vec<PathBuf> = Vec::new();
    java_files(w, &mut files);
    files.sort();
    let mut args = sv(flags);
    args.push("-d".to_string());
    args.push("out".to_string());
    for f in &files {
        args.push(ps(f.strip_prefix(w).unwrap_or(f.as_path())));
    }
    javac_raw(e, args, Some(w), log)
}

fn jrun(args: &[String], envs: &[(String, String)], log: &mut String) -> bool {
    let mut c = Command::new("java");
    c.args(args);
    for (k, v) in envs {
        c.env(k, v);
    }
    match c.output() {
        Ok(o) => {
            log.push_str(&lossy(&o.stdout));
            log.push_str(&lossy(&o.stderr));
            o.status.success()
        }
        Err(x) => {
            log.push_str(&format!("java: {}\n", x));
            false
        }
    }
}

fn cls_args(w: &Path, class: &str, rest: &[&str]) -> Vec<String> {
    let mut a = vec!["-cp".to_string(), ps(&w.join("out")), format!("io.gpsz.zygote.{}", class)];
    a.extend(sv(rest));
    a
}

fn keys_line(salt: &str) -> String {
    let mut s = String::new();
    for k in ["cfg", "sys", "beat", "trk", "net", "bt"] {
        s.push_str(&tag(salt, k));
        s.push(',');
    }
    s
}

pub fn run_suite(name: &str, e: &Env) -> (bool, String) {
    let mut log = String::new();
    let r = match name {
        "beat" => beat(e, &mut log),
        "idmap" => idmap(e, &mut log),
        "lshook" => hook(
            e,
            &mut log,
            &["lshook/stubs"],
            &["LsHook.java", "PackageGuardLs.java"],
            "lshook/Check.java",
        ),
        "lochook" => hook(
            e,
            &mut log,
            &["beat/stubs", "lochook/stubs"],
            &[
                "Frames.java",
                "LsHook.java",
                "LocationHooks.java",
                "GnssFake.java",
                "Config.java",
                "ConfigNative.java",
                "LocationNative.java",
                "Fix.java",
                "WeakIdentityMap.java",
            ],
            "lochook/Check.java",
        ),
        "setguard" => hook(
            e,
            &mut log,
            &["beat/stubs", "setguard/stubs"],
            &[
                "Frames.java",
                "LsHook.java",
                "SettingsGuard.java",
                "Config.java",
                "ConfigNative.java",
                "LocationNative.java",
            ],
            "setguard/Check.java",
        ),
        "sysguard" => hook(e, &mut log, &[], &["SysGuard.java"], "sysguard/Check.java"),
        "syshook" => syshook(e, &mut log),
        "parity" => parity(e, &mut log),
        "rswire" => rswire(e, &mut log),
        "cfgwire" => cfgwire(e, &mut log),
        "gnssfake" => gnssfake(e, &mut log),
        "gnsswire" => gnsswire(e, &mut log),
        _ => Err(format!("suite tidak dikenal: {}", name)),
    };
    match r {
        Ok(()) => (true, log),
        Err(m) => {
            log.push_str(&m);
            log.push('\n');
            (false, log)
        }
    }
}

fn hook(e: &Env, log: &mut String, stubs: &[&str], src: &[&str], check: &str) -> R<()> {
    let td = tempdir("jhook")?;
    let w = td.0.clone();
    tree(e, &w, stubs, src, &[check])?;
    compile(e, &w, &[], log)?;
    if jrun(&cls_args(&w, "Check", &[]), &[], log) {
        Ok(())
    } else {
        Err("Check gagal".to_string())
    }
}

fn syshook(e: &Env, log: &mut String) -> R<()> {
    let td = tempdir("jsyshook")?;
    let w = td.0.clone();
    let pk = w.join("io/gpsz/zygote");
    fs::create_dir_all(&pk).map_err(|x| x.to_string())?;
    copy_tree(&e.t().join("beat/stubs"), &w)?;
    copy_tree(&e.t().join("lochook/stubs"), &w)?;
    for f in ["LsNative.java", "ZygoteEntry.java", "PackageGuardLs.java", "SettingsGuard.java"] {
        let _ = fs::remove_file(pk.join(f));
    }
    copy_tree(&e.t().join("syshook/stubs"), &w)?;
    for f in [
        "Frames.java",
        "LsHook.java",
        "SystemHooks.java",
        "GnssFake.java",
        "Config.java",
        "ConfigNative.java",
        "LocationNative.java",
        "Fix.java",
        "WeakIdentityMap.java",
    ] {
        cp(&e.jsrc().join(f), &pk)?;
    }
    cp(&e.t().join("syshook/Check.java"), &pk)?;
    compile(e, &w, &[], log)?;
    let mut all = true;
    for mode in ["ls", "off", "notready", "partial"] {
        log.push_str(&format!("<< {} >>\n", mode));
        if !jrun(&cls_args(&w, "Check", &[mode]), &[], log) {
            all = false;
        }
    }
    if all {
        Ok(())
    } else {
        Err("ada mode yang gagal".to_string())
    }
}

fn idmap(e: &Env, log: &mut String) -> R<()> {
    let td = tempdir("jidmap")?;
    let w = td.0.clone();
    tree(e, &w, &[], &["WeakIdentityMap.java"], &["idmap/IdentityMapTest.java"])?;
    compile(e, &w, &["-Xlint:all", "-Werror"], log)?;
    if jrun(&cls_args(&w, "IdentityMapTest", &[]), &[], log) {
        Ok(())
    } else {
        Err("IdentityMapTest gagal".to_string())
    }
}

fn beat(e: &Env, log: &mut String) -> R<()> {
    let td = tempdir("jbeat")?;
    let w = td.0.clone();
    tree(
        e,
        &w,
        &["beat/stubs"],
        &["Config.java", "ConfigNative.java", "LocationNative.java"],
        &["beat/BeatTest.java", "beat/TrackTest.java", "beat/ParseTest.java", "beat/FileFieldsTest.java"],
    )?;
    compile(e, &w, &[], log)?;
    let keys = keys_line(&e.salt);
    let envs = vec![
        ("WIRE_PLAIN".to_string(), "48213".to_string()),
        ("WIRE_HEX".to_string(), enc(&e.salt, "48213")),
        ("WIRE_PLAIN5".to_string(), "12345".to_string()),
        ("WIRE_HEX5".to_string(), enc(&e.salt, "12345")),
        ("BRIDGE_KEYS".to_string(), keys.clone()),
        ("GPSZ_KEYS".to_string(), keys),
    ];
    for cls in ["BeatTest", "TrackTest", "ParseTest", "FileFieldsTest"] {
        if !jrun(&cls_args(&w, cls, &[]), &envs, log) {
            return Err(format!("{} gagal", cls));
        }
    }
    Ok(())
}

fn parity(e: &Env, log: &mut String) -> R<()> {
    if which("cargo").is_none() {
        return Err("butuh cargo".to_string());
    }
    let td = tempdir("jparity")?;
    let w = td.0.clone();
    tree(
        e,
        &w,
        &["beat/stubs", "parity/stubs"],
        &["Config.java", "Fix.java", "ConfigNative.java", "LocationNative.java"],
        &["parity/ParityDump.java", "parity/JniCheck.java"],
    )?;
    compile(e, &w, &[], log)?;
    let o = Command::new("java")
        .args(cls_args(&w, "ParityDump", &[]))
        .output()
        .map_err(|x| format!("java: {}", x))?;
    log.push_str(&lossy(&o.stderr));
    if !o.status.success() {
        return Err("ParityDump gagal".to_string());
    }
    let jtxt = w.join("java.txt");
    fs::write(&jtxt, &o.stdout).map_err(|x| format!("{}: {}", jtxt.display(), x))?;
    let manifest = ps(&e.root.join("native/lochooks/Cargo.toml"));
    cargo(
        vec![
            "run".to_string(),
            "--release".to_string(),
            "-q".to_string(),
            "--manifest-path".to_string(),
            manifest.clone(),
            "--example".to_string(),
            "parity".to_string(),
            "--".to_string(),
            ps(&jtxt),
        ],
        log,
    )?;
    cargo(
        vec!["build".to_string(), "--release".to_string(), "-q".to_string(), "--manifest-path".to_string(), manifest],
        log,
    )?;
    let so = ps(&e.root.join("native/lochooks/target/release/liblochooks.so"));
    let envs = vec![("LOCHOOKS_SO".to_string(), so)];
    if jrun(&cls_args(&w, "JniCheck", &[]), &envs, log) {
        Ok(())
    } else {
        Err("JniCheck gagal".to_string())
    }
}

fn jclass(w: &Path, class: &str, salt: &str, flag: &str, arg: &str, echo: bool, log: &mut String) -> (bool, String) {
    let mut c = Command::new("java");
    c.arg(format!("-Dfake.salt={}", salt));
    if !flag.is_empty() {
        c.arg(flag);
    }
    c.arg("-cp").arg(w.join("out")).arg(format!("io.gpsz.zygote.{}", class));
    if !arg.is_empty() {
        c.arg(arg);
    }
    match c.output() {
        Ok(o) => {
            log.push_str(&lossy(&o.stderr));
            let out = lossy(&o.stdout);
            if echo {
                log.push_str(&out);
            }
            (o.status.success(), out)
        }
        Err(x) => {
            log.push_str(&format!("java: {}\n", x));
            (false, String::new())
        }
    }
}

fn first_diff(a: &str, b: &str, log: &mut String) {
    let mut n = 0;
    let mut ia = a.lines();
    let mut ib = b.lines();
    loop {
        let x = ia.next();
        let y = ib.next();
        if x.is_none() && y.is_none() {
            break;
        }
        if x != y {
            log.push_str(&format!("    < {}\n    > {}\n", x.unwrap_or(""), y.unwrap_or("")));
            n += 1;
            if n >= 8 {
                break;
            }
        }
    }
}

fn float_compare(a: &str, b: &str, log: &mut String) -> bool {
    let la: Vec<&str> = a.lines().filter(|x| !x.is_empty()).collect();
    let lb: Vec<&str> = b.lines().filter(|x| !x.is_empty()).collect();
    if la.len() != lb.len() || la.is_empty() {
        log.push_str(&format!("  FAIL jumlah baris {} {}\n", la.len(), lb.len()));
        return false;
    }
    let mut bad = 0;
    let mut worst = 0.0f64;
    for (x, y) in la.iter().zip(lb.iter()) {
        let fx: Vec<&str> = x.split('|').collect();
        let fy: Vec<&str> = y.split('|').collect();
        if fx.len() != fy.len() {
            bad += 1;
            log.push_str(&format!("  FAIL kolom {} {}\n", x, y));
            continue;
        }
        for (u, v) in fx.iter().zip(fy.iter()) {
            if u == v {
                continue;
            }
            match (u.parse::<f64>(), v.parse::<f64>()) {
                (Ok(pu), Ok(pv)) => {
                    let d = (pu - pv).abs();
                    if d > worst {
                        worst = d;
                    }
                    if d > 2e-5 * pu.abs().max(1.0) {
                        bad += 1;
                        log.push_str(&format!("  FAIL {} | {}\n", x, y));
                        break;
                    }
                }
                _ => {
                    bad += 1;
                    log.push_str(&format!("  FAIL {} | {}\n", x, y));
                    break;
                }
            }
        }
    }
    log.push_str(&format!(
        "  {} {} baris, selisih terbesar {:.3e}\n",
        if bad == 0 { "ok  " } else { "FAIL" },
        la.len(),
        worst
    ));
    bad == 0
}

fn pick(text: &str) -> String {
    text.lines()
        .filter(|l| l.starts_with("fix|") || l.starts_with("snap|"))
        .collect::<Vec<&str>>()
        .join("\n")
}

fn rswire(e: &Env, log: &mut String) -> R<()> {
    let td = tempdir("jrswire")?;
    let t = td.0.clone();
    let salt = {
        let cfg = rd(&e.jsrc().join("Config.java"));
        let mut found = String::new();
        for l in cfg.lines() {
            if let Some(i) = l.find("SALT = \"") {
                let rest = &l[i + 8..];
                if let Some(n) = rest.find('"') {
                    found = rest[..n].to_string();
                }
            }
        }
        found
    };
    if salt.is_empty() {
        return Err("SALT tidak ditemukan di Config.java".to_string());
    }
    let mut trees: Vec<PathBuf> = Vec::new();
    for (name, native_dir) in [("real", e.jsrc()), ("fake", e.t().join("rswire/fake"))] {
        let w = t.join(name);
        fs::create_dir_all(&w).map_err(|x| x.to_string())?;
        let pk = w.join("io/gpsz/zygote");
        fs::create_dir_all(&pk).map_err(|x| x.to_string())?;
        copy_tree(&e.t().join("beat/stubs"), &w)?;
        copy_tree(&e.t().join("parity/stubs"), &w)?;
        cp(&e.jsrc().join("Config.java"), &pk)?;
        cp(&e.jsrc().join("Fix.java"), &pk)?;
        cp(&e.t().join("parity/ParityDump.java"), &pk)?;
        cp(&e.t().join("rswire/WireDump.java"), &pk)?;
        cp(&e.t().join("rswire/WireCheck.java"), &pk)?;
        cp(&native_dir.join("ConfigNative.java"), &pk)?;
        cp(&native_dir.join("LocationNative.java"), &pk)?;
        compile(e, &w, &[], log)?;
        trees.push(w);
    }
    let real = trees[0].clone();
    let fake = trees[1].clone();
    let mut fail = false;

    log.push_str("== codec: jalur Java vs native palsu harus identik (termasuk hex ganjil/rusak, non-ASCII)\n");
    let (ok1, c_real) = jclass(&real, "WireDump", &salt, "", "", false, log);
    let (ok2, c_fake) = jclass(&fake, "WireDump", &salt, "", "", false, log);
    if !ok1 || !ok2 {
        return Err("WireDump gagal dijalankan".to_string());
    }
    if c_real == c_fake {
        log.push_str(&format!("  ok   {} baris sama\n", c_real.lines().count()));
    } else {
        log.push_str("  FAIL codec beda\n");
        first_diff(&c_real, &c_fake, log);
        fail = true;
    }
    let (ok3, c_bad) = jclass(&fake, "WireDump", &salt, "-Dfake.badseed=1", "", false, log);
    if !ok3 {
        return Err("WireDump badseed gagal dijalankan".to_string());
    }
    if c_real == c_bad {
        log.push_str("  ok   salt tidak cocok: codec jatuh ke Java, hasil sama\n");
    } else {
        log.push_str("  FAIL salt tidak cocok tapi hasil codec beda\n");
        first_diff(&c_real, &c_bad, log);
        fail = true;
    }

    log.push_str("== Fix.apply dan snapshot: jalur Java vs native palsu (toleransi float)\n");
    let (ok4, p_real) = jclass(&real, "ParityDump", &salt, "", "", false, log);
    let (ok5, p_fake) = jclass(&fake, "ParityDump", &salt, "", "", false, log);
    if !ok4 || !ok5 {
        return Err("ParityDump gagal dijalankan".to_string());
    }
    if !float_compare(&pick(&p_real), &pick(&p_fake), log) {
        fail = true;
    }

    log.push_str("== mode java (kelas native asli, library tidak termuat)\n");
    if !jclass(&real, "WireCheck", &salt, "", "java", true, log).0 {
        fail = true;
    }
    log.push_str("== mode native (kelas palsu, salt cocok)\n");
    if !jclass(&fake, "WireCheck", &salt, "", "native", true, log).0 {
        fail = true;
    }
    log.push_str("== mode badseed (salt/seed tidak cocok: harus jatuh ke Java)\n");
    if !jclass(&fake, "WireCheck", &salt, "-Dfake.badseed=1", "badseed", true, log).0 {
        fail = true;
    }
    if fail {
        Err("ADA YANG GAGAL".to_string())
    } else {
        Ok(())
    }
}

fn norm_baca(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = String::new();
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'(' {
            let mut j = i + 1;
            while j < b.len() && b[j].is_ascii_digit() {
                j += 1;
            }
            if j > i + 1 && s[j..].starts_with(" baca)") {
                out.push_str("(N baca)");
                i = j + 6;
                continue;
            }
        }
        let ch = s[i..].chars().next().unwrap_or(' ');
        out.push(ch);
        i += ch.len_utf8().max(1);
    }
    out
}

fn jrun_file(args: &[String], file: &Path) -> R<Option<i32>> {
    let f = fs::File::create(file).map_err(|x| format!("{}: {}", file.display(), x))?;
    let f2 = f.try_clone().map_err(|x| x.to_string())?;
    let st = Command::new("java")
        .args(args)
        .stdout(f)
        .stderr(f2)
        .status()
        .map_err(|x| format!("java: {}", x))?;
    Ok(st.code())
}

const GNSS_SRC: &[&str] = &[
    "Frames.java",
    "LsHook.java",
    "Config.java",
    "ConfigNative.java",
    "Fix.java",
    "GnssFake.java",
];

fn gnssfake(e: &Env, log: &mut String) -> R<()> {
    let td = tempdir("jgnssfake")?;
    let w = td.0.clone();
    tree(
        e,
        &w,
        &["beat/stubs", "lochook/stubs"],
        GNSS_SRC,
        &["gnsswire/fake/LocationNative.java", "gnsswire/GnssWire.java"],
    )?;
    compile(e, &w, &[], log)?;
    log.push_str("== native palsu sesuai kontrak: dipakai bila verifikasi lolos, cadangan Java bila null, melempar, atau array salah\n");
    let (ok1, _) = jclass(&w, "GnssWire", &e.salt, "", "fake:on", true, log);
    log.push_str("== native palsu yang salah satu keluarannya beda: verifikasi gagal, native tidak dipakai\n");
    let (ok2, _) = jclass(&w, "GnssWire", &e.salt, "-Dfake.bad=1", "fake:off", true, log);
    if ok1 && ok2 {
        Ok(())
    } else {
        Err("GnssWire (native palsu) gagal".to_string())
    }
}

fn gnsswire(e: &Env, log: &mut String) -> R<()> {
    let so = match env_set("LOCHOOKS_SO") {
        Some(s) => PathBuf::from(s),
        None => {
            if which("cargo").is_none() {
                return Err("butuh cargo atau LOCHOOKS_SO".to_string());
            }
            let manifest = ps(&e.root.join("native/lochooks/Cargo.toml"));
            cargo(
                vec!["build".to_string(), "--release".to_string(), "-q".to_string(), "--manifest-path".to_string(), manifest],
                log,
            )
            .map_err(|m| format!("build lochooks gagal ({})", m))?;
            e.root.join("native/lochooks/target/release/liblochooks.so")
        }
    };
    if !so.is_file() {
        return Err(format!("tidak ada {}", so.display()));
    }
    let libdir = so
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| PathBuf::from("."))
        .canonicalize()
        .map_err(|x| x.to_string())?;
    let td = tempdir("jgnsswire")?;
    let w = td.0.clone();
    tree(
        e,
        &w,
        &["beat/stubs", "lochook/stubs"],
        &["Frames.java", "LsHook.java", "Config.java", "ConfigNative.java", "LocationNative.java", "Fix.java", "GnssFake.java"],
        &["gnsswire/GnssWire.java"],
    )?;
    compile(e, &w, &[], log)?;
    log.push_str("== liblochooks.so asli: verifikasi GnssFake lolos, snapshot/NMEA/measurements sama dengan Java\n");
    let args = vec![
        format!("-Djava.library.path={}", ps(&libdir)),
        "-cp".to_string(),
        ps(&w.join("out")),
        "io.gpsz.zygote.GnssWire".to_string(),
        "real:on".to_string(),
    ];
    if jrun(&args, &[], log) {
        Ok(())
    } else {
        Err("GnssWire (library asli) gagal".to_string())
    }
}

fn cfgwire(e: &Env, log: &mut String) -> R<()> {
    let so = match env_set("CONFIGCORE_SO") {
        Some(s) => PathBuf::from(s),
        None => {
            if which("cargo").is_none() {
                return Err("butuh cargo atau CONFIGCORE_SO".to_string());
            }
            let manifest = ps(&e.root.join("native/configcore/Cargo.toml"));
            cargo(
                vec!["build".to_string(), "--release".to_string(), "-q".to_string(), "--manifest-path".to_string(), manifest],
                log,
            )
            .map_err(|m| format!("build configcore gagal ({})", m))?;
            e.root.join("native/configcore/target/release/libconfigcore.so")
        }
    };
    if !so.is_file() {
        return Err(format!("tidak ada {}", so.display()));
    }
    let libdir = so
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| PathBuf::from("."))
        .canonicalize()
        .map_err(|x| x.to_string())?;

    let td = tempdir("jcfgwire")?;
    let t = td.0.clone();
    let w = t.join("w");
    tree(
        e,
        &w,
        &["beat/stubs"],
        &["Config.java", "ConfigNative.java", "LocationNative.java"],
        &[
            "beat/BeatTest.java",
            "beat/TrackTest.java",
            "beat/ParseTest.java",
            "beat/FileFieldsTest.java",
            "cfgwire/CfgDump.java",
            "cfgwire/JniRun.java",
        ],
    )?;
    compile(e, &w, &[], log)?;
    let out = ps(&w.join("out"));

    log.push_str("== library termuat dan verifikasi Config lolos (JniRun memeriksa Config.cfgNative())\n");
    log.push_str("== keluaran jalur Java dan jalur Rust harus identik sampai bit terakhir\n");
    let mut fail = false;
    let mut dump_java = String::new();
    let mut dump_native = String::new();
    for cls in ["CfgDump", "ParseTest", "TrackTest", "BeatTest", "FileFieldsTest"] {
        let jf = t.join(format!("{}.java.txt", cls));
        let nf = t.join(format!("{}.native.txt", cls));
        let ja = jrun_file(
            &[
                "-Xss16m".to_string(),
                "-cp".to_string(),
                out.clone(),
                "io.gpsz.zygote.JniRun".to_string(),
                "java".to_string(),
                cls.to_string(),
            ],
            &jf,
        )?;
        let na = jrun_file(
            &[
                "-Xss16m".to_string(),
                format!("-Djava.library.path={}", ps(&libdir)),
                "-cp".to_string(),
                out.clone(),
                "io.gpsz.zygote.JniRun".to_string(),
                "native".to_string(),
                cls.to_string(),
            ],
            &nf,
        )?;
        let a = norm_baca(&rd(&jf));
        let b = norm_baca(&rd(&nf));
        let n = a.lines().count();
        let f = a.lines().filter(|l| l.starts_with("FAIL")).count();
        if ja == na && a == b && n > 0 {
            log.push_str(&format!("  ok   {}: {} baris identik (Java vs Rust), {} baris FAIL di kedua jalur\n", cls, n, f));
        } else {
            log.push_str(&format!("  FAIL {}: Java (exit {:?}) dan Rust (exit {:?}) beda\n", cls, ja, na));
            first_diff(&a, &b, log);
            fail = true;
        }
        if cls == "CfgDump" {
            dump_java = a;
            dump_native = b;
        }
    }
    let gj = dump_java.lines().filter(|l| l.starts_with("FAIL")).count();
    if gj != 0 {
        log.push_str("  FAIL CfgDump mencetak FAIL\n");
        fail = true;
    }
    let nl = dump_native
        .lines()
        .filter(|l| {
            let b = l.as_bytes();
            b.len() >= 2 && b[0] == b'p' && (b[1].is_ascii_digit() || b[1] == b'f')
        })
        .count();
    if nl < 6000 {
        log.push_str(&format!("  FAIL CfgDump parse terlalu sedikit ({})\n", nl));
        fail = true;
    }
    let nn = dump_native.lines().filter(|l| l.ends_with("null")).count();
    let nv = dump_native.lines().filter(|l| l.starts_with("tp")).count();
    log.push_str(&format!("  info  parseTrack: {} kasus, {} menghasilkan null; sisanya track valid\n", nv, nn));
    if fail {
        Err("ADA YANG GAGAL".to_string())
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn baca_counts_are_normalised() {
        assert_eq!(norm_baca("x (123 baca) y (4 baca)"), "x (N baca) y (N baca)");
        assert_eq!(norm_baca("(abc baca)"), "(abc baca)");
        assert_eq!(norm_baca("é(7 baca)"), "é(N baca)");
    }

    #[test]
    fn float_tolerance() {
        let mut log = String::new();
        assert!(float_compare("fix|1.0|a\n", "fix|1.000001|a\n", &mut log));
        assert!(!float_compare("fix|1.0|a\n", "fix|1.1|a\n", &mut log));
        assert!(!float_compare("fix|1.0|a\n", "fix|1.0|b\n", &mut log));
    }

    #[test]
    fn keys_line_has_six_entries() {
        let k = keys_line("Vzvwk4Rxj7Ps");
        assert_eq!(k.matches(',').count(), 6);
        assert!(k.starts_with("956349,db078e,c1e67d,"));
    }
}
