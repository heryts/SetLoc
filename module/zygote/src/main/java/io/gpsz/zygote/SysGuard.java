package io.gpsz.zygote;

import java.io.File;

final class SysGuard {
    static final String ENABLE = "enable_ls_system";
    static final String PENDING = ".ls_sys_pending";
    static final String BLOCKED = "ls_system_blocked";

    static final int OFF = 0;
    static final int ARMED = 1;
    static final int BLOCKED_NOW = 2;
    static final int FAILED = 3;

    static int arm(File enable, File pending, File blocked) {
        try {
            if (!enable.exists()) return OFF;
            if (blocked.exists()) return BLOCKED_NOW;
            return pending.exists() ? ARMED : FAILED;
        } catch (Throwable t) {
            return FAILED;
        }
    }

    private SysGuard() { }
}
