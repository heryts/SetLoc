use crate::app::app_installed;
use crate::bridge::ensure_up;
use crate::cli::{bootts, hook_confirmed, module_prop, names};
use crate::codec::{BTKEY, KEY, NETKEY, SYSKEY, TRKKEY};
use crate::engine::Core;
use crate::sys::{self, csget, mtime_s, run, run_all, sget, uptime_cs, uptime_s, APP};
use configcore::track::{apply_track, parse_track, tk_on};
use std::fs;
use std::path::Path;

fn q(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('"');
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

fn list(v: &[String]) -> String {
    let items: Vec<String> = v.iter().map(|s| q(s)).collect();
    format!("[{}]", items.join(","))
}

fn kv<'a>(raw: &'a str, key: &str) -> Option<&'a str> {
    raw.split(';').rev().find_map(|p| {
        let (k, v) = p.split_once('=')?;
        if k == key {
            Some(v)
        } else {
            None
        }
    })
}

pub fn sys_mode(g: &str, confirmed: bool, up: i64, boot: i64, android: Option<i64>, off: Option<i64>) -> &'static str {
    let hb = g == "2";
    if let (Some(mt), true) = (android, g == "1" || hb) {
        return if mt < boot {
            if confirmed {
                if hb {
                    "heartbeat"
                } else {
                    "live"
                }
            } else if up < 120 {
                if hb {
                    "waithb"
                } else {
                    "wait"
                }
            } else if hb {
                "failedhb"
            } else {
                "failed"
            }
        } else if hb {
            "pendinghb"
        } else {
            "pending"
        };
    }
    match off {
        Some(mt) if mt >= boot => "offpending",
        _ => "off",
    }
}

fn sys_flags(mode: &str) -> (bool, bool) {
    (
        matches!(mode, "live" | "pending" | "failed" | "wait"),
        matches!(mode, "heartbeat" | "pendinghb" | "failedhb" | "waithb"),
    )
}

fn sys_json(pkgdir: &Path) -> String {
    let g = csget(SYSKEY).unwrap_or_default();
    let g = g.trim();
    let g = if g.len() == 1 && g.as_bytes()[0].is_ascii_digit() { g } else { "" };
    let stamp = |n: &str| {
        let p = pkgdir.join(n);
        if p.exists() {
            Some(mtime_s(&p))
        } else {
            None
        }
    };
    let mode = sys_mode(g, hook_confirmed(), uptime_s(), bootts(), stamp("android"), stamp(".sysoff"));
    let (s, h) = sys_flags(mode);
    format!("{{\"mode\":{},\"sys\":{},\"hb\":{}}}", q(mode), s, h)
}

fn state_json(pkgdir: &Path) -> String {
    let _ = fs::create_dir_all(pkgdir);
    let all = pkgdir.join(".all").exists();
    let files: Vec<String> = names(pkgdir).into_iter().filter(|n| !n.starts_with('.') && n != "android").collect();
    format!("{{\"all\":{},\"files\":{}}}", all, list(&files))
}

fn hide_json() -> String {
    let net = csget(NETKEY).as_deref() != Some("0");
    let bt = csget(BTKEY).as_deref() == Some("1");
    format!("{{\"net\":{},\"bt\":{}}}", net, bt)
}

fn cfg_json() -> String {
    let raw = csget(KEY).unwrap_or_default();
    let has = kv(&raw, "enabled").is_some();
    let on = kv(&raw, "enabled") == Some("1");
    let mut lat = kv(&raw, "lat").unwrap_or("").to_string();
    let mut lng = kv(&raw, "lng").unwrap_or("").to_string();
    if tk_on(&raw) {
        if let Some(u) = uptime_cs() {
            let trk = sget(TRKKEY);
            if let Some(t) = parse_track(trk.trim()) {
                let a = apply_track(&t, u * 10, 0);
                lat = format!("{:.6}", a.lat);
                lng = format!("{:.6}", a.lng);
            }
        }
    }
    let ready = has && !lat.is_empty() && !lng.is_empty();
    format!("{{\"has\":{},\"on\":{},\"lat\":{},\"lng\":{},\"ready\":{}}}", has, on, q(&lat), q(&lng), ready)
}

fn clean_ver(s: &str) -> String {
    let t = s.trim();
    t.strip_prefix(['v', 'V']).unwrap_or(t).to_string()
}

fn app_json(moddir: &Path) -> String {
    let installed = app_installed();
    let mut secure = false;
    let mut appv = String::new();
    if installed {
        let out = run_all("dumpsys", &["package", APP]).1;
        secure = out.contains("WRITE_SECURE_SETTINGS: granted=true");
        appv = out
            .lines()
            .find_map(|l| l.trim_start().strip_prefix("versionName="))
            .map(clean_ver)
            .unwrap_or_default();
    }
    let modv = clean_ver(&module_prop(moddir, "version"));
    format!(
        "{{\"installed\":{},\"secure\":{},\"ver\":{{\"mod\":{},\"app\":{}}}}}",
        installed,
        secure,
        q(&modv),
        q(&appv)
    )
}

fn pkgs(flag: &str) -> Vec<String> {
    let out = run("pm", &["list", "packages", flag]).map(|r| r.1).unwrap_or_default();
    out.lines()
        .filter_map(|l| l.trim().strip_prefix("package:"))
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect()
}

pub fn exec(args: &[String]) -> i32 {
    let moddir = sys::moddir();
    let pkgdir = moddir.join("packages");
    let a = |i: usize| args.get(i).map(String::as_str).unwrap_or("");
    let nosync = args.iter().any(|x| x == "--nosync");
    match a(0) {
        "status" => {
            let daemon = ensure_up(&moddir, false);
            Core::new(&moddir).sync_cfg(false);
            say!(
                "{{\"daemon\":{},\"state\":{},\"sys\":{},\"hide\":{},\"cfg\":{},\"app\":{}}}",
                daemon,
                state_json(&pkgdir),
                sys_json(&pkgdir),
                hide_json(),
                cfg_json(),
                app_json(&moddir)
            );
            0
        }
        "cfg" => {
            if !nosync {
                Core::new(&moddir).sync_cfg(false);
            }
            say!("{}", cfg_json());
            0
        }
        "sys" => {
            say!("{}", sys_json(&pkgdir));
            0
        }
        "app" => {
            say!("{}", app_json(&moddir));
            0
        }
        "pkgs" => match a(1) {
            "user" => {
                say!("{}", list(&pkgs("-3")));
                0
            }
            "system" => {
                say!("{}", list(&pkgs("-s")));
                0
            }
            _ => {
                say!("usage: webui pkgs user|system");
                1
            }
        },
        _ => {
            say!("usage: webui status|cfg [--nosync]|sys|app|pkgs user|system");
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_escape() {
        assert_eq!(q("a\"b\\c\n"), "\"a\\\"b\\\\c\\n\"");
        assert_eq!(q("\u{1}"), "\"\\u0001\"");
        assert_eq!(list(&["x".to_string(), "y.z".to_string()]), "[\"x\",\"y.z\"]");
        assert_eq!(list(&[]), "[]");
    }

    #[test]
    fn kv_last_wins() {
        assert_eq!(kv("enabled=1;lat=2;lat=3", "lat"), Some("3"));
        assert_eq!(kv("enabled=1", "lng"), None);
        assert_eq!(kv("enabled=", "enabled"), Some(""));
    }

    #[test]
    fn sys_modes() {
        let b = 1000;
        assert_eq!(sys_mode("1", true, 500, b, Some(10), None), "live");
        assert_eq!(sys_mode("2", true, 500, b, Some(10), None), "heartbeat");
        assert_eq!(sys_mode("1", false, 50, b, Some(10), None), "wait");
        assert_eq!(sys_mode("2", false, 50, b, Some(10), None), "waithb");
        assert_eq!(sys_mode("1", false, 500, b, Some(10), None), "failed");
        assert_eq!(sys_mode("2", false, 500, b, Some(10), None), "failedhb");
        assert_eq!(sys_mode("1", true, 500, b, Some(2000), None), "pending");
        assert_eq!(sys_mode("2", true, 500, b, Some(2000), None), "pendinghb");
        assert_eq!(sys_mode("0", true, 500, b, Some(10), None), "off");
        assert_eq!(sys_mode("", true, 500, b, Some(10), Some(2000)), "offpending");
        assert_eq!(sys_mode("1", true, 500, b, None, Some(10)), "off");
        assert_eq!(sys_mode("1", true, 500, b, None, None), "off");
    }

    #[test]
    fn sys_flag_sets() {
        assert_eq!(sys_flags("live"), (true, false));
        assert_eq!(sys_flags("waithb"), (false, true));
        assert_eq!(sys_flags("offpending"), (false, false));
        assert_eq!(sys_flags("off"), (false, false));
    }

    #[test]
    fn versions() {
        assert_eq!(clean_ver(" v1.2.3\n"), "1.2.3");
        assert_eq!(clean_ver("V2"), "2");
        assert_eq!(clean_ver("3"), "3");
    }
}
