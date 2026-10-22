use std::ffi::c_void;

pub const MAGIC: i32 = 0x6770_737a;

#[export_name = "Java_io_gpsz_zygote_Native_probe"]
pub extern "system" fn probe(_env: *mut c_void, _cls: *mut c_void) -> i32 {
    MAGIC
}

#[export_name = "Java_io_gpsz_zygote_Native_hashUnit"]
pub extern "system" fn hash_unit(_env: *mut c_void, _cls: *mut c_void, a: i64, b: i64) -> f64 {
    gpsz_core::rng::hash_unit(a, b)
}

#[export_name = "Java_io_gpsz_zygote_Native_networkAccuracy"]
pub extern "system" fn network_accuracy(_env: *mut c_void, _cls: *mut c_void, elapsed_ms: i64, gps_acc: f32) -> f32 {
    gpsz_core::state::network_accuracy(elapsed_ms, gps_acc)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ptr::null_mut;

    #[test]
    fn probe_returns_magic() {
        assert_eq!(probe(null_mut(), null_mut()), MAGIC);
    }

    #[test]
    fn hash_unit_matches_core() {
        for i in 0..64 {
            assert_eq!(hash_unit(null_mut(), null_mut(), i, i * 3 + 1), gpsz_core::rng::hash_unit(i, i * 3 + 1));
        }
    }

    #[test]
    fn network_accuracy_matches_core() {
        assert_eq!(
            network_accuracy(null_mut(), null_mut(), 123_456, 8.0),
            gpsz_core::state::network_accuracy(123_456, 8.0)
        );
    }
}
