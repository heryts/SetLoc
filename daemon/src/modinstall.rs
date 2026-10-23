use crate::app::install_app_out;
use crate::sys::{boot_completed, quiet, run, APP};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

fn cp_a(src: &str, dst: &str) -> bool {
    Command::new("cp")
        .args(["-a", src, dst])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map_or(false, |s| s.success())
}

fn in_path(name: &str) -> bool {
    std::env::var_os("PATH").map_or(false, |p| std::env::split_paths(&p).any(|d| d.join(name).is_file()))
}

fn keep_config(old: &Path, new: &Path) {
    if old == new || !old.join("packages").is_dir() {
        return;
    }
    let _ = fs::create_dir_all(new.join("packages"));
    let src = format!("{}/.", old.join("packages").display());
    let dst = format!("{}/", new.join("packages").display());
    if cp_a(&src, &dst) {
        say!("- Konfigurasi lama dipertahankan");
    } else {
        say!("  ! gagal menyalin konfigurasi lama");
    }
    for f in [".hb_default", "disable_super", "disable_trk"] {
        let s = old.join(f);
        if s.is_file() {
            let _ = cp_a(&s.to_string_lossy(), &new.join(f).to_string_lossy());
        }
    }
}

fn install_apk(m: &Path) {
    say!("- Install app SetLoc");
    let apk = m.join("apk/SetLoc.apk");
    if !apk.is_file() {
        say!("  ! APK tidak ada di modul, dilewati");
        return;
    }
    if !in_path("pm") || !boot_completed() {
        say!("  ! Android belum berjalan penuh (recovery?), app dipasang otomatis setelah reboot");
        return;
    }
    let (ok, out) = install_app_out(&apk);
    if ok {
        let ap = apk.to_string_lossy().to_string();
        let sum = run("md5sum", &[&ap])
            .map(|r| r.1.split_whitespace().next().unwrap_or("").to_string())
            .unwrap_or_default();
        let _ = fs::write(m.join(".apk_sum"), format!("{}\n", sum));
        quiet("cmd", &["appops", "set", APP, "SYSTEM_ALERT_WINDOW", "allow"]);
        say!("  app SetLoc terpasang");
    } else {
        say!("  ! pm install gagal: {}", out);
        say!("  ! dicoba lagi otomatis setelah reboot");
    }
}

pub fn run_install(args: &[String]) -> i32 {
    let a = |i: usize| args.get(i).map(String::as_str).unwrap_or("");
    if a(0).is_empty() {
        say!("usage: setlocd modinstall <MODPATH> [OLD_MOD] [MODID]");
        return 1;
    }
    let modpath = PathBuf::from(a(0));
    let old = if a(1).is_empty() {
        let id = if a(2).is_empty() { "setloc_zygisk" } else { a(2) };
        PathBuf::from(format!("/data/adb/modules/{}", id))
    } else {
        PathBuf::from(a(1))
    };
    keep_config(&old, &modpath);
    install_apk(&modpath);
    0
}
