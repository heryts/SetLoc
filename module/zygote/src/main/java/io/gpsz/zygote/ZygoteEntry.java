package io.gpsz.zygote;

import com.v7878.r8.annotations.DoNotObfuscate;
import com.v7878.r8.annotations.DoNotObfuscateType;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.zygisk.ZygoteLoader;

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

    @DoNotObfuscate
    @DoNotShrink
    public static void premain() { }

    @DoNotObfuscate
    @DoNotShrink
    public static void main() {
        try {
            Native.load();
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