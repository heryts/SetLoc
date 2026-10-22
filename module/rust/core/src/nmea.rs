use crate::fixed::fixed;
use crate::gnss::{Snapshot, T_BDS, T_GAL, T_GLO, T_GPS};

pub struct NmeaFix {
    pub lat: f64,
    pub lng: f64,
    pub accuracy: f32,
    pub speed: Option<f32>,
    pub bearing: Option<f32>,
    pub altitude: Option<f64>,
}

fn talker(ty: i32) -> &'static str {
    match ty {
        T_GLO => "GL",
        T_GAL => "GA",
        T_BDS => "GB",
        _ => "GP",
    }
}

fn system_id(ty: i32) -> i32 {
    match ty {
        T_GLO => 2,
        T_GAL => 3,
        T_BDS => 4,
        _ => 1,
    }
}

fn signal_id(ty: i32) -> i32 {
    if ty == T_GAL {
        7
    } else {
        1
    }
}

fn nmea_id(ty: i32, svid: i32) -> i32 {
    if ty == T_GLO {
        64 + svid
    } else {
        svid
    }
}

fn round_f(x: f32) -> i32 {
    (x as f64 + 0.5).floor() as i32
}

pub fn sentence(body: &str) -> String {
    let cs = body.bytes().fold(0u8, |a, b| a ^ b);
    format!("${}*{:02X}\r\n", body, cs)
}

pub fn coord(v: f64, lat: bool) -> String {
    let t = (v.abs() * 60_000_000.0 + 0.5).floor() as i64;
    let m = t % 60_000_000;
    let deg = t / 60_000_000;
    if lat {
        format!("{:02}{:02}.{:06}", deg, m / 1_000_000, m % 1_000_000)
    } else {
        format!("{:03}{:02}.{:06}", deg, m / 1_000_000, m % 1_000_000)
    }
}

fn civil(days: i64) -> (i64, i64, i64) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

pub fn utc_fields(wall_ms: i64) -> (String, String) {
    let ms = wall_ms - wall_ms.rem_euclid(1000);
    let secs = ms.div_euclid(1000);
    let days = secs.div_euclid(86_400);
    let sod = secs.rem_euclid(86_400);
    let (y, m, d) = civil(days);
    (
        format!("{:02}{:02}{:02}.00", sod / 3600, sod % 3600 / 60, sod % 60),
        format!("{:02}{:02}{:02}", d, m, y % 100),
    )
}

pub fn build(s: &Snapshot, fix: &NmeaFix, wall_ms: i64) -> Vec<String> {
    let (hms, dmy) = utc_fields(wall_ms);
    let (lat, lng) = (fix.lat, fix.lng);
    let ns = if lat >= 0.0 { 'N' } else { 'S' };
    let ew = if lng >= 0.0 { 'E' } else { 'W' };
    let hdop = (fix.accuracy as f64 / 5.0).min(5.0).max(0.7);
    let vdop = hdop * 1.1;
    let pdop = (hdop * hdop + vdop * vdop).sqrt();
    let knots = fix.speed.map_or(0.0, |v| v as f64 * 1.943844);
    let course = fix.bearing.map_or(0.0, |v| v as f64);
    let alt = fix.altitude.unwrap_or(0.0);
    let used = s.used_count();
    let fix_quality = if used > 0 { 1 } else { 0 };
    let fix_type = if used >= 4 { 3 } else { 1 };
    let mut out = Vec::new();
    out.push(sentence(&format!(
        "GNGGA,{},{},{},{},{},{},{:02},{},{},M,{},M,,",
        hms,
        coord(lat, true),
        ns,
        coord(lng, false),
        ew,
        fix_quality,
        used,
        fixed(hdop, 1),
        fixed(alt, 1),
        fixed(0.0, 1)
    )));
    let groups = [T_GPS, T_GLO, T_GAL, T_BDS];
    for ty in groups {
        let ids: Vec<i32> = (0..s.n).filter(|i| s.ty[*i] == ty && s.used[*i]).map(|i| nmea_id(ty, s.svid[i])).collect();
        if ids.is_empty() {
            continue;
        }
        let slots: Vec<String> = (0..12).map(|i| ids.get(i).map_or(String::new(), |v| format!("{:02}", v))).collect();
        out.push(sentence(&format!(
            "GNGSA,A,{},{},{},{},{},{}",
            fix_type,
            slots.join(","),
            fixed(pdop, 1),
            fixed(hdop, 1),
            fixed(vdop, 1),
            system_id(ty)
        )));
    }
    for ty in groups {
        let idx: Vec<usize> = (0..s.n).filter(|i| s.ty[*i] == ty).collect();
        if idx.is_empty() {
            continue;
        }
        let pages = (idx.len() + 3) / 4;
        for p in 0..pages {
            let mut body = format!("{}GSV,{},{},{:02}", talker(ty), pages, p + 1, idx.len());
            for j in p * 4..idx.len().min(p * 4 + 4) {
                let i = idx[j];
                body.push_str(&format!(
                    ",{:02},{:02},{:03},{:02}",
                    nmea_id(ty, s.svid[i]),
                    round_f(s.elev[i]),
                    round_f(s.az[i]) % 360,
                    round_f(s.cn0[i])
                ));
            }
            body.push(',');
            body.push_str(&signal_id(ty).to_string());
            out.push(sentence(&body));
        }
    }
    out.push(sentence(&format!(
        "GNRMC,{},{},{},{},{},{},{},{},{},,,A",
        hms,
        if used > 0 { 'A' } else { 'V' },
        coord(lat, true),
        ns,
        coord(lng, false),
        ew,
        fixed(knots, 2),
        fixed(course, 2),
        dmy
    )));
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gnss::snapshot;

    fn checksum_ok(line: &str) -> bool {
        let body = line.trim_end().strip_prefix('$').unwrap();
        let (data, cs) = body.rsplit_once('*').unwrap();
        u8::from_str_radix(cs, 16).unwrap() == data.bytes().fold(0, |a, b| a ^ b)
    }

    fn fix() -> NmeaFix {
        NmeaFix { lat: -7.7829, lng: 110.3671, accuracy: 8.0, speed: Some(3.0), bearing: Some(45.0), altitude: Some(120.5) }
    }

    #[test]
    fn coordinates() {
        assert_eq!(coord(-7.7829, true), "0746.974000");
        assert_eq!(coord(110.3671, false), "11022.026000");
    }

    #[test]
    fn utc() {
        assert_eq!(utc_fields(0), ("000000.00".to_string(), "010170".to_string()));
        assert_eq!(utc_fields(1_700_000_000_999), ("221320.00".to_string(), "141123".to_string()));
        assert_eq!(utc_fields(951_782_400_000).1, "290200");
    }

    #[test]
    fn sentences_have_valid_checksums_and_order() {
        let s = snapshot(true, 200_000);
        let lines = build(&s, &fix(), 1_700_000_000_000);
        assert!(lines.iter().all(|l| l.ends_with("\r\n") && checksum_ok(l)));
        assert!(lines[0].starts_with("$GNGGA,221320.00,0746.974000,S,11022.026000,E,1,"));
        assert!(lines.last().unwrap().starts_with("$GNRMC,221320.00,A,0746.974000,S,11022.026000,E,5.83,45.00,141123,,,A*"));
        assert!(lines.iter().any(|l| l.starts_with("$GPGSV,")));
        assert!(lines.iter().any(|l| l.starts_with("$GNGSA,A,3,")));
    }

    #[test]
    fn gga_altitude_and_hdop() {
        let s = snapshot(true, 200_000);
        let lines = build(&s, &fix(), 1_700_000_000_000);
        let f: Vec<&str> = lines[0].split(',').collect();
        assert_eq!(f[8], "1.6");
        assert_eq!(f[9], "120.5");
        assert_eq!(f[10], "M");
    }

    #[test]
    fn unfixed_marks_void() {
        let s = snapshot(false, 200_000);
        let lines = build(&s, &fix(), 1_700_000_000_000);
        assert!(lines.last().unwrap().starts_with("$GNRMC,221320.00,V,"));
        assert!(!lines.iter().any(|l| l.starts_with("$GNGSA")));
    }

    #[test]
    fn gsv_pages_cover_all_sats() {
        let s = snapshot(true, 300_000);
        let lines = build(&s, &fix(), 1_700_000_000_000);
        let gps = (0..s.n).filter(|i| s.ty[*i] == T_GPS).count();
        let pages = lines.iter().filter(|l| l.starts_with("$GPGSV,")).count();
        assert_eq!(pages, (gps + 3) / 4);
    }
}
