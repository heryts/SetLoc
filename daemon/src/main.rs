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
mod modinstall;
mod pref;
mod service;
mod supervisor;
mod sys;
mod webui;

use crate::codec::KEY;
use crate::engine::Core;
use crate::sys::csget;

fn bridge_cmd(args: &[String]) -> i32 {
    let moddir = sys::moddir();
    let mut core = Core::new(&moddir);
    let a = |i: usize| args.get(i).map(String::as_str).unwrap_or("");
    match a(0) {
        "install" => bridge::install(&moddir),
        "modinstall" => modinstall::run_install(&args[1..]),
        "sync" => {
            core.sync_cfg(false);
            0
        }
        "mirror" => {
            if let Some(c) = csget(KEY) {
                if !c.is_empty() {
                    core.mirror_cfg(&c);
                }
            }
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
        "service" => service::run(),
        "super" => supervisor::run(),
        "daemon" => daemon::run(),
        _ => {
            say!("usage: setlocd ensure [super]|service|sync|mirror|force|setstart 0|1|setpos <lat> <lng> [acc]|setrandom 0|1|status|install|modinstall <MODPATH> [OLD_MOD] [MODID]|sysdefault|webui status|cfg [--nosync]|pkgs user|system|sys|app|gpsz ...");
            1
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match args.first().map(String::as_str) {
        Some("gpsz") => cli::run_cli(&args[1..]),
        Some("webui") => webui::exec(&args[1..]),
        _ => bridge_cmd(&args),
    };
    std::process::exit(code);
}
