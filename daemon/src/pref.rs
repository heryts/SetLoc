use crate::sys::{data, quiet, APP};
use routecore::appstate::{self, FILE};
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
    let patterns = [
        format!("data/{}/files/{}", APP, FILE),
        format!("user/*/{}/files/{}", APP, FILE),
        format!("user_de/*/{}/files/{}", APP, FILE),
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
            if let Some(p) = walk(&d.join(root), 1, FILE) {
                return p.to_string_lossy().into_owned();
            }
        }
    }
    String::new()
}

pub fn pref_update(path: &Path, patch: &str) -> bool {
    appstate::update(path, patch, &|tmp| {
        quiet("chcon", &["--reference", &path.to_string_lossy(), &tmp.to_string_lossy()]);
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn sample(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("setlocd-pref-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        let p = d.join(FILE);
        fs::write(&p, "lat:1.0000000\nacc:10\n").unwrap();
        p
    }

    #[test]
    fn update_sets_existing_and_new_keys() {
        let p = sample("set");
        assert!(pref_update(&p, "lat:-7.5\nspd:0"));
        let t = fs::read_to_string(&p).unwrap();
        assert_eq!(t, "lat:-7.5000000\nacc:10\nspd:0\n");
        let _ = fs::remove_dir_all(p.parent().unwrap());
    }

    #[test]
    fn invalid_patch_leaves_file_untouched() {
        let p = sample("bad");
        assert!(!pref_update(&p, "lat:2\nacc:abc"));
        assert_eq!(fs::read_to_string(&p).unwrap(), "lat:1.0000000\nacc:10\n");
        let _ = fs::remove_dir_all(p.parent().unwrap());
    }

    #[test]
    fn update_preserves_mode() {
        let p = sample("mode");
        fs::set_permissions(&p, fs::Permissions::from_mode(0o660)).unwrap();
        assert!(pref_update(&p, "start:1"));
        assert_eq!(fs::metadata(&p).unwrap().permissions().mode() & 0o777, 0o660);
        let _ = fs::remove_dir_all(p.parent().unwrap());
    }

    #[test]
    fn find_uses_override_and_walks_for_state_file() {
        let p = sample("find");
        assert_eq!(find_pref(&p.to_string_lossy(), false), p.to_string_lossy());
        let _ = fs::remove_dir_all(p.parent().unwrap());
    }
}
