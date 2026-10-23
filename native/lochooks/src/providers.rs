pub const GPS: &str = "gps";
pub const NETWORK: &str = "network";
pub const FUSED: &str = "fused";
pub const SPOOF: [&str; 3] = [GPS, NETWORK, FUSED];

pub fn is_spoof(provider: &str) -> bool {
    SPOOF.contains(&provider)
}

pub fn active(parsing: bool, enabled: bool, has_pos: bool, sys_heartbeat: bool) -> bool {
    !parsing && enabled && has_pos && !sys_heartbeat
}

pub fn merge(existing: &[String]) -> Vec<String> {
    let mut out: Vec<String> = existing.to_vec();
    for p in SPOOF {
        if !out.iter().any(|e| e == p) {
            out.push(p.to_string());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merge_appends_missing_in_order() {
        let got = merge(&["passive".to_string(), "network".to_string()]);
        assert_eq!(got, vec!["passive", "network", "gps", "fused"]);
    }

    #[test]
    fn merge_keeps_duplicates_and_complete_lists() {
        let src: Vec<String> = ["gps", "gps", "network", "fused"].iter().map(|s| s.to_string()).collect();
        assert_eq!(merge(&src), src);
    }

    #[test]
    fn active_requires_all_conditions() {
        assert!(active(false, true, true, false));
        assert!(!active(true, true, true, false));
        assert!(!active(false, false, true, false));
        assert!(!active(false, true, false, false));
        assert!(!active(false, true, true, true));
    }

    #[test]
    fn spoof_membership() {
        assert!(is_spoof("fused"));
        assert!(!is_spoof("passive"));
        assert!(!is_spoof(""));
    }
}
