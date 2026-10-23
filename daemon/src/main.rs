macro_rules! say {
    ($($a:tt)*) => {{
        use std::io::Write;
        let _ = writeln!(std::io::stdout(), $($a)*);
    }};
}

mod app;
mod bridge;
mod cli;
mod codec;
mod config;
mod daemon;
mod engine;
mod lock;
mod pref;
mod supervisor;
mod sys;

use crate::engine::Core;

fn bridge_cmd(args: &[String]) -> i32 {
    let moddir = sys::moddir();
    let mut core = Core::new(&moddir);
    let a = |i: usize| args.get(i).map(String::as_str).unwrap_or("");
    match a(0) {
        "install" => bridge::install(&moddir),
        "sync" => {
            core.sync_cfg(false);
            0
        }
        "mirror" => {
            core.mirror_from_settings();
            0
        }
        "force" => {
            if core.sync_cfg(true) {
                0
            } else {
                1
            }
        }
        "setstart" => bridge::set_app_start(&mut core, a(1) == "1"),
        "setpos" => bridge::set_app_pos(&mut core, a(1), a(2), a(3)),
        "setrandom" => bridge::set_app_random(&mut core, a(1)),
        "ensure" => bridge::ensure(&moddir, a(1) == "super"),
        "status" => {
            bridge::status(&moddir, &mut core);
            0
        }
        "sysdefault" => {
            daemon::sysdefault();
            0
        }
        "super" => supervisor::run(),
        "daemon" => daemon::run(),
        _ => {
            say!("usage: setlocd ensure [super]|sync|mirror|force|setstart 0|1|setpos <lat> <lng> [acc]|setrandom 0|1|status|install|sysdefault|gpsz ...");
            1
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match args.first().map(String::as_str) {
        Some("gpsz") => cli::run_cli(&args[1..]),
        _ => bridge_cmd(&args),
    };
    std::process::exit(code);
}
