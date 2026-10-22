pub trait Clock {
    fn elapsed_ms(&self) -> i64;
    fn elapsed_ns(&self) -> i64;
    fn uptime_ms(&self) -> i64;
    fn wall_ms(&self) -> i64;
}

#[cfg(unix)]
mod sys {
    use super::Clock;

    #[repr(C)]
    struct Timespec {
        sec: i64,
        nsec: i64,
    }

    extern "C" {
        fn clock_gettime(id: i32, ts: *mut Timespec) -> i32;
    }

    const REALTIME: i32 = 0;
    const MONOTONIC: i32 = 1;
    const BOOTTIME: i32 = 7;

    fn ns(id: i32) -> i64 {
        let mut ts = Timespec { sec: 0, nsec: 0 };
        unsafe { clock_gettime(id, &mut ts) };
        ts.sec * 1_000_000_000 + ts.nsec
    }

    pub struct SysClock;

    impl Clock for SysClock {
        fn elapsed_ms(&self) -> i64 {
            ns(BOOTTIME) / 1_000_000
        }

        fn elapsed_ns(&self) -> i64 {
            ns(BOOTTIME)
        }

        fn uptime_ms(&self) -> i64 {
            ns(MONOTONIC) / 1_000_000
        }

        fn wall_ms(&self) -> i64 {
            ns(REALTIME) / 1_000_000
        }
    }
}

#[cfg(unix)]
pub use sys::SysClock;

pub struct ManualClock {
    pub elapsed_ns: std::cell::Cell<i64>,
    pub uptime_ms: std::cell::Cell<i64>,
    pub wall_ms: std::cell::Cell<i64>,
}

impl ManualClock {
    pub fn new(elapsed_ms: i64) -> Self {
        ManualClock {
            elapsed_ns: std::cell::Cell::new(elapsed_ms * 1_000_000),
            uptime_ms: std::cell::Cell::new(elapsed_ms),
            wall_ms: std::cell::Cell::new(1_700_000_000_000 + elapsed_ms),
        }
    }

    pub fn advance(&self, ms: i64) {
        self.elapsed_ns.set(self.elapsed_ns.get() + ms * 1_000_000);
        self.uptime_ms.set(self.uptime_ms.get() + ms);
        self.wall_ms.set(self.wall_ms.get() + ms);
    }

    pub fn sleep_gap(&self, ms: i64) {
        self.elapsed_ns.set(self.elapsed_ns.get() + ms * 1_000_000);
        self.wall_ms.set(self.wall_ms.get() + ms);
    }
}

impl Clock for ManualClock {
    fn elapsed_ms(&self) -> i64 {
        self.elapsed_ns.get() / 1_000_000
    }

    fn elapsed_ns(&self) -> i64 {
        self.elapsed_ns.get()
    }

    fn uptime_ms(&self) -> i64 {
        self.uptime_ms.get()
    }

    fn wall_ms(&self) -> i64 {
        self.wall_ms.get()
    }
}
