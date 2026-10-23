pub const MAX_CLOCK_SKEW_MS: i64 = 5_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Mark {
    pub ns: i64,
    pub ms: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    Skip,
    Fix { ms: i64 },
}

pub fn is_fix(ns: i64, ms: i64, provider: Option<&str>) -> bool {
    ns != 0 && ms > 0 && provider.map_or(false, |p| !p.is_empty())
}

pub fn decide(ns: i64, ms: i64, provider: Option<&str>, mark: Option<Mark>, wall_now_ms: i64) -> Decision {
    if !is_fix(ns, ms, provider) {
        return Decision::Skip;
    }
    if mark == Some(Mark { ns, ms }) {
        return Decision::Skip;
    }
    let ms = if ms > wall_now_ms + MAX_CLOCK_SKEW_MS { wall_now_ms } else { ms };
    Decision::Fix { ms }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_incomplete_locations() {
        assert!(!is_fix(0, 1, Some("gps")));
        assert!(!is_fix(1, 0, Some("gps")));
        assert!(!is_fix(1, 1, None));
        assert!(!is_fix(1, 1, Some("")));
        assert!(is_fix(1, 1, Some("gps")));
    }

    #[test]
    fn skips_when_mark_matches() {
        let m = Mark { ns: 7, ms: 9 };
        assert_eq!(decide(7, 9, Some("gps"), Some(m), 100), Decision::Skip);
        assert_eq!(decide(7, 10, Some("gps"), Some(m), 100), Decision::Fix { ms: 10 });
        assert_eq!(decide(8, 9, Some("gps"), Some(m), 100), Decision::Fix { ms: 9 });
    }

    #[test]
    fn clamps_future_time_only_beyond_skew() {
        assert_eq!(decide(1, 105_000, Some("gps"), None, 100_000), Decision::Fix { ms: 105_000 });
        assert_eq!(decide(1, 105_001, Some("gps"), None, 100_000), Decision::Fix { ms: 100_000 });
    }

    #[test]
    fn clamped_value_is_stable_against_own_mark() {
        let Decision::Fix { ms } = decide(5, 900_000, Some("gps"), None, 1_000) else { panic!() };
        let mark = Mark { ns: 5, ms };
        assert_eq!(decide(5, ms, Some("gps"), Some(mark), 1_000), Decision::Skip);
    }
}
