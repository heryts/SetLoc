pub const K_LAT: i32 = 0;
pub const K_LNG: i32 = 1;
pub const K_ACC: i32 = 2;
pub const K_MOCK: i32 = 3;
pub const K_LAST_KNOWN: i32 = 4;
pub const K_SPD: i32 = 5;
pub const K_BRG: i32 = 6;
pub const K_ALT: i32 = 7;
pub const K_HAS_SPD: i32 = 8;
pub const K_HAS_ALT: i32 = 10;
pub const K_DIST: i32 = 11;
pub const K_BEARING_TO: i32 = 12;
pub const K_VACC: i32 = 13;
pub const K_HAS_MSL_ACC: i32 = 22;
pub const K_EXT: i32 = 30;
pub const K_NO_BOOL: i32 = 31;
pub const K_BSSID: i32 = 32;
pub const K_NULL: i32 = 33;
pub const K_CELL_UPDATE: i32 = 34;
pub const K_CELL_LIST_ARG: i32 = 35;
pub const K_DROP: i32 = 36;
pub const K_BT_DROP: i32 = 37;
pub const K_BT_INT: i32 = 38;
pub const K_BT_TRUE: i32 = 39;
pub const K_CREATE: i32 = 41;
pub const K_TOSTRING: i32 = 43;
pub const K_DELIVER: i32 = 44;
pub const K_WIFI_SCAN: i32 = 45;
pub const K_WIFI_SSID: i32 = 47;
pub const K_CELL_ALL: i32 = 48;
pub const K_CELL_LOC: i32 = 49;
pub const K_FEED_NMEA: i32 = 50;
pub const K_FEED_ANT: i32 = 52;
pub const K_SET: i32 = 54;
pub const K_PROVIDER_ENABLED: i32 = 55;
pub const K_GET_PROVIDERS: i32 = 56;
pub const K_LOCATION_ENABLED: i32 = 57;
pub const K_FEED_GPS: i32 = 58;
pub const K_GPS_STATUS: i32 = 59;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum Route {
    Create = 0,
    Set = 1,
    Providers = 2,
    LastKnown = 3,
    Deliver = 4,
    Snap = 5,
    SnapPair = 6,
    CellUpdate = 7,
    CellListArg = 8,
    Drop = 9,
    WifiScan = 10,
    WifiSsid = 11,
    CellAll = 12,
    CellLoc = 13,
    Feed = 14,
    GpsStatus = 15,
    Ext = 16,
    MockFalse = 17,
}

impl Route {
    pub fn code(self) -> i32 {
        self as i32
    }
}

pub fn is_snap_kind(kind: i32) -> bool {
    matches!(kind, K_LAT | K_LNG | K_ACC | K_SPD | K_BRG | K_ALT | K_TOSTRING)
        || (K_HAS_SPD..=K_HAS_ALT).contains(&kind)
        || (K_VACC..=K_HAS_MSL_ACC).contains(&kind)
}

pub fn route(kind: i32) -> Route {
    match kind {
        K_CREATE => Route::Create,
        K_SET => Route::Set,
        K_LOCATION_ENABLED | K_PROVIDER_ENABLED | K_GET_PROVIDERS => Route::Providers,
        K_MOCK => Route::MockFalse,
        K_LAST_KNOWN => Route::LastKnown,
        K_DELIVER => Route::Deliver,
        k if is_snap_kind(k) => Route::Snap,
        K_DIST | K_BEARING_TO => Route::SnapPair,
        K_CELL_UPDATE => Route::CellUpdate,
        K_CELL_LIST_ARG => Route::CellListArg,
        K_DROP => Route::Drop,
        K_WIFI_SCAN => Route::WifiScan,
        K_WIFI_SSID => Route::WifiSsid,
        K_CELL_ALL => Route::CellAll,
        K_CELL_LOC => Route::CellLoc,
        k if (K_FEED_NMEA..=K_FEED_ANT).contains(&k) || k == K_FEED_GPS => Route::Feed,
        K_GPS_STATUS => Route::GpsStatus,
        k if k >= K_EXT => Route::Ext,
        _ => Route::MockFalse,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum ExtValue {
    PassThrough = 0,
    Bssid = 1,
    Null = 2,
    Void = 3,
    Int0 = 4,
    True = 5,
    False = 6,
}

impl ExtValue {
    pub fn code(self) -> i32 {
        self as i32
    }
}

pub fn ext_value(kind: i32, hide_net: bool, hide_bt: bool) -> ExtValue {
    let bt = (K_BT_DROP..=K_BT_TRUE).contains(&kind);
    let hidden = kind == K_NO_BOOL || if bt { hide_bt } else { hide_net };
    if !hidden {
        return ExtValue::PassThrough;
    }
    match kind {
        K_BSSID => ExtValue::Bssid,
        K_NULL => ExtValue::Null,
        K_BT_DROP => ExtValue::Void,
        K_BT_INT => ExtValue::Int0,
        K_BT_TRUE => ExtValue::True,
        _ => ExtValue::False,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snap_kinds_match_java_set() {
        let snap: Vec<i32> = (0..70).filter(|k| is_snap_kind(*k)).collect();
        let mut want = vec![0, 1, 2, 5, 6, 7, 8, 9, 10, 43];
        want.extend(13..=22);
        want.sort();
        assert_eq!(snap, want);
    }

    #[test]
    fn codes_are_stable() {
        assert_eq!(Route::Create.code(), 0);
        assert_eq!(Route::Snap.code(), 5);
        assert_eq!(Route::Ext.code(), 16);
        assert_eq!(Route::MockFalse.code(), 17);
        assert_eq!(ExtValue::PassThrough.code(), 0);
        assert_eq!(ExtValue::Bssid.code(), 1);
        assert_eq!(ExtValue::Null.code(), 2);
        assert_eq!(ExtValue::Void.code(), 3);
        assert_eq!(ExtValue::Int0.code(), 4);
        assert_eq!(ExtValue::True.code(), 5);
        assert_eq!(ExtValue::False.code(), 6);
    }

    #[test]
    fn route_first_stage_kinds() {
        assert_eq!(route(K_CREATE), Route::Create);
        assert_eq!(route(K_SET), Route::Set);
        for k in [K_LOCATION_ENABLED, K_PROVIDER_ENABLED, K_GET_PROVIDERS] {
            assert_eq!(route(k), Route::Providers);
        }
    }

    #[test]
    fn route_gated_chain_order() {
        assert_eq!(route(K_LAST_KNOWN), Route::LastKnown);
        assert_eq!(route(K_DELIVER), Route::Deliver);
        assert_eq!(route(K_TOSTRING), Route::Snap);
        assert_eq!(route(K_DIST), Route::SnapPair);
        assert_eq!(route(K_BEARING_TO), Route::SnapPair);
        assert_eq!(route(K_MOCK), Route::MockFalse);
        assert_eq!(route(K_WIFI_SCAN), Route::WifiScan);
        assert_eq!(route(K_WIFI_SSID), Route::WifiSsid);
        assert_eq!(route(K_CELL_ALL), Route::CellAll);
        assert_eq!(route(K_CELL_LOC), Route::CellLoc);
        assert_eq!(route(K_GPS_STATUS), Route::GpsStatus);
        for k in [50, 51, 52, K_FEED_GPS] {
            assert_eq!(route(k), Route::Feed);
        }
        for k in [K_EXT, K_NO_BOOL, K_BSSID, K_NULL, K_BT_DROP, K_BT_INT, K_BT_TRUE] {
            assert_eq!(route(k), Route::Ext, "{k}");
        }
    }

    #[test]
    fn route_unused_ids_fall_to_ext_or_mock() {
        assert_eq!(route(40), Route::Ext);
        assert_eq!(route(53), Route::Ext);
        assert_eq!(route(23), Route::MockFalse);
        assert_eq!(route(29), Route::MockFalse);
    }

    #[test]
    fn ext_value_respects_hide_flags() {
        assert_eq!(ext_value(K_BSSID, true, false), ExtValue::Bssid);
        assert_eq!(ext_value(K_BSSID, false, true), ExtValue::PassThrough);
        assert_eq!(ext_value(K_BT_TRUE, false, true), ExtValue::True);
        assert_eq!(ext_value(K_BT_TRUE, true, false), ExtValue::PassThrough);
        assert_eq!(ext_value(K_BT_DROP, false, true), ExtValue::Void);
        assert_eq!(ext_value(K_BT_INT, false, true), ExtValue::Int0);
        assert_eq!(ext_value(K_NULL, true, false), ExtValue::Null);
        assert_eq!(ext_value(K_NO_BOOL, false, false), ExtValue::False);
        assert_eq!(ext_value(K_EXT, true, false), ExtValue::False);
    }
}
