package io.gpsz.zygote;

import com.v7878.r8.annotations.DoNotObfuscate;
import com.v7878.r8.annotations.DoNotObfuscateType;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.zygisk.ZygoteLoader;

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

    private static boolean lsDisabled() {
        try {
            return new File(ZygoteLoader.getModuleDir(), "disable_ls").exists();
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean locDisabled() {
        try {
            return new File(ZygoteLoader.getModuleDir(), "disable_ls_loc").exists();
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean setDisabled() {
        try {
            return new File(ZygoteLoader.getModuleDir(), "disable_ls_set").exists();
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean rsDisabled() {
        try {
            return new File(ZygoteLoader.getModuleDir(), "disable_rs").exists();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Memuat libconfigcore.so dan liblochooks.so (Rust). Gagal memuat berarti jalur Java lama dipakai. */
    private static void loadRust() {
        if (rsDisabled()) return;
        ConfigNative.ensureLoaded();
        LocationNative.ensureLoaded();
    }

    private static boolean systemArmed() {
        try {
            return SysGuard.arm(
                    new File(ZygoteLoader.getModuleDir(), SysGuard.ENABLE),
                    new File(ZygoteLoader.getModuleDir(), SysGuard.PENDING),
                    new File(ZygoteLoader.getModuleDir(), SysGuard.BLOCKED)) == SysGuard.ARMED;
        } catch (Throwable t) {
            return false;
        }
    }

    @DoNotObfuscate
    @DoNotShrink
    public static void premain() {
        if (isSelf()) return;
        boolean ls = !lsDisabled();
        if (isSystemServer()) {
            if (ls && systemArmed()) {
                LsNative.setSysEnabled(true);
                LsNative.ensureLoaded();
                loadRust();
            }
            return;
        }
        if (ls) {
            LsNative.setLocEnabled(!locDisabled());
            LsNative.setSetEnabled(!setDisabled());
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