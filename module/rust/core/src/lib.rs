pub mod keys {
    include!(concat!(env!("OUT_DIR"), "/keys.rs"));
}
pub mod clock;
pub mod codec;
pub mod fixed;
pub mod fix;
pub mod gnss;
pub mod nmea;
pub mod rng;
pub mod state;
pub mod track;
pub mod wifi;
