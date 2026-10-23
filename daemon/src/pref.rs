use crate::sys::{data, APP};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

fn expand(base: &Path, pat: &str) -> Vec<PathBuf> {
    let mut cur = vec![base.to_path_buf()];
    for seg in pat.split('/') {
        let mut next = Vec::new();
        for p in &cur {
            if seg == "*" {
                if let Ok(rd) = fs::read_dir(p) {
                    for e in rd.flatten() {
                        next.push(e.path());
                    }
                }
            } else {
                next.push(p.join(seg));
            }
        }
        cur = next;
    }
    cur.into_iter().filter(|p| p.is_file()).collect()
}

fn walk(dir: &Path, depth: usize, name: &str) -> Option<PathBuf> {
    if depth > 6 {
        return None;
    }
    let rd = fs::read_dir(dir).ok()?;
    let mut subdirs = Vec::new();
    for e in rd.flatten() {
        let p = e.path();
        if p.file_name().is_some_and(|n| n == name) {
            return Some(p);
        }
        if e.file_type().is_ok_and(|t| t.is_dir()) {
            subdirs.push(p);
        }
    }
    subdirs.into_iter().find_map(|d| walk(&d, depth + 1, name))
}

pub fn find_pref(current: &str, deep: bool) -> String {
    if !current.is_empty() && Path::new(current).is_file() {
        return current.to_string();
    }
    let d = data();
    let name = format!("{}_prefs.xml", APP);
    let patterns = [
        format!("data/{}/shared_prefs/{}", APP, name),
        format!("user/*/{}/shared_prefs/{}", APP, name),
        format!("user_de/*/{}/shared_prefs/{}", APP, name),
        format!("misc/*/prefs/{}/{}", APP, name),
        format!("misc/*/*/prefs/{}/{}", APP, name),
    ];
    let mut found: Vec<(SystemTime, PathBuf)> = Vec::new();
    for pat in &patterns {
        for p in expand(&d, pat) {
            let t = fs::metadata(&p).and_then(|m| m.modified()).unwrap_or(SystemTime::UNIX_EPOCH);
            found.push((t, p));
        }
    }
    found.sort_by_key(|f| std::cmp::Reverse(f.0));
    if let Some((_, p)) = found.first() {
        return p.to_string_lossy().into_owned();
    }
    if deep {
        for root in ["misc", "user", "user_de"] {
            if let Some(p) = walk(&d.join(root), 1, &name) {
                return p.to_string_lossy().into_owned();
            }
        }
    }
    String::new()
}

pub fn pref_set(path: &Path, ty: &str, name: &str, val: &str) {
    let txt = match fs::read_to_string(path) {
        Ok(t) => t,
        Err(_) => return,
    };
    let presence = format!("<{} name=\"{}\"", ty, name);
    let new = match txt.find(&presence) {
        Some(i) => {
            let key = format!("{} value=\"", presence);
            let s = match txt[i..].starts_with(&key) {
                true => i + key.len(),
                false => return,
            };
            match txt[s..].find('"') {
                Some(e) => format!("{}{}{}", &txt[..s], val, &txt[s + e..]),
                None => return,
            }
        }
        None => txt.replacen("</map>", &format!("    <{} name=\"{}\" value=\"{}\" />\n</map>", ty, name, val), 1),
    };
    if new.contains(&format!("name=\"{}\" value=\"{}\"", name, val)) {
        let _ = fs::write(path, new);
    }
}

pub fn pref_str(path: &Path, name: &str, val: &str) {
    let txt = match fs::read_to_string(path) {
        Ok(t) => t,
        Err(_) => return,
    };
    let open = format!("<string name=\"{}\">", name);
    let new = match txt.find(&open) {
        Some(i) => {
            let s = i + open.len();
            match txt[s..].find('<') {
                Some(e) => format!("{}{}{}", &txt[..s], val, &txt[s + e..]),
                None => return,
            }
        }
        None => txt.replacen("</map>", &format!("    <string name=\"{}\">{}</string>\n</map>", name, val), 1),
    };
    if new.contains(&format!("{}{}</string>", open, val)) && new.contains("</map>") {
        let _ = fs::write(path, new);
    }
}

pub fn pref_del(path: &Path, name: &str) {
    let txt = match fs::read_to_string(path) {
        Ok(t) => t,
        Err(_) => return,
    };
    let probe = format!("name=\"{}\"", name);
    if !txt.contains(&probe) {
        return;
    }
    let open = format!("<string name=\"{}\">", name);
    let new: String = txt
        .split_inclusive('\n')
        .filter(|l| !(l.contains(&open) && l.contains("</string>")))
        .collect();
    if !new.is_empty() && !new.contains(&probe) {
        let _ = fs::write(path, new);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(tag: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("setlocd-pref-{}-{}.xml", tag, std::process::id()));
        fs::write(
            &p,
            "<map>\n    <float name=\"latitude\" value=\"1.0\" />\n    <string name=\"accuracy_settings\">10</string>\n</map>\n",
        )
        .unwrap();
        p
    }

    #[test]
    fn set_existing_and_new() {
        let p = sample("set");
        pref_set(&p, "float", "latitude", "-7.5");
        pref_set(&p, "float", "speed_ms", "0");
        let t = fs::read_to_string(&p).unwrap();
        assert!(t.contains("<float name=\"latitude\" value=\"-7.5\" />"));
        assert!(t.contains("<float name=\"speed_ms\" value=\"0\" />\n</map>"));
        let _ = fs::remove_file(&p);
    }

    #[test]
    fn str_and_del() {
        let p = sample("str");
        pref_str(&p, "accuracy_settings", "25");
        assert!(fs::read_to_string(&p).unwrap().contains(">25</string>"));
        pref_del(&p, "accuracy_settings");
        assert!(!fs::read_to_string(&p).unwrap().contains("accuracy_settings"));
        let _ = fs::remove_file(&p);
    }
}
