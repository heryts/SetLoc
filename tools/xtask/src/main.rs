
use std::env;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{exit, Command, Stdio};
use std::thread;

type R<T> = Result<T, String>;

const PIN: &str = "6fa2d5cbbf51c96fb81a9b5d96fdd74beac4aafe";
const VMTOOLS_URL: &str = "https://github.com/aerath-stuff/AndroidVMTools";
const VMTOOLS_DIR: &str = "module/external/AndroidVMTools";
const MODULE_ZIP: &str = "module/zygote/build/outputs/magisk/release/SetLocZygisk-release.zip";

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    let res = match args.first().map(String::as_str) {
        Some("build") => enter_root().and_then(|_| build()),
        Some("setup") => enter_root().and_then(|_| setup_vmtools().map(|m| println!("{}", m))),
        Some("update") => match args.get(1) {
            Some(z) => update(z),
            None => Err("pakai: cargo xtask update <zip-source-baru>".into()),
        },
        _ => Err("pakai: cargo xtask build | setup | update <zip>".into()),
    };
    if let Err(e) = res {
        eprintln!("GAGAL: {}", e);
        exit(1);
    }
}

fn enter_root() -> R<()> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let root = root.canonicalize().map_err(|e| format!("root repo: {}", e))?;
    env::set_current_dir(&root).map_err(|e| format!("cd {}: {}", root.display(), e))
}

fn env_set(name: &str) -> Option<String> {
    env::var(name).ok().filter(|v| !v.is_empty())
}

fn which(name: &str) -> Option<PathBuf> {
    let path = env::var_os("PATH")?;
    env::split_paths(&path).map(|d| d.join(name)).find(|p| p.is_file())
}

fn list_dirs(base: &str) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = match fs::read_dir(base) {
        Ok(rd) => rd.filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.is_dir()).collect(),
        Err(_) => Vec::new(),
    };
    v.sort();
    v
}

fn status(cmd: &mut Command, what: &str) -> R<()> {
    match cmd.status() {
        Ok(s) if s.success() => Ok(()),
        Ok(s) => Err(format!("{} gagal ({})", what, s)),
        Err(e) => Err(format!("{} tidak bisa dijalankan: {}", what, e)),
    }
}

fn out_of(cmd: &mut Command) -> Option<String> {
    let o = cmd.stdin(Stdio::null()).stderr(Stdio::null()).output().ok()?;
    if !o.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&o.stdout).trim().to_string())
}

fn words(name: &str) -> Vec<String> {
    env::var(name).unwrap_or_default().split_whitespace().map(String::from).collect()
}

fn java_major(java: &Path) -> Option<u32> {
    let o = Command::new(java).arg("-version").stdin(Stdio::null()).output().ok()?;
    let text = format!("{}{}", String::from_utf8_lossy(&o.stderr), String::from_utf8_lossy(&o.stdout));
    for line in text.lines() {
        if let Some(i) = line.find(" version \"") {
            let digits: String = line[i + 10..].chars().take_while(|c| c.is_ascii_digit()).collect();
            return digits.parse().ok();
        }
    }
    None
}

fn find_jdk(want: u32, over: Option<String>) -> R<PathBuf> {
    let ok = |d: &Path| d.join("bin/javac").exists() && java_major(&d.join("bin/java")) == Some(want);
    if let Some(o) = over {
        let p = PathBuf::from(&o);
        return if ok(&p) { Ok(p) } else { Err(format!("JAVA{}_HOME={} bukan JDK {}", want, o, want)) };
    }
    let mut c: Vec<PathBuf> = Vec::new();
    if let Some(j) = env_set("JAVA_HOME") {
        c.push(PathBuf::from(j));
    }
    if let Some(r) = which("java").and_then(|p| p.canonicalize().ok()) {
        if let Some(d) = r.parent().and_then(|b| b.parent()) {
            c.push(d.to_path_buf());
        }
    }
    c.extend(list_dirs("/usr/lib/jvm"));
    if let Some(h) = env_set("HOME") {
        c.extend(list_dirs(&format!("{}/.sdkman/candidates/java", h)));
    }
    for d in list_dirs("/Library/Java/JavaVirtualMachines") {
        c.push(d.join("Contents/Home"));
    }
    c.into_iter().find(|d| ok(d)).ok_or_else(|| {
        format!("JDK {} tidak ditemukan. Pasang (mis. sudo apt install openjdk-{}-jdk) atau set JAVA{}_HOME.", want, want, want)
    })
}

fn with_jdk(jh: &Path, cmd: &mut Command) {
    let old = env::var("PATH").unwrap_or_default();
    cmd.env("JAVA_HOME", jh).env("PATH", format!("{}/bin:{}", jh.display(), old));
}

fn version_key(p: &Path) -> Vec<u32> {
    let n = p.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    n.split(|c: char| !c.is_ascii_digit()).filter(|s| !s.is_empty()).filter_map(|s| s.parse().ok()).collect()
}

fn find_ndk() -> R<PathBuf> {
    for k in ["ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"] {
        if let Some(d) = env_set(k) {
            let p = PathBuf::from(d);
            if p.join("toolchains/llvm").is_dir() {
                return Ok(p);
            }
        }
    }
    let sdk = env_set("ANDROID_HOME").or_else(|| env_set("ANDROID_SDK_ROOT")).or_else(|| {
        fs::read_to_string("app/local.properties").ok().and_then(|t| {
            t.lines().find_map(|l| l.strip_prefix("sdk.dir=").map(|v| v.trim().to_string()))
        })
    });
    if let Some(sdk) = sdk {
        let mut v = list_dirs(&format!("{}/ndk", sdk));
        v.sort_by_key(|p| version_key(p));
        if let Some(d) = v.pop() {
            if d.join("toolchains/llvm").is_dir() {
                return Ok(d);
            }
        }
    }
    Err("NDK Android tidak ditemukan. Pasang lewat SDK Manager atau set ANDROID_NDK_HOME.".into())
}

fn git_rev(d: &Path) -> Option<String> {
    out_of(Command::new("git").arg("-C").arg(d).args(["rev-parse", "HEAD"]))
}

fn vmtools_complete(d: &Path) -> bool {
    git_rev(d).as_deref() == Some(PIN) && d.join("gradle/libs.versions.toml").is_file() && d.join("src/main/java").is_dir()
}

fn setup_vmtools() -> R<String> {
    let d = Path::new(VMTOOLS_DIR);
    if vmtools_complete(d) {
        return Ok(format!("ok (AndroidVMTools @ {}, sudah ada)", PIN));
    }
    let q = |args: &[&str]| -> R<()> {
        status(Command::new("git").arg("-C").arg(d).args(args).stdout(Stdio::null()), &format!("git {}", args.join(" ")))
    };
    if git_rev(d).as_deref() == Some(PIN) && q(&["reset", "-q", "--hard", PIN]).is_ok() && vmtools_complete(d) {
        return Ok(format!("ok (AndroidVMTools @ {}, dipulihkan)", PIN));
    }
    if !d.join(".git").exists() {
        fs::create_dir_all(d).map_err(|e| format!("mkdir {}: {}", d.display(), e))?;
        q(&["init", "-q"])?;
        q(&["remote", "add", "origin", VMTOOLS_URL])?;
    }
    q(&["fetch", "--depth", "1", "origin", PIN])?;
    q(&["checkout", "-q", "-f", "FETCH_HEAD"])?;
    if !vmtools_complete(d) {
        return Err(format!("AndroidVMTools tidak lengkap di {}", d.display()));
    }
    Ok(format!("ok (AndroidVMTools @ {})", PIN))
}

fn target_info(t: &str) -> R<(&'static str, &'static str)> {
    match t {
        "aarch64-linux-android" => Ok(("arm64-v8a", "aarch64-linux-android33-clang")),
        "armv7-linux-androideabi" => Ok(("armeabi-v7a", "armv7a-linux-androideabi33-clang")),
        "x86_64-linux-android" => Ok(("x86_64", "x86_64-linux-android33-clang")),
        "i686-linux-android" => Ok(("x86", "i686-linux-android33-clang")),
        _ => Err(format!("target Rust tidak dikenal: {}", t)),
    }
}

fn build_daemon(skip_tests: bool) -> R<()> {
    if which("cargo").is_none() {
        return Err("butuh cargo (https://rustup.rs)".into());
    }
    let ndk = find_ndk()?;
    let host = if cfg!(target_os = "macos") { "darwin-x86_64" } else { "linux-x86_64" };
    let bin = ndk.join("toolchains/llvm/prebuilt").join(host).join("bin");
    if !skip_tests {
        status(Command::new("cargo").args(["test", "--release"]).current_dir("daemon"), "cargo test daemon")?;
    }
    let mut targets = words("RUST_TARGETS");
    if targets.is_empty() {
        targets.push("aarch64-linux-android".into());
    }
    for t in targets {
        let (abi, cc) = target_info(&t)?;
        if which("rustup").is_some() {
            let have = out_of(Command::new("rustup").args(["target", "list", "--installed"])).unwrap_or_default();
            if !have.lines().any(|l| l.trim() == t) {
                status(Command::new("rustup").args(["target", "add", t.as_str()]), "rustup target add")?;
            }
        }
        let up = t.to_uppercase().replace('-', "_");
        status(
            Command::new("cargo")
                .args(["build", "--release", "--target", t.as_str()])
                .env(format!("CARGO_TARGET_{}_LINKER", up), bin.join(cc))
                .current_dir("daemon"),
            &format!("cargo build {}", t),
        )?;
        let dest = PathBuf::from(format!("module/zygote/src/main/assets/bin/{}", abi));
        fs::create_dir_all(&dest).map_err(|e| e.to_string())?;
        fs::copy(format!("daemon/target/{}/release/setlocd", t), dest.join("setlocd")).map_err(|e| format!("salin setlocd: {}", e))?;
    }
    Ok(())
}

fn newest_apk() -> Option<PathBuf> {
    let rd = fs::read_dir("app/app/build/outputs/apk/release").ok()?;
    rd.filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().map_or(false, |x| x == "apk"))
        .max_by_key(|p| fs::metadata(p).and_then(|m| m.modified()).ok())
}

fn gradle(dir: &str, jdk: &Path, tasks: &[&str]) -> R<()> {
    let mut c = Command::new("sh");
    c.arg("./gradlew").args(words("GRADLE_ARGS")).args(tasks).current_dir(dir);
    with_jdk(jdk, &mut c);
    status(&mut c, &format!("gradle {}", tasks.join(" ")))
}

fn build() -> R<()> {
    let jdk = find_jdk(21, env_set("JAVA21_HOME"))?;
    let skip_tests = env_set("SKIP_TESTS").is_some();

    if which("cargo").is_none() {
        return Err("butuh cargo (https://rustup.rs)".into());
    }
    if Path::new("tools/rotate-salt/Cargo.toml").is_file() {
        let mut c = Command::new("cargo");
        c.args(["run", "--release", "-q", "--manifest-path", "tools/rotate-salt/Cargo.toml", "--"]);
        if env_set("ROTATE_SALT").is_some() {
            c.arg("--new");
        }
        status(&mut c, "rotate-salt")?;
    } else {
        eprintln!("PERINGATAN: tools/rotate-salt tidak ada, salt tidak diputar");
    }

    if let Some(key) = env_set("MAPS_API_KEY") {
        let old = fs::read_to_string("app/local.properties").unwrap_or_default();
        let line = format!("MAPS_API_KEY={}", key);
        let mut keep: Vec<&str> = old.lines().filter(|l| !l.starts_with("MAPS_API_KEY=")).collect();
        keep.push(&line);
        fs::write("app/local.properties", keep.join("\n") + "\n").map_err(|e| format!("local.properties: {}", e))?;
    }

    let setup = thread::spawn(setup_vmtools);

    println!("== 1/4 build app ==");
    let tasks: &[&str] = if skip_tests { &["assembleRelease"] } else { &["testDebugUnitTest", "assembleRelease"] };
    gradle("app", &jdk, tasks)?;
    let apk = newest_apk().ok_or("APK rilis tidak ditemukan")?;
    fs::create_dir_all("module/zygote/src/main/assets/apk").map_err(|e| e.to_string())?;
    fs::copy(&apk, "module/zygote/src/main/assets/apk/SetLoc.apk").map_err(|e| format!("salin APK: {}", e))?;

    println!("== 2/4 build daemon Rust ==");
    build_daemon(skip_tests).map_err(|e| format!("daemon Rust: {}", e))?;

    println!("== 3/4 siapkan AndroidVMTools ==");
    let msg = setup.join().map_err(|_| "thread setup panik".to_string())??;
    println!("{}", msg);

    println!("== 4/4 build modul Zygisk ==");
    gradle("module", &jdk, &["--no-configuration-cache", ":zygote:assembleRelease"])?;
    if !Path::new(MODULE_ZIP).is_file() {
        return Err(format!("{} tidak ada", MODULE_ZIP));
    }
    println!("{}", MODULE_ZIP);
    Ok(())
}

struct TempDir(PathBuf);
impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn tempdir(tag: &str) -> R<TempDir> {
    let p = env::temp_dir().join(format!("setloc-{}-{}", tag, std::process::id()));
    let _ = fs::remove_dir_all(&p);
    fs::create_dir_all(&p).map_err(|e| e.to_string())?;
    Ok(TempDir(p))
}

fn find_source_root(t: &Path) -> Option<PathBuf> {
    for d1 in list_dirs(&t.to_string_lossy()) {
        if d1.file_name().map_or(false, |n| n == "setloc-zygisk-all") {
            return Some(d1);
        }
        for d2 in list_dirs(&d1.to_string_lossy()) {
            if d2.file_name().map_or(false, |n| n == "setloc-zygisk-all") {
                return Some(d2);
            }
        }
    }
    None
}

fn rsync(excl: &[&str], src: &Path, dst: &str) -> R<()> {
    fs::create_dir_all(dst).map_err(|e| e.to_string())?;
    let mut c = Command::new("rsync");
    c.args(["-a", "--delete"]).args(excl).arg(format!("{}/", src.display())).arg(format!("{}/", dst));
    status(&mut c, &format!("rsync {}", dst))
}

fn fix_crlf(dir: &Path) {
    let rd = match fs::read_dir(dir) {
        Ok(r) => r,
        Err(_) => return,
    };
    for e in rd.filter_map(|e| e.ok()) {
        let p = e.path();
        let name = e.file_name().to_string_lossy().to_string();
        if p.is_dir() {
            if name != "external" && name != "build" && name != ".gradle" && name != "target" && name != ".git" {
                fix_crlf(&p);
            }
        } else if name.ends_with(".sh") || name == "gradlew" {
            if let Ok(b) = fs::read(&p) {
                if b.contains(&b'\r') {
                    let fixed: Vec<u8> = b.into_iter().filter(|&c| c != b'\r').collect();
                    let _ = fs::write(&p, fixed);
                }
            }
        }
    }
}

fn update(zip: &str) -> R<()> {
    for c in ["rsync", "unzip"] {
        if which(c).is_none() {
            return Err(format!("butuh {}", c));
        }
    }
    let src = Path::new(zip).canonicalize().map_err(|e| format!("{}: {}", zip, e))?;
    let t = tempdir("src")?;
    let z = if src.is_dir() {
        find_source_root(&src).or_else(|| if src.join("app").is_dir() && src.join("module").is_dir() { Some(src.clone()) } else { None })
    } else {
        status(Command::new("unzip").arg("-q").arg(&src).arg("-d").arg(&t.0), "unzip")?;
        find_source_root(&t.0)
    }
    .ok_or("bukan source SetLoc")?;
    if !z.join("app").is_dir() || !z.join("module").is_dir() {
        return Err("bukan source SetLoc".into());
    }

    let repo = env_set("REPO").unwrap_or_else(|| format!("{}/proj/SetLoc", env::var("HOME").unwrap_or_default()));
    env::set_current_dir(&repo).map_err(|e| format!("{}: {}", repo, e))?;
    if !(Path::new("version.properties").is_file() && Path::new("app").is_dir() && Path::new("module").is_dir()) {
        return Err(format!("{} bukan repo SetLoc", repo));
    }
    if env_set("CHECK_CLEAN").is_some() {
        let st = out_of(Command::new("git").args(["status", "--porcelain", "app", "module"])).unwrap_or_default();
        if !st.is_empty() {
            return Err("CHECK_CLEAN=1 aktif dan ada perubahan belum di-commit di app/ atau module/".into());
        }
    }

    let ex = [
        "--exclude=local.properties", "--exclude=keystore.properties", "--exclude=*.p12", "--exclude=*.pem",
        "--exclude=*.jks", "--exclude=*.keystore", "--exclude=build/", "--exclude=.gradle/", "--exclude=external/",
        "--exclude=.cxx/", "--exclude=target/",
    ];
    rsync(&ex, &z.join("app"), "app")?;
    let mut mex = ex.to_vec();
    mex.extend(["--exclude=apk/", "--exclude=assets/bin/"]);
    rsync(&mex, &z.join("module"), "module")?;
    if z.join("daemon").is_dir() {
        rsync(&ex, &z.join("daemon"), "daemon")?;
    }
    for f in ["README.md", "make-keystore.sh", "update.sh", "setup.sh", ".gitignore", ".gitattributes", "version.properties", ".salt"] {
        if z.join(f).is_file() {
            fs::copy(z.join(f), f).map_err(|e| format!("salin {}: {}", f, e))?;
        }
    }
    for d in ["tools", ".cargo", ".github/workflows"] {
        if z.join(d).is_dir() {
            rsync(&["--exclude=target/"], &z.join(d), d)?;
        }
    }
    if z.join("native").is_dir() {
        rsync(&["--exclude=target/", "--exclude=build/"], &z.join("native"), "native")?;
    }
    fix_crlf(Path::new("."));
    let _ = fs::set_permissions("app/gradlew", fs::Permissions::from_mode(0o755));

    build()?;

    let ver = fs::read_to_string("version.properties")
        .unwrap_or_default()
        .lines()
        .find_map(|l| l.strip_prefix("versionName=").map(|v| v.trim().to_string()))
        .ok_or("versionName tidak ada di version.properties")?;
    let c = tempdir("zip")?;
    status(Command::new("unzip").arg("-q").arg(MODULE_ZIP).arg("-d").arg(&c.0), "unzip modul")?;
    let customize = fs::read_to_string(c.0.join("customize.sh")).unwrap_or_default();
    let apk = newest_apk().ok_or("APK rilis tidak ditemukan")?;
    let same_apk = fs::read(c.0.join("apk/SetLoc.apk")).ok() == fs::read(&apk).ok();
    if !(c.0.join("customize.d/40-install-apk.sh").is_file()
        && customize.lines().any(|l| l.trim() == ". \"$MODPATH/customize.d/40-install-apk.sh\"")
        && c.0.join("bin/arm64-v8a/setlocd").is_file()
        && same_apk)
    {
        return Err("isi zip tidak sesuai".into());
    }
    let home = env::var("HOME").unwrap_or_default();
    let out = format!("{}/SetLocZygisk-v{}.zip", home, ver);
    fs::copy(MODULE_ZIP, &out).map_err(|e| format!("salin zip: {}", e))?;
    if Path::new("/mnt/c").is_dir() {
        let _ = fs::copy(MODULE_ZIP, format!("/mnt/c/SetLocZygisk-v{}.zip", ver));
    }
    println!("SELESAI: {}", out);
    Ok(())
}
