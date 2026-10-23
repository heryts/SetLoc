use crate::codec::{dec, enc};
use std::ffi::CString;
use std::fs;
use std::os::raw::{c_char, c_int, c_ulong, c_void};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

pub const APP: &str = "com.heryts.setloc";

#[repr(C)]
struct PollFd {
    fd: c_int,
    events: i16,
    revents: i16,
}

extern "C" {
    fn poll(fds: *mut PollFd, nfds: c_ulong, timeout: c_int) -> c_int;
    fn signal(sig: c_int, handler: usize) -> usize;
    fn setsid() -> c_int;
    fn inotify_init1(flags: c_int) -> c_int;
    fn inotify_add_watch(fd: c_int, path: *const c_char, mask: u32) -> c_int;
    fn read(fd: c_int, buf: *mut c_void, count: usize) -> isize;
    fn close(fd: c_int) -> c_int;
}

pub static STOP: AtomicBool = AtomicBool::new(false);

extern "C" fn on_stop(_: c_int) {
    STOP.store(true, Ordering::SeqCst);
}

pub fn install_signals() {
    for s in [1, 2, 15] {
        unsafe {
            signal(s, on_stop as *const () as usize);
        }
    }
}

pub fn stopping() -> bool {
    STOP.load(Ordering::SeqCst)
}

pub fn wait_fd(fd: c_int, ms: u64) -> bool {
    let mut p = PollFd { fd, events: 1, revents: 0 };
    let r = unsafe { poll(&mut p, 1, ms.min(i32::MAX as u64) as c_int) };
    r > 0
}

pub fn sleep_ms(ms: u64) {
    wait_fd(-1, ms);
}

pub struct Ino {
    fd: c_int,
    pub dir: String,
    pub dead: bool,
}

impl Ino {
    pub fn open(dir: &str) -> Option<Ino> {
        let c = CString::new(dir).ok()?;
        let fd = unsafe { inotify_init1(0o4000 | 0o2000000) };
        if fd < 0 {
            return None;
        }
        let wd = unsafe { inotify_add_watch(fd, c.as_ptr(), 0x8 | 0x80 | 0x100 | 0x400 | 0x800) };
        if wd < 0 {
            unsafe {
                close(fd);
            }
            return None;
        }
        Some(Ino { fd, dir: dir.to_string(), dead: false })
    }

    pub fn fd(&self) -> c_int {
        self.fd
    }

    pub fn drain(&mut self) -> bool {
        let mut hit = false;
        let mut buf = [0u8; 4096];
        loop {
            let n = unsafe { read(self.fd, buf.as_mut_ptr() as *mut c_void, buf.len()) };
            if n <= 0 {
                break;
            }
            let n = n as usize;
            let mut off = 0;
            while off + 16 <= n {
                let mask = u32::from_ne_bytes(buf[off + 4..off + 8].try_into().unwrap());
                let len = u32::from_ne_bytes(buf[off + 12..off + 16].try_into().unwrap()) as usize;
                let end = (off + 16 + len).min(n);
                let name = buf[off + 16..end].split(|&b| b == 0).next().unwrap_or(&[]);
                if mask & 0x188 != 0 && name.ends_with(b"_prefs.xml") {
                    hit = true;
                }
                if mask & 0x8c00 != 0 {
                    self.dead = true;
                }
                off = end;
            }
        }
        hit
    }
}

impl Drop for Ino {
    fn drop(&mut self) {
        unsafe {
            close(self.fd);
        }
    }
}

pub fn data() -> PathBuf {
    PathBuf::from(std::env::var("SETLOCD_DATA").unwrap_or_else(|_| "/data".into()))
}

pub fn moddir() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(Path::to_path_buf))
        .unwrap_or_else(|| PathBuf::from("."))
}

pub fn env_u64(name: &str, default: u64) -> u64 {
    std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

pub fn run(prog: &str, args: &[&str]) -> Option<(bool, String)> {
    let o = Command::new(prog)
        .args(args)
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    let s = String::from_utf8_lossy(&o.stdout).trim_end_matches('\n').to_string();
    Some((o.status.success(), s))
}

pub fn run_all(prog: &str, args: &[&str]) -> (bool, String) {
    match Command::new(prog).args(args).stdin(Stdio::null()).output() {
        Ok(o) => {
            let mut s = String::from_utf8_lossy(&o.stdout).into_owned();
            s.push_str(&String::from_utf8_lossy(&o.stderr));
            (o.status.success(), s.trim_end_matches('\n').to_string())
        }
        Err(e) => (false, e.to_string()),
    }
}

pub fn quiet(prog: &str, args: &[&str]) -> bool {
    Command::new(prog)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

pub fn quiet_bg(cmds: Vec<Vec<String>>) {
    std::thread::spawn(move || {
        for c in cmds {
            if let Some((prog, args)) = c.split_first() {
                let _ = Command::new(prog)
                    .args(args)
                    .stdin(Stdio::null())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .status();
            }
        }
    });
}

pub fn sget(key: &str) -> String {
    match run("cmd", &["settings", "get", "global", key]) {
        Some((true, v)) => v,
        _ => run("settings", &["get", "global", key]).map(|r| r.1).unwrap_or_default(),
    }
}

pub fn sput(key: &str, val: &str) -> bool {
    quiet("cmd", &["settings", "put", "global", key, val]) || quiet("settings", &["put", "global", key, val])
}

pub fn sdel(key: &str) {
    if !quiet("cmd", &["settings", "delete", "global", key]) {
        quiet("settings", &["delete", "global", key]);
    }
}

pub fn csget(key: &str) -> Option<String> {
    let v = sget(key);
    if v.is_empty() || v == "null" {
        return None;
    }
    dec(&v)
}

pub fn csput(key: &str, val: &str) -> bool {
    sput(key, &enc(val))
}

pub fn uptime_cs() -> Option<i64> {
    let s = fs::read_to_string("/proc/uptime").ok()?;
    let first = s.split_whitespace().next()?;
    let (i, f) = first.split_once('.')?;
    format!("{}{}", i, f).parse().ok()
}

pub fn uptime_s() -> i64 {
    uptime_cs().map_or(0, |c| c / 100)
}

pub fn now_s() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64)
}

pub fn mtime_s(p: &Path) -> i64 {
    fs::metadata(p)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map_or(0, |d| d.as_secs() as i64)
}

pub fn newer(a: &Path, b: &Path) -> bool {
    let ma = match fs::metadata(a).and_then(|m| m.modified()) {
        Ok(t) => t,
        Err(_) => return false,
    };
    match fs::metadata(b).and_then(|m| m.modified()) {
        Ok(tb) => ma > tb,
        Err(_) => true,
    }
}

pub fn cmdline(pid: &str) -> Vec<String> {
    fs::read(format!("/proc/{}/cmdline", pid))
        .map(|b| {
            b.split(|&c| c == 0)
                .filter(|s| !s.is_empty())
                .map(|s| String::from_utf8_lossy(s).into_owned())
                .collect()
        })
        .unwrap_or_default()
}

pub fn is_self_cmd(pid: &str, sub: &str) -> bool {
    let a = cmdline(pid);
    a.len() >= 2 && a[0].ends_with("setlocd") && a[1] == sub
}

pub fn pidof(name: &str) -> Vec<String> {
    let mut out = Vec::new();
    if let Ok(rd) = fs::read_dir("/proc") {
        for e in rd.flatten() {
            let pid = e.file_name().to_string_lossy().into_owned();
            if pid.bytes().all(|b| b.is_ascii_digit()) && cmdline(&pid).first().map(String::as_str) == Some(name) {
                out.push(pid);
            }
        }
    }
    out.sort_by_key(|p| p.parse::<u64>().unwrap_or(0));
    out
}

pub fn oom_protect() {
    let _ = fs::write("/proc/self/oom_score_adj", "-1000");
}

pub fn spawn_detached(args: &[&str]) -> bool {
    let exe = match std::env::current_exe() {
        Ok(e) => e,
        Err(_) => return false,
    };
    let mut c = Command::new(exe);
    c.args(args).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    unsafe {
        c.pre_exec(|| {
            setsid();
            Ok(())
        });
    }
    match c.spawn() {
        Ok(mut child) => {
            std::thread::spawn(move || {
                let _ = child.wait();
            });
            true
        }
        Err(_) => false,
    }
}

pub fn boot_completed() -> bool {
    run("getprop", &["sys.boot_completed"]).is_some_and(|r| r.1 == "1")
}
