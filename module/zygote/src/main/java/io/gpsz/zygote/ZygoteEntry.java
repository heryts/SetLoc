package io.gpsz.zygote;

import com.v7878.r8.annotations.DoNotObfuscate;
import com.v7878.r8.annotations.DoNotObfuscateType;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.zygisk.ZygoteLoader;

import android.util.Log;

import java.io.File;

@DoNotObfuscateType
@DoNotShrinkType
public final class ZygoteEntry {
    static final String OWN_PACKAGE = "com.heryts.setloc";

    static boolean isSelf() {
        try {
            return OWN_PACKAGE.equals(ZygoteLoader.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isSystemServer() {
        try {
            return ZygoteLoader.PACKAGE_SYSTEM_SERVER.equals(ZygoteLoader.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean disabled(String marker) {
        try {
            return new File(ZygoteLoader.getModuleDir(), marker).exists();
        } catch (Throwable t) {
            return true;
        }
    }

    private static void loadRust() {
        if (disabled("disable_rs")) return;
        ConfigNative.ensureLoaded();
        LocationNative.ensureLoaded();
    }

    private static int systemArm() {
        try {
            return SysGuard.arm(
                    new File(ZygoteLoader.getModuleDir(), SysGuard.ENABLE),
                    new File(ZygoteLoader.getModuleDir(), SysGuard.PENDING),
                    new File(ZygoteLoader.getModuleDir(), SysGuard.BLOCKED));
        } catch (Throwable t) {
            return SysGuard.FAILED;
        }
    }

    @DoNotObfuscate
    @DoNotShrink
    public static void premain() {
        if (isSelf()) return;
        boolean ls = !disabled("disable_ls");
        if (isSystemServer()) {
            int arm = ls ? systemArm() : SysGuard.OFF;
            boolean ready = false;
            if (arm == SysGuard.ARMED) {
                LsNative.setSysEnabled(true);
                ready = LsNative.ensureLoaded();
                loadRust();
            }
            Log.i("GpsZ", "system hook premain arm=" + arm + " lsplant=" + ready + " err=" + LsNative.loadError());
            return;
        }
        if (ls) {
            LsNative.setLocEnabled(!disabled("disable_ls_loc"));
            LsNative.setSetEnabled(!disabled("disable_ls_set"));
            LsNative.ensureLoaded();
        }
        loadRust();
    }

    @DoNotObfuscate
    @DoNotShrink
    public static void main() {
        try {
            if ("android".equals(ZygoteLoader.getPackageName())) {
                Config.startPolling(SystemHooks::install);
                return;
            }
            LocationHooks.install();
            Config.startPolling(null);
        } catch (Throwable ignored) { }
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private ZygoteEntry() { }
}