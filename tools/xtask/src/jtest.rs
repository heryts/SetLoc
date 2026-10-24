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

fn lib_dir(e: &Env, krate: &str, var: &str, log: &mut String) -> R<String> {
    if let Some(s) = env_set(var) {
        let so = PathBuf::from(s);
        if !so.is_file() {
            return Err(format!("tidak ada {}", so.display()));
        }
        let dir = so.parent().map(|p| p.to_path_buf()).unwrap_or_else(|| PathBuf::from("."));
        return dir.canonicalize().map(|d| ps(&d)).map_err(|x| x.to_string());
    }
    if which("cargo").is_none() {
        return Err(format!("butuh cargo atau {}", var));
    }
    let manifest = ps(&e.root.join("native").join(krate).join("Cargo.toml"));
    cargo(
        vec!["build".to_string(), "--release".to_string(), "-q".to_string(), "--manifest-path".to_string(), manifest],
        log,
    )
    .map_err(|m| format!("build {} gagal ({})", krate, m))?;
    let dir = e.root.join("native").join(krate).join("target/release");
    dir.canonicalize().map(|d| ps(&d)).map_err(|x| format!("{}: {}", dir.display(), x))
}

fn host_libs(e: &Env, log: &mut String) -> R<String> {
    let a = lib_dir(e, "configcore", "CONFIGCORE_SO", log)?;
    let b = lib_dir(e, "lochooks", "LOCHOOKS_SO", log)?;
    Ok(format!("-Djava.library.path={}:{}", a, b))
}

fn cls_args(w: &Path, class: &str, rest: &[&str]) -> Vec<String> {
    let mut a = vec!["-cp".to_string(), ps(&w.join("out")), format!("io.gpsz.zygote.{}", class)];
    a.extend(sv(rest));
    a
}

fn ncls_args(lib: &str, w: &Path, class: &str, rest: &[&str]) -> Vec<String> {
    let mut a = vec![lib.to_string()];
    a.extend(cls_args(w, class, rest));
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
            false,
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
            true,
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
            true,
        ),
        "syshook" => syshook(e, &mut log),
        "unsafe" => unsafe_suite(e, &mut log),
        "parity" => parity(e, &mut log),
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

fn hook(e: &Env, log: &mut String, stubs: &[&str], src: &[&str], check: &str, native: bool) -> R<()> {
    let td = tempdir("jhook")?;
    let w = td.0.clone();
    tree(e, &w, stubs, src, &[check])?;
    compile(e, &w, &[], log)?;
    let args = if native {
        let lib = host_libs(e, log)?;
        ncls_args(&lib, &w, "Check", &[])
    } else {
        cls_args(&w, "Check", &[])
    };
    if jrun(&args, &[], log) {
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
    for f in ["LegacyFrame.java", "LsNative.java", "ZygoteEntry.java", "PackageGuardLs.java", "SettingsGuard.java"] {
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
    let lib = host_libs(e, log)?;
    let mut all = true;
    for mode in ["ok", "broken", "partial"] {
        log.push_str(&format!("<< {} >>\n", mode));
        if !jrun(&ncls_args(&lib, &w, "Check", &[mode]), &[], log) {
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
    let lib = host_libs(e, log)?;
    for cls in ["BeatTest", "TrackTest", "ParseTest", "FileFieldsTest"] {
        if !jrun(&ncls_args(&lib, &w, cls, &[]), &envs, log) {
            return Err(format!("{} gagal", cls));
        }
    }
    Ok(())
}

fn parity(e: &Env, log: &mut String) -> R<()> {
    let td = tempdir("jparity")?;
    let w = td.0.clone();
    tree(
        e,
        &w,
        &["beat/stubs", "parity/stubs"],
        &["Config.java", "Fix.java", "ConfigNative.java", "LocationNative.java"],
        &["parity/JniCheck.java"],
    )?;
    compile(e, &w, &[], log)?;
    let lib = host_libs(e, log)?;
    if jrun(&ncls_args(&lib, &w, "JniCheck", &[]), &[], log) {
        Ok(())
    } else {
        Err("JniCheck gagal".to_string())
    }
}

fn gnsswire(e: &Env, log: &mut String) -> R<()> {
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
    let lib = host_libs(e, log)?;
    log.push_str("== liblochooks.so asli: verifikasi GnssFake lolos, snapshot/NMEA/measurements sesuai kontrak\n");
    if jrun(&ncls_args(&lib, &w, "GnssWire", &[]), &[], log) {
        Ok(())
    } else {
        Err("GnssWire gagal".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_line_has_six_entries() {
        let k = keys_line("Vzvwk4Rxj7Ps");
        assert_eq!(k.matches(',').count(), 6);
        assert!(k.starts_with("956349,db078e,c1e67d,"));
    }
}

fn unsafe_suite(e: &Env, log: &mut String) -> R<()> {
    let td = tempdir("junsafe")?;
    let w = td.0.clone();
    for d in ["stub", "out", "test"] {
        fs::create_dir_all(w.join(d)).map_err(|x| x.to_string())?;
    }
    let m = e.root.join("module");
    let lint = ["-Xlint:all", "-Werror", "--limit-modules", "java.base"];

    let mut files: Vec<PathBuf> = Vec::new();
    java_files(&m.join("stub/src"), &mut files);
    files.sort();
    let mut a = sv(&lint);
    a.push("-d".to_string());
    a.push(ps(&w.join("stub")));
    for f in &files {
        a.push(ps(f));
    }
    javac_raw(e, a, None, log)?;

    let mut files: Vec<PathBuf> = Vec::new();
    java_files(&m.join("unsafe/src"), &mut files);
    files.sort();
    let mut a = sv(&lint);
    a.push("-cp".to_string());
    a.push(ps(&w.join("stub")));
    a.push("-d".to_string());
    a.push(ps(&w.join("out")));
    for f in &files {
        a.push(ps(f));
    }
    javac_raw(e, a, None, log)?;

    let mut a = sv(&lint);
    a.push("-cp".to_string());
    a.push(ps(&w.join("out")));
    a.push("-d".to_string());
    a.push(ps(&w.join("test")));
    a.push(ps(&e.t().join("unsafe/UnsafeTest.java")));
    javac_raw(e, a, None, log)?;

    let cp_arg = format!("{}:{}", ps(&w.join("out")), ps(&w.join("test")));
    let args = vec![
        "-cp".to_string(),
        cp_arg,
        "com.v7878.sun.unsafe.UnsafeTest".to_string(),
        ps(&e.t().join("unsafe/api.txt")),
    ];
    if jrun(&args, &[], log) {
        Ok(())
    } else {
        Err("UnsafeTest gagal".to_string())
    }
}
