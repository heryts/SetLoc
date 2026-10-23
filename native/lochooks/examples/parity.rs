use configcore::{codec::SALT, jitter};
use lochooks::fix::{self, Pt};
use lochooks::snapshot::{self, Inputs};
use std::fs;

fn line(k: &str, v: &[String]) -> String {
    let mut s = k.to_string();
    for x in v {
        s.push('|');
        s.push_str(x);
    }
    s
}

fn f(v: f64) -> String {
    format!("{v:?}")
}

fn fl(v: f32) -> String {
    format!("{:?}", v as f64)
}

fn pt(lat: f64, lng: f64, at: i64, spd: f32, brg: f32, alt: f64, jit: f32) -> Pt {
    Pt { lat, lng, at, spd, brg, alt, acc_jit: jit }
}

fn jfloat(v: f32) -> String {
    if v.is_nan() {
        "NaN".into()
    } else {
        format!("{:?}", v as f64)
    }
}

fn main() {
    let seed = jitter::seed(SALT);
    let mut out: Vec<String> = Vec::new();
    let as_: [i64; 9] = [0, 1, 11, 20, 41, 100, 101, 901, -5];
    let bs: [i64; 7] = [0, 1, 2, 17, 1000, 123456789012, -3];
    for a in as_ {
        for b in bs {
            out.push(line("h", &[a.to_string(), b.to_string(), f(jitter::hash_unit(seed, a, b))]));
        }
    }
    for s in [31i64, 51] {
        for b in [0i64, 1, 50, 99999, 10000000001] {
            out.push(line("g", &[s.to_string(), b.to_string(), f(jitter::gauss(seed, s, b))]));
        }
    }
    let ts: [i64; 11] = [0, 1, 999, 1000, 29999, 30000, 35001, 123456, 500000, 36000123, 99999999];
    let lats = [-7.7f64, 0.0, 89.9999999, 45.5];
    for t in ts {
        for la in lats {
            let (a, b) = jitter::network_offset(seed, t, la);
            out.push(line("net", &[t.to_string(), f(la), f(a), f(b)]));
        }
        let n10 = jitter::network_accuracy(seed, t, 10.0) as f32;
        let n1 = jitter::network_accuracy(seed, t, 1.0) as f32;
        out.push(line("nacc", &[t.to_string(), fl(n10), fl(n1)]));
        out.push(line("alt", &[t.to_string(), f(jitter::altitude_now(seed, t / 1000, 100.0, 3.0))]));
        let (s, m, x) = jitter::gnss_extras(seed, t / 1000);
        out.push(line("gnss", &[t.to_string(), s.to_string(), m.to_string(), x.to_string()]));
    }
    for j in [0f32, 0.35, -0.7, 1.0, -1.05] {
        out.push(line(
            "acc",
            &[jfloat(j), fl(jitter::acc_now(10.0, j)), fl(jitter::v_acc(10.0, j)), fl(jitter::s_acc(j)), fl(jitter::b_acc(j))],
        ));
    }
    let provs = ["gps", "network", "fused", "passive"];
    let vars: [[f32; 3]; 5] = [[0.0, f32::NAN, 0.2], [5.0, 90.0, 0.2], [0.3, 10.0, -0.6], [1.0, f32::NAN, -10.0], [33.0, 359.5, 0.9]];
    for pr in provs {
        for t in [0i64, 77000, 1234567] {
            for v in vars {
                let a = fix::apply(pr, &pt(-7.7, 110.4, t, v[0], v[1], 100.0, v[2]), 10.0, seed, t);
                let m = a.motion;
                let (sat, mean, mx) = a.extras.unwrap_or((-1, -1, -1));
                let (has_alt, alt, has_spd, spd, has_v, vacc, has_s, sacc) = match m {
                    Some(m) => (true, m.alt, true, m.spd, true, m.v_acc, true, m.s_acc),
                    None => (false, 0.0, false, 0.0, false, 0.0, false, 0.0),
                };
                let (has_b, brg, has_ba, bacc) = match m.and_then(|m| m.bearing) {
                    Some((b, ba)) => (true, b, true, ba),
                    None => (false, 0.0, false, 0.0),
                };
                let nz = |has: bool, v: f64| if has { v } else { 0.0 };
                let _ = nz;
                out.push(format!(
                    "fix|{pr}|{t}|{}|{}|{}|{:?}|{:?}|{:?}|{has_alt}|{:?}|{has_spd}|{:?}|{has_v}|{:?}|{has_s}|{:?}|{has_b}|{:?}|{has_ba}|{:?}|{sat}|{mean}|{mx}",
                    jfloat(v[0]),
                    jfloat(v[1]),
                    jfloat(v[2]),
                    a.lat,
                    a.lng,
                    a.acc as f64,
                    alt,
                    spd as f64,
                    vacc as f64,
                    sacc as f64,
                    brg as f64,
                    bacc as f64,
                ));
            }
        }
    }
    type Cfg = (f64, f64, bool, f32, f32, f64, f32, f32);
    let cfgs: [Cfg; 4] = [
        (-7.7, 110.4, false, 8.0, 0.0, 100.0, 0.0, f32::NAN),
        (-7.7, 110.4, true, 8.0, 5.0, 100.0, 3.0, 45.0),
        (89.99999, 179.99999, true, 30.0, 0.0, 5.0, 1.0, f32::NAN),
        (0.0, -179.99999, true, 50.0, 0.0, 5.0, 0.0, f32::NAN),
    ];
    for (ci, c) in cfgs.iter().enumerate() {
        let mut t: i64 = 700000;
        for i in 0..12 {
            let p = snapshot::renew(&Inputs { lat: c.0, lng: c.1, spd: c.4, brg: c.7, alt: c.5, alt_var: c.6, random: c.2, rr: c.3, now_ms: t, seed });
            out.push(line("snap", &[ci.to_string(), t.to_string(), f(p.lat), f(p.lng), f(p.alt), fl(p.acc_jit), fl(p.spd)]));
            t += 51 + i * 313;
        }
    }
    let path = std::env::args().nth(1).expect("java dump path");
    let java = fs::read_to_string(&path).expect("read java dump");
    let jl: Vec<&str> = java.lines().collect();
    let mut bad = 0;
    if jl.len() != out.len() {
        println!("jumlah baris beda: java {} rust {}", jl.len(), out.len());
        bad += 1;
    }
    for (i, (a, b)) in jl.iter().zip(out.iter()).enumerate() {
        let fa: Vec<&str> = a.split('|').collect();
        let fb: Vec<&str> = b.split('|').collect();
        let mut ok = fa.len() == fb.len();
        if ok {
            for (fi, (x, y)) in fa.iter().zip(fb.iter()).enumerate() {
                if x == y {
                    continue;
                }
                match (x.parse::<f64>(), y.parse::<f64>()) {
                    (Ok(p), Ok(q)) => {
                        let scale = p.abs().max(q.abs()).max(1.0);
                        let tol = if fa[0] == "nacc" || fa[0] == "fix" && fa[1] == "network" && fi == 8 { 2e-6 * scale } else { 1e-12 * scale };
                        if (p - q).abs() > tol {
                            ok = false;
                        }
                    }
                    _ => ok = false,
                }
            }
        }
        if !ok {
            bad += 1;
            if bad <= 15 {
                println!("BEDA baris {i}\n  java: {a}\n  rust: {b}");
            }
        }
    }
    println!("{} baris dibandingkan, {} beda", out.len(), bad);
    if bad > 0 {
        std::process::exit(1);
    }
}
