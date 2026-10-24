use configcore::jitter;

pub const KIND_STATUS: i32 = 0;
pub const KIND_NMEA: i32 = 1;
pub const KIND_MEAS: i32 = 2;
pub const KIND_ANT: i32 = 3;

pub const PERIOD_MS: i64 = 1000;

pub const A_AGAIN: u32 = 1;
pub const A_OFF: u32 = 2;
pub const A_STOPPED: u32 = 4;
pub const A_STARTED: u32 = 8;
pub const A_FIRST_FIX: u32 = 16;
pub const A_STATUS: u32 = 32;
pub const A_FIXED: u32 = 64;
pub const A_SEND: u32 = 128;

const S_STARTED: u32 = 1;
const S_FIXED: u32 = 2;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Plan {
    pub initial_ms: i64,
    pub period_ms: i64,
    pub ttff_ms: i64,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Step {
    pub state: u32,
    pub acts: u32,
}

pub fn plan(seed: u64, kind: i32, minute: i64) -> Option<Plan> {
    let unit = |salt: i64| jitter::hash_unit(seed, salt, minute);
    let (initial_ms, ttff_ms) = match kind {
        KIND_STATUS => (150 + (unit(72) * 200.0) as i64, 700 + (unit(71) * 1500.0) as i64),
        KIND_NMEA => (1000 + (unit(73) * 200.0) as i64, 0),
        KIND_MEAS => (1000 + (unit(74) * 200.0) as i64, 0),
        KIND_ANT => (200 + (unit(75) * 200.0) as i64, 0),
        _ => return None,
    };
    Some(Plan { initial_ms, period_ms: PERIOD_MS, ttff_ms })
}

pub fn step(kind: i32, live: bool, state: u32, elapsed_ms: i64, ttff_ms: i64) -> Option<Step> {
    let keep = |acts: u32| Some(Step { state, acts });
    match kind {
        KIND_STATUS => {
            if !live {
                return keep(A_STOPPED | A_OFF);
            }
            if state & S_STARTED == 0 {
                return Some(Step { state: state | S_STARTED, acts: A_STARTED | A_AGAIN });
            }
            let fixed = elapsed_ms >= ttff_ms;
            let mut next = state;
            let mut acts = A_STATUS | A_AGAIN;
            if fixed {
                acts |= A_FIXED;
                if state & S_FIXED == 0 {
                    next |= S_FIXED;
                    acts |= A_FIRST_FIX;
                }
            }
            Some(Step { state: next, acts })
        }
        KIND_NMEA | KIND_MEAS => keep(if live { A_SEND | A_AGAIN } else { A_OFF }),
        KIND_ANT => keep(if live { A_SEND } else { 0 }),
        _ => None,
    }
}

pub fn pack_step(s: Step) -> i64 {
    ((s.state as i64) << 8) | s.acts as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    const SEED: u64 = 0x1234_5678_9abc_def0;

    #[test]
    fn plan_ranges_match_per_kind_windows() {
        for minute in [0, 1, 777, 1_234_567] {
            let s = plan(SEED, KIND_STATUS, minute).unwrap();
            assert!((150..350).contains(&s.initial_ms));
            assert!((700..2200).contains(&s.ttff_ms));
            assert_eq!(s.period_ms, 1000);
            let n = plan(SEED, KIND_NMEA, minute).unwrap();
            let m = plan(SEED, KIND_MEAS, minute).unwrap();
            assert!((1000..1200).contains(&n.initial_ms) && (1000..1200).contains(&m.initial_ms));
            let a = plan(SEED, KIND_ANT, minute).unwrap();
            assert!((200..400).contains(&a.initial_ms));
        }
        assert_eq!(plan(SEED, 9, 0), None);
        assert_eq!(plan(SEED, -1, 0), None);
    }

    #[test]
    fn plan_is_stable_within_a_minute_and_uses_the_legacy_salts() {
        assert_eq!(plan(SEED, KIND_STATUS, 5), plan(SEED, KIND_STATUS, 5));
        let p = plan(SEED, KIND_STATUS, 42).unwrap();
        assert_eq!(p.initial_ms, 150 + (jitter::hash_unit(SEED, 72, 42) * 200.0) as i64);
        assert_eq!(p.ttff_ms, 700 + (jitter::hash_unit(SEED, 71, 42) * 1500.0) as i64);
        let differs = (0..50).any(|m| plan(SEED, KIND_STATUS, m).unwrap().ttff_ms != p.ttff_ms);
        assert!(differs);
    }

    #[test]
    fn status_sequence_started_then_status_then_first_fix_once() {
        let ttff = 1000;
        let s1 = step(KIND_STATUS, true, 0, 0, ttff).unwrap();
        assert_eq!(s1.acts, A_STARTED | A_AGAIN);
        let s2 = step(KIND_STATUS, true, s1.state, 500, ttff).unwrap();
        assert_eq!(s2.acts, A_STATUS | A_AGAIN);
        assert_eq!(s2.state, s1.state);
        let s3 = step(KIND_STATUS, true, s2.state, 1000, ttff).unwrap();
        assert_eq!(s3.acts, A_STATUS | A_AGAIN | A_FIXED | A_FIRST_FIX);
        let s4 = step(KIND_STATUS, true, s3.state, 2000, ttff).unwrap();
        assert_eq!(s4.acts, A_STATUS | A_AGAIN | A_FIXED);
        assert_eq!(s4.state, s3.state);
    }

    #[test]
    fn status_stops_when_not_live_at_any_stage() {
        for state in [0, 1, 3] {
            let s = step(KIND_STATUS, false, state, 5000, 1000).unwrap();
            assert_eq!(s.acts, A_STOPPED | A_OFF);
            assert_eq!(s.state, state);
        }
    }

    #[test]
    fn nmea_and_meas_repeat_while_live_and_call_off_when_not() {
        for k in [KIND_NMEA, KIND_MEAS] {
            assert_eq!(step(k, true, 0, 0, 0).unwrap().acts, A_SEND | A_AGAIN);
            assert_eq!(step(k, false, 0, 0, 0).unwrap().acts, A_OFF);
        }
    }

    #[test]
    fn antenna_sends_once_and_never_repeats() {
        assert_eq!(step(KIND_ANT, true, 0, 0, 0).unwrap().acts, A_SEND);
        assert_eq!(step(KIND_ANT, false, 0, 0, 0).unwrap().acts, 0);
    }

    #[test]
    fn unknown_kind_has_no_step_and_pack_roundtrips() {
        assert_eq!(step(7, true, 0, 0, 0), None);
        let p = pack_step(Step { state: 3, acts: A_STATUS | A_AGAIN | A_FIXED | A_FIRST_FIX });
        assert_eq!(p & 0xff, (A_STATUS | A_AGAIN | A_FIXED | A_FIRST_FIX) as i64);
        assert_eq!(p >> 8, 3);
    }
}
