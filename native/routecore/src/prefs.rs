pub const KM_PER_MILE: f32 = 1.609344;

pub const ROUTE_NONE: i32 = 0;
pub const ROUTE_LIVE: i32 = 1;
pub const ROUTE_ENDED_OFF: i32 = 2;

pub fn speed_var(unit: i32, speed_kmh: f32, raw: f32) -> f32 {
    if raw < 0.0 {
        return -1.0;
    }
    match unit {
        0 => speed_kmh * raw / 100.0,
        2 => raw * KM_PER_MILE,
        _ => raw,
    }
}

pub fn route_end(t0_cs: i64, n: i64, tick_ms: i64, hold_ms: i64) -> i64 {
    t0_cs * 10 + (n - 1) * tick_ms + hold_ms
}

#[allow(clippy::too_many_arguments)]
pub fn route_state(play: bool, t0_cs: i64, n: i64, tick_ms: i64, hold_ms: i64, boot_saved: i64, boot_now: i64, now_ms: i64) -> i32 {
    if !play || boot_saved != boot_now {
        return ROUTE_NONE;
    }
    let end = route_end(t0_cs, n, tick_ms, hold_ms);
    if now_ms < end {
        ROUTE_LIVE
    } else if hold_ms > 0 {
        ROUTE_ENDED_OFF
    } else {
        ROUTE_NONE
    }
}

pub fn cfg_off(lat: f64, lng: f64, acc: f32) -> String {
    format!("enabled=0;lat={:.6};lng={:.6};acc={};random=0", lat, lng, acc)
}

pub fn coord(v: f64) -> String {
    format!("{:.7}", v)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn speed_var_by_unit() {
        assert_eq!(speed_var(0, 50.0, 10.0), 5.0);
        assert_eq!(speed_var(1, 50.0, 4.0), 4.0);
        assert_eq!(speed_var(2, 50.0, 10.0), 10.0 * KM_PER_MILE);
        assert_eq!(speed_var(7, 50.0, 4.0), 4.0);
        assert_eq!(speed_var(0, 50.0, -1.0), -1.0);
        assert_eq!(speed_var(2, 50.0, -0.5), -1.0);
        assert_eq!(speed_var(0, 50.0, 0.0), 0.0);
    }

    #[test]
    fn route_end_adds_hold() {
        assert_eq!(route_end(100, 5, 500, 0), 3000);
        assert_eq!(route_end(100, 5, 500, 60_000), 63_000);
    }

    #[test]
    fn route_state_matches_live_and_ended() {
        let st = |play, hold, saved, now| route_state(play, 100, 5, 500, hold, saved, 7, now);
        assert_eq!(st(true, 0, 7, 2999), ROUTE_LIVE);
        assert_eq!(st(true, 0, 7, 3000), ROUTE_NONE);
        assert_eq!(st(true, 1, 7, 3000), ROUTE_LIVE);
        assert_eq!(st(true, 1, 7, 3001), ROUTE_ENDED_OFF);
        assert_eq!(st(true, 1, 7, 9999), ROUTE_ENDED_OFF);
        assert_eq!(st(false, 1, 7, 3000), ROUTE_NONE);
        assert_eq!(st(true, 1, 6, 3000), ROUTE_NONE);
        assert_eq!(st(true, 1, 6, 10), ROUTE_NONE);
    }

    #[test]
    fn cfg_off_line() {
        assert_eq!(cfg_off(-7.25, 110.5, 10.0), "enabled=0;lat=-7.250000;lng=110.500000;acc=10;random=0");
        assert_eq!(cfg_off(1.0, 2.0, 12.5), "enabled=0;lat=1.000000;lng=2.000000;acc=12.5;random=0");
    }

    #[test]
    fn coord_has_seven_decimals() {
        assert_eq!(coord(-7.7829), "-7.7829000");
        assert_eq!(coord(110.0), "110.0000000");
    }
}
