pub struct Prev {
    pub lat: f64,
    pub lng: f64,
    pub acc: f32,
}

pub struct Parsed {
    pub enabled: bool,
    pub jitter: bool,
    pub playing: bool,
    pub tk: bool,
    pub lat: f64,
    pub lng: f64,
    pub acc: f32,
    pub spd: f32,
    pub brg: f32,
    pub alt: f64,
    pub altv: f32,
    pub rr: f32,
}

fn in_range(v: f64, limit: f64, fallback: f64) -> f64 {
    if v.abs() <= limit {
        v
    } else {
        fallback
    }
}

pub fn parse(s: Option<&str>, prev: &Prev) -> Parsed {
    let s = match s {
        None => "",
        Some(v) if v == "null" => "",
        Some(v) => v,
    };
    if s.is_empty() {
        return Parsed {
            enabled: false,
            jitter: false,
            playing: false,
            tk: false,
            lat: prev.lat,
            lng: prev.lng,
            acc: prev.acc,
            spd: 0.0,
            brg: f32::NAN,
            alt: 0.0,
            altv: 0.0,
            rr: 8.0,
        };
    }
    let mut en = false;
    let mut jit = false;
    let mut pl = false;
    let mut tk = false;
    let mut la = prev.lat;
    let mut lo = prev.lng;
    let mut ac = prev.acc;
    let mut sp = 0.0f32;
    let mut br = f32::NAN;
    let mut al = 0.0f64;
    let mut av = 0.0f32;
    let mut rad = 8.0f32;
    for kv in s.split(';') {
        let (k, v) = match kv.split_once('=') {
            Some(p) => p,
            None => continue,
        };
        match k {
            "enabled" => en = v == "1",
            "lat" => {
                if let Ok(x) = v.parse::<f64>() {
                    la = in_range(x, 90.0, la);
                }
            }
            "lng" => {
                if let Ok(x) = v.parse::<f64>() {
                    lo = in_range(x, 180.0, lo);
                }
            }
            "acc" => {
                if let Ok(x) = v.parse::<f32>() {
                    ac = in_range(x as f64, 1e5, ac as f64) as f32;
                }
            }
            "random" => jit = v == "1",
            "spd" => {
                if let Ok(x) = v.parse::<f32>() {
                    sp = (in_range(x as f64, 1e4, 0.0) as f32).max(0.0);
                }
            }
            "brg" => {
                if let Ok(b) = v.parse::<f32>() {
                    br = if b.is_nan() || b.is_infinite() || b < 0.0 { f32::NAN } else { b % 360.0 };
                }
            }
            "alt" => {
                if let Ok(x) = v.parse::<f64>() {
                    al = in_range(x, 1e5, 0.0);
                }
            }
            "altv" => {
                if let Ok(x) = v.parse::<f32>() {
                    av = (in_range(x as f64, 1e4, 0.0) as f32).max(0.0);
                }
            }
            "rr" => {
                if let Ok(x) = v.parse::<f32>() {
                    rad = (in_range(x as f64, 1e4, 8.0) as f32).max(0.0).min(100.0);
                }
            }
            "pl" => pl = v == "1",
            "tk" => tk = v == "1",
            _ => {}
        }
    }
    Parsed { enabled: en, jitter: jit, playing: pl, tk, lat: la, lng: lo, acc: ac, spd: sp, brg: br, alt: al, altv: av, rr: rad }
}

pub struct BeatState {
    pub seen: Option<String>,
    pub changed_at: i64,
    pub armed: bool,
    pub last_poll_at: i64,
}

impl Default for BeatState {
    fn default() -> Self {
        BeatState { seen: None, changed_at: 0, armed: false, last_poll_at: 0 }
    }
}

const GAP_MS: i64 = 3_000;
const FREEZE_MS: i64 = 10_000;
const OFF_MS: i64 = 60_000;

pub fn check_beat(beat: Option<&str>, playing: bool, now: i64, st: &mut BeatState) -> (i32, bool) {
    let gap = st.last_poll_at != 0 && now - st.last_poll_at > GAP_MS;
    st.last_poll_at = now;
    if !playing {
        st.armed = false;
        return (0, false);
    }
    if !st.armed || gap || beat != st.seen.as_deref() {
        st.armed = true;
        st.seen = beat.map(str::to_string);
        st.changed_at = now;
        return (0, false);
    }
    let stale = now - st.changed_at;
    if stale >= OFF_MS {
        (2, false)
    } else if stale >= FREEZE_MS {
        (1, true)
    } else {
        (0, false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_disables() {
        let p = parse(None, &Prev { lat: 1.0, lng: 2.0, acc: 10.0 });
        assert!(!p.enabled);
        assert_eq!(p.lat, 1.0);
    }

    #[test]
    fn parses_fields() {
        let p = parse(Some("enabled=1;lat=-7.5;lng=110.5;acc=12;random=1;spd=3;brg=90;alt=5;altv=2;rr=10"), &Prev { lat: 0.0, lng: 0.0, acc: 0.0 });
        assert!(p.enabled);
        assert!(p.jitter);
        assert_eq!(p.lat, -7.5);
        assert_eq!(p.lng, 110.5);
        assert_eq!(p.acc, 12.0);
        assert_eq!(p.spd, 3.0);
        assert_eq!(p.brg, 90.0);
        assert_eq!(p.alt, 5.0);
    }

    #[test]
    fn out_of_range_falls_back() {
        let p = parse(Some("lat=999"), &Prev { lat: 5.0, lng: 0.0, acc: 0.0 });
        assert_eq!(p.lat, 5.0);
    }

    #[test]
    fn beat_freezes_then_stops() {
        let mut st = BeatState::default();
        let (lvl, _) = check_beat(Some("1"), true, 0, &mut st);
        assert_eq!(lvl, 0);
        let (lvl, freeze) = check_beat(Some("1"), true, 10_001, &mut st);
        assert_eq!(lvl, 1);
        assert!(freeze);
        for t in (11_000..60_000).step_by(1_000) {
            check_beat(Some("1"), true, t, &mut st);
        }
        let (lvl, freeze) = check_beat(Some("1"), true, 60_001, &mut st);
        assert_eq!(lvl, 2);
        assert!(!freeze);
    }

    #[test]
    fn beat_gap_rearms() {
        let mut st = BeatState::default();
        check_beat(Some("1"), true, 1_000, &mut st);
        check_beat(Some("1"), true, 1_500, &mut st);
        let (lvl, freeze) = check_beat(Some("1"), true, 61_000, &mut st);
        assert_eq!(lvl, 0);
        assert!(!freeze);
    }

    #[test]
    fn beat_change_resets() {
        let mut st = BeatState::default();
        check_beat(Some("1"), true, 0, &mut st);
        let (lvl, _) = check_beat(Some("2"), true, 10_001, &mut st);
        assert_eq!(lvl, 0);
    }
}
