use crate::timeline::{LINE_WIDTH, TICK_MS};

pub const PREF_EVERY: i64 = 10;
const MIN_SLEEP_MS: i64 = 20;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Plan {
    pub wait_ms: i64,
    pub auto_stop: bool,
    pub end_wait_ms: i64,
    pub line_width: i64,
    pub hold_end_ms: i64,
}

fn minutes_to_ms(min: f32) -> i64 {
    (min * 60_000.0_f32) as i64
}

pub fn plan(wait_start_min: f32, wait_end_min: f32, n: i64, chars: i64) -> Plan {
    let auto_stop = wait_end_min < 0.0;
    let end_wait_ms = if auto_stop { 0 } else { minutes_to_ms(wait_end_min) };
    let line_width = if chars == n * LINE_WIDTH as i64 { LINE_WIDTH as i64 } else { 0 };
    Plan {
        wait_ms: minutes_to_ms(wait_start_min),
        auto_stop,
        end_wait_ms,
        line_width,
        hold_end_ms: if auto_stop { 1 } else { end_wait_ms },
    }
}

pub fn tick_at(now: i64, t0: i64, n: i64) -> i64 {
    let k = ((now - t0) / TICK_MS).max(0);
    if k >= n - 1 {
        -1
    } else {
        k
    }
}

pub fn sleep_ms(now: i64, t0: i64, k: i64) -> i64 {
    (t0 + (k + 1) * TICK_MS - now).clamp(MIN_SLEEP_MS, TICK_MS)
}

pub fn end_at(t0: i64, n: i64, end_wait_ms: i64) -> i64 {
    t0 + (n - 1) * TICK_MS + end_wait_ms
}

pub fn pref_due(thin: bool, counter: i64) -> bool {
    !thin || counter % PREF_EVERY == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plan_auto_stop_when_end_wait_negative() {
        let p = plan(0.5, -1.0, 10, 480);
        assert_eq!(p, Plan { wait_ms: 30_000, auto_stop: true, end_wait_ms: 0, line_width: 48, hold_end_ms: 1 });
    }

    #[test]
    fn plan_holds_at_end() {
        let p = plan(0.0, 2.0, 10, 481);
        assert_eq!(p, Plan { wait_ms: 0, auto_stop: false, end_wait_ms: 120_000, line_width: 0, hold_end_ms: 120_000 });
    }

    #[test]
    fn plan_zero_end_wait_is_not_auto_stop() {
        let p = plan(0.0, 0.0, 3, 144);
        assert!(!p.auto_stop);
        assert_eq!(p.hold_end_ms, 0);
    }

    #[test]
    fn plan_nan_end_wait_is_not_auto_stop() {
        assert!(!plan(0.0, f32::NAN, 3, 144).auto_stop);
    }

    #[test]
    fn tick_index_progresses_and_finishes() {
        assert_eq!(tick_at(900, 1000, 5), 0);
        assert_eq!(tick_at(1000, 1000, 5), 0);
        assert_eq!(tick_at(1499, 1000, 5), 0);
        assert_eq!(tick_at(1500, 1000, 5), 1);
        assert_eq!(tick_at(2999, 1000, 5), 3);
        assert_eq!(tick_at(3000, 1000, 5), -1);
        assert_eq!(tick_at(90_000, 1000, 5), -1);
    }

    #[test]
    fn sleep_is_clamped() {
        assert_eq!(sleep_ms(1000, 1000, 0), 500);
        assert_eq!(sleep_ms(1490, 1000, 0), 20);
        assert_eq!(sleep_ms(1400, 1000, 0), 100);
        assert_eq!(sleep_ms(5000, 1000, 0), 20);
        assert_eq!(sleep_ms(0, 1000, 0), 500);
    }

    #[test]
    fn end_time_includes_hold() {
        assert_eq!(end_at(1000, 5, 0), 3000);
        assert_eq!(end_at(1000, 5, 60_000), 63_000);
    }

    #[test]
    fn pref_writes_are_thinned_only_when_asked() {
        assert!(pref_due(false, 3));
        assert!(pref_due(true, 0));
        assert!(!pref_due(true, 1));
        assert!(pref_due(true, 10));
    }
}
