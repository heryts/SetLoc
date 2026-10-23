use crate::bridge::{self, ensure, set_app_pos, set_app_random, set_app_start};
use crate::codec::{dec, enc, inrange, BEATKEY, BTKEY, KEY, NETKEY, SYSKEY, TRKKEY};
use crate::config::{DEFAULT_LAT, DEFAULT_LNG};
use crate::engine::Core;
use crate::lsguard;
use crate::sys::{self, csget, mtime_s, now_s, pidof, quiet, run, sget, sput, uptime_s};
use std::fs;
use std::path::{Path, PathBuf};

struct State {
    raw: String,
    e: String,
    lat: String,
    lng: String,
    acc: String,
    rnd: String,
    spd: String,
    brg: String,
    alt: String,
    altv: String,
    rr: String,
}

fn get(raw: &str, k: &str) -> String {
    let t = format!(";{};", raw);
    let pat = format!(";{}=", k);
    match t.find(&pat) {
        Some(i) => t[i + pat.len()..].split(';').next().unwrap_or("").to_string(),
        None => String::new(),
    }
}

fn or(v: String, d: &str) -> String {
    if v.is_empty() {
        d.to_string()
    } else {
        v
    }
}

fn load() -> State {
    let raw = dec(&sget(KEY)).unwrap_or_default();
    State {
        e: or(get(&raw, "enabled"), "0"),
        lat: or(get(&raw, "lat"), DEFAULT_LAT),
        lng: or(get(&raw, "lng"), DEFAULT_LNG),
        acc: or(get(&raw, "acc"), "10"),
        rnd: or(get(&raw, "random"), "0"),
        spd: get(&raw, "spd"),
        brg: get(&raw, "brg"),
        alt: get(&raw, "alt"),
        altv: get(&raw, "altv"),
        rr: get(&raw, "rr"),
        raw,
    }
}

fn isnum(s: &str) -> bool {
    !(s.is_empty()
        || s == "-"
        || s == "."
        || s == "-."
        || s.bytes().any(|b| !(b.is_ascii_digit() || b == b'.' || b == b'-'))
        || s.matches('.').count() > 1
        || s.get(1..).is_some_and(|r| r.contains('-')))
}

fn validpkg(s: &str) -> bool {
    !(s.is_empty() || s.starts_with('.') || s.bytes().any(|b| !(b.is_ascii_alphanumeric() || b == b'.' || b == b'_')))
}

fn save(st: &State, core: &Core) {
    let mut x = String::new();
    for (k, v) in [("spd", &st.spd), ("alt", &st.alt), ("altv", &st.altv), ("rr", &st.rr), ("brg", &st.brg)] {
        if !v.is_empty() {
            x.push_str(&format!(";{}={}", k, v));
        }
    }
    sput(KEY, &enc(&format!("enabled={};lat={};lng={};acc={};random={}{}", st.e, st.lat, st.lng, st.acc, st.rnd, x)));
    core.mirror_from_settings();
}

fn bootts() -> i64 {
    now_s() - uptime_s()
}

fn write_empty(p: &Path) {
    let _ = fs::write(p, "");
}

fn touch_ref(target: &Path, reference: &Path) {
    if let Ok(m) = fs::metadata(reference).and_then(|m| m.modified()) {
        if let Ok(f) = fs::OpenOptions::new().write(true).create(true).truncate(false).open(target) {
            let _ = f.set_modified(m);
        }
    }
}

fn names(dir: &Path) -> Vec<String> {
    let mut v: Vec<String> = fs::read_dir(dir)
        .map(|rd| rd.flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect())
        .unwrap_or_default();
    v.sort();
    v
}

fn log_lines(args: &[&str], needle: &str, ci: bool, tail: usize) -> Vec<String> {
    let out = run("logcat", args).map(|r| r.1).unwrap_or_default();
    let n = if ci { needle.to_lowercase() } else { needle.to_string() };
    let m: Vec<&str> = out
        .lines()
        .filter(|l| if ci { l.to_lowercase().contains(&n) } else { l.contains(&n) })
        .collect();
    m[m.len().saturating_sub(tail)..].iter().map(|s| format!("  {}", s)).collect()
}

struct Ctx {
    moddir: PathBuf,
    pkgdir: PathBuf,
}

impl Ctx {
    fn injected(&self) -> bool {
        let a = self.pkgdir.join("android");
        a.exists() && mtime_s(&a) < bootts()
    }

    fn sysmode(&self, core: &Core, val: &str) -> bool {
        if fs::create_dir_all(&self.pkgdir).is_err() {
            return false;
        }
        let off = self.pkgdir.join(".sysoff");
        let mt = self.pkgdir.join(".sysmt");
        let android = self.pkgdir.join("android");
        let keep = off.is_file() && mt.is_file() && mtime_s(&off) >= bootts();
        let _ = fs::remove_file(&off);
        if !android.exists() {
            write_empty(&android);
            if keep {
                touch_ref(&android, &mt);
            }
        }
        let _ = fs::remove_file(&mt);
        sput(SYSKEY, &enc(val));
        core.mirror_from_settings();
        true
    }

    fn system(&self, core: &Core, arg: &str) {
        let android = self.pkgdir.join("android");
        let off = self.pkgdir.join(".sysoff");
        let mt = self.pkgdir.join(".sysmt");
        match arg {
            "on" => {
                self.sysmode(core, "1");
                say!("system hook ON (mode PENUH: semua app dispoof). REBOOT dibutuhkan bila belum live. Bootloop? buat file {}/disable dari recovery.", self.moddir.display());
            }
            "heartbeat" => {
                self.sysmode(core, "2");
                say!("system hook mode HEARTBEAT: sistem hanya mengulang fix asli 1 Hz, lokasi app lain TIDAK diubah (spoof hanya app di packages/). Bila sudah live, berlaku tanpa reboot; bila belum, REBOOT.");
            }
            "off" => {
                if android.exists() {
                    if self.injected() {
                        touch_ref(&mt, &android);
                        let _ = fs::remove_file(&android);
                        write_empty(&off);
                    } else {
                        for f in [&android, &off, &mt] {
                            let _ = fs::remove_file(f);
                        }
                        sput(SYSKEY, &enc("0"));
                        core.mirror_from_settings();
                    }
                } else if !(off.is_file() && mtime_s(&off) >= bootts()) {
                    sput(SYSKEY, &enc("0"));
                    core.mirror_from_settings();
                }
                say!("system hook OFF. Reboot agar system_server tidak lagi di-inject.");
            }
            "status" => {
                let f = android.exists();
                let sv = csget(SYSKEY).unwrap_or_default();
                let mut st = "off";
                if (sv == "1" || sv == "2") && f {
                    st = if self.injected() { "live" } else { "pending" };
                }
                say!("packages/android: {}   sys={}   state={}", if f { "ya" } else { "tidak" }, sv, st);
                say!("log system hook (logcat -s GpsZ):");
                for l in log_lines(&["-d", "-s", "GpsZ:I"], "system hook", true, 12) {
                    say!("{}", l);
                }
            }
            _ => say!("system on|heartbeat|off|status"),
        }
    }

    fn ls_system(&self, arg: &str) {
        let st = lsguard::state(&self.moddir);
        match arg {
            "on" => {
                if lsguard::enable(&self.moddir) {
                    say!("LSPlant di system_server: AKTIF (default). REBOOT dibutuhkan. Jika boot sebelumnya gagal, percobaan otomatis diblokir dan hook sistem tidak dimuat; lihat: ls-system status.");
                } else {
                    say!("gagal menghapus {}", lsguard::DISABLE);
                }
            }
            "off" => {
                if lsguard::disable(&self.moddir) {
                    say!("LSPlant di system_server: MATI. Reboot agar system_server tidak memuat hook.");
                } else {
                    say!("gagal membuat {}", lsguard::DISABLE);
                }
            }
            "reset" => {
                lsguard::reset(&self.moddir);
                say!("penanda boot dan blokir dihapus. Percobaan berikutnya saat reboot.");
            }
            "status" => {
                say!("ls-system={}", st);
                say!(
                    "{}: {}   {}: {}   {}: {}",
                    lsguard::DISABLE,
                    if self.moddir.join(lsguard::DISABLE).exists() { "ya" } else { "tidak" },
                    lsguard::PENDING,
                    if self.moddir.join(lsguard::PENDING).exists() { "ya" } else { "tidak" },
                    lsguard::BLOCKED,
                    if self.moddir.join(lsguard::BLOCKED).exists() { "ya" } else { "tidak" }
                );
                match st {
                    "blocked" => say!("percobaan sebelumnya tidak selesai (system_server mati sebelum stabil). Hook sistem tidak dimuat. Setelah penyebab dicek: ls-system reset"),
                    "pending" => say!("system_server sedang dicoba dengan LSPlant dan belum terkonfirmasi stabil ({} dtk)", lsguard::STABLE_S),
                    _ => {}
                }
            }
            _ => say!("ls-system on|off|status|reset"),
        }
    }

    fn pkg(&self, args: &[String]) {
        let sub = args.first().map(String::as_str).unwrap_or("");
        let name = args.get(1).map(String::as_str).unwrap_or("");
        match sub {
            "add" | "rm" => {
                if !validpkg(name) {
                    say!("usage: pkg {} <package>", sub);
                    return;
                }
                if name == "android" {
                    say!("android diatur lewat: system on|heartbeat|off");
                    return;
                }
                if sub == "add" {
                    if fs::create_dir_all(&self.pkgdir).is_ok() {
                        write_empty(&self.pkgdir.join(name));
                        quiet("am", &["force-stop", name]);
                    }
                    say!("added {} (app restarted)", name);
                } else {
                    let _ = fs::remove_file(self.pkgdir.join(name));
                    quiet("am", &["force-stop", name]);
                    say!("removed {}", name);
                }
            }
            "list" => {
                for n in names(&self.pkgdir) {
                    say!("{}", n);
                }
            }
            "all" => {
                let _ = fs::create_dir_all(&self.pkgdir);
                if name == "on" {
                    write_empty(&self.pkgdir.join(".all"));
                    say!("injecting into ALL apps (risky; reboot or restart apps)");
                } else {
                    let _ = fs::remove_file(self.pkgdir.join(".all"));
                    say!("all-apps mode off");
                }
            }
            _ => say!("pkg add|rm <package> | pkg list | pkg all on|off"),
        }
    }
}

fn module_prop(moddir: &Path, key: &str) -> String {
    let t = fs::read_to_string(moddir.join("module.prop")).unwrap_or_default();
    let pre = format!("{}=", key);
    t.lines().find_map(|l| l.strip_prefix(&pre)).unwrap_or("").to_string()
}

pub fn run_cli(args: &[String]) -> i32 {
    let moddir = sys::moddir();
    let ctx = Ctx { pkgdir: moddir.join("packages"), moddir: moddir.clone() };
    let mut core = Core::new(&moddir);
    let a = |i: usize| args.get(i).map(String::as_str).unwrap_or("");
    let cmd = a(0);
    if !matches!(cmd, "sync" | "check" | "status" | "doctor" | "super" | "net" | "bt" | "ls-system") {
        core.sync_cfg(false);
    }
    let mut st = load();
    match cmd {
        "on" | "off" | "toggle" => {
            let on = match cmd {
                "on" => true,
                "off" => false,
                _ => st.e != "1",
            };
            st.e = if on { "1".into() } else { "0".into() };
            save(&st, &core);
            set_app_start(&mut core, on);
        }
        "set" => {
            if !(isnum(a(1)) && isnum(a(2))) {
                say!("usage: set <lat> <lng> [acc]");
                return 1;
            }
            if !(inrange(a(1), 90) && inrange(a(2), 180)) {
                say!("lat harus -90..90 dan lng -180..180");
                return 1;
            }
            st.lat = a(1).into();
            st.lng = a(2).into();
            st.spd = "0".into();
            st.brg.clear();
            if !a(3).is_empty() {
                if !(isnum(a(3)) && !a(3).starts_with('-')) {
                    say!("acc must be a non-negative number");
                    return 1;
                }
                st.acc = a(3).into();
            }
            save(&st, &core);
            set_app_pos(&mut core, a(1), a(2), a(3));
        }
        "random" => match a(1) {
            "0" | "1" => {
                st.rnd = a(1).into();
                save(&st, &core);
                set_app_random(&mut core, a(1));
            }
            _ => {
                say!("usage: random 0|1");
                return 1;
            }
        },
        "bt" => match a(1) {
            "on" => {
                sput(BTKEY, &enc("1"));
                core.mirror_from_settings();
                say!("blokir scan Bluetooth: aktif");
            }
            "off" => {
                sput(BTKEY, &enc("0"));
                core.mirror_from_settings();
                say!("blokir scan Bluetooth: mati");
            }
            _ => say!("{}", if csget(BTKEY).as_deref() == Some("1") { "bt=aktif" } else { "bt=mati" }),
        },
        "net" => match a(1) {
            "on" => {
                sput(NETKEY, &enc("1"));
                core.mirror_from_settings();
                say!("sembunyikan Wi-Fi/cell: aktif");
            }
            "off" => {
                sput(NETKEY, &enc("0"));
                core.mirror_from_settings();
                say!("sembunyikan Wi-Fi/cell: mati");
            }
            _ => say!("{}", if csget(NETKEY).as_deref() == Some("0") { "net=mati" } else { "net=aktif" }),
        },
        "show" => {
            say!(
                "enabled={} lat={} lng={} acc={} random={} spd={} brg={} alt={} altv={} rr={}",
                st.e,
                st.lat,
                st.lng,
                st.acc,
                st.rnd,
                or(st.spd.clone(), "0"),
                or(st.brg.clone(), "-"),
                or(st.alt.clone(), "0"),
                or(st.altv.clone(), "0"),
                or(st.rr.clone(), "8")
            );
            if ctx.pkgdir.join(".all").exists() {
                say!("packages: ALL apps (listed ones below are EXCLUDED)");
            }
            for n in names(&ctx.pkgdir) {
                if !matches!(n.as_str(), ".all" | ".sysoff" | ".sysmt") {
                    say!("  pkg: {}", n);
                }
            }
        }
        "pkg" => ctx.pkg(&args[1.min(args.len())..]),
        "system" => ctx.system(&core, a(1)),
        "ls-system" => ctx.ls_system(a(1)),
        "sync" => {
            core.sync_cfg(true);
            say!("cfg={}", csget(KEY).unwrap_or_default());
        }
        "status" => bridge::status(&moddir, &mut core),
        "check" => {
            if !validpkg(a(1)) {
                say!("usage: check <package>");
                return 1;
            }
            let f = ctx.pkgdir.join(a(1)).exists();
            let all = ctx.pkgdir.join(".all").exists();
            let want = if all { !f } else { f };
            let yn = |b: bool| if b { "ya" } else { "tidak" };
            say!("config : {}", st.raw);
            say!(
                "file packages/{} : {}   (mode .all: {})  -> seharusnya di-inject: {}",
                a(1),
                yn(f),
                yn(all),
                if want { "YA" } else { "TIDAK" }
            );
            let pids = pidof(a(1));
            say!("proses berjalan : {}", if pids.is_empty() { "tidak ada".to_string() } else { pids.join(" ") });
            say!("log inject terakhir (logcat -s GpsZ):");
            for l in log_lines(&["-d", "-s", "GpsZ:I"], &format!("injected into {}", a(1)), false, 3) {
                say!("{}", l);
            }
            say!("  (kosong = proses ini TIDAK pernah ter-inject sejak buffer log terakhir)");
        }
        "super" => match a(1) {
            "off" => {
                write_empty(&moddir.join("disable_super"));
                say!("supervisor dimatikan (daemon yang di-kill tidak dinyalakan lagi)");
            }
            "on" => {
                let _ = fs::remove_file(moddir.join("disable_super"));
                ensure(&moddir, false);
                say!("supervisor aktif");
            }
            _ => say!("{}", if moddir.join("disable_super").exists() { "supervisor=dimatikan" } else { "supervisor=aktif" }),
        },
        "doctor" => {
            bridge::status(&moddir, &mut core);
            let ds = moddir.join("disable_super").exists();
            say!("{}", if ds { "supervisor=dimatikan (disable_super)" } else { "supervisor=aktif" });
            say!("modul={} code={}", module_prop(&moddir, "version"), module_prop(&moddir, "versionCode"));
            say!(
                "sys={} beat={} uptime={}",
                csget(SYSKEY).unwrap_or_default(),
                csget(BEATKEY).unwrap_or_default(),
                std::fs::read_to_string("/proc/uptime").unwrap_or_default().split_whitespace().next().unwrap_or("")
            );
            say!("ls-system={}", lsguard::state(&moddir));
            if moddir.join("disable_trk").exists() {
                say!("trk=dimatikan (disable_trk)");
            } else {
                say!("trk=aktif trkdata={}", sget(TRKKEY).chars().take(48).collect::<String>());
            }
            for f in [core.cfgs(), core.cfgf()] {
                if f.is_file() {
                    say!("mirror: {}", run("ls", &["-lZ", &f.to_string_lossy()]).map(|r| r.1).unwrap_or_default());
                } else {
                    say!("mirror: tidak ada ({})", f.display());
                }
            }
            say!("baca mirror oleh system_server (logcat -s GpsZ):");
            for l in log_lines(&["-d", "-s", "GpsZ:I", "GpsZ:W"], "config file", true, 4) {
                say!("{}", l);
            }
            say!("--- hook sistem (logcat -s GpsZ)");
            for l in log_lines(&["-d", "-s", "GpsZ:I"], "system hook", true, 8) {
                say!("{}", l);
            }
        }
        _ => say!("on|off|toggle|set <lat> <lng> [acc]|random 0|1|net on|off|bt on|off|show|pkg ...|system on|heartbeat|off|status|ls-system on|off|status|reset|sync|check <pkg>|super on|off|doctor"),
    }
    0
}
