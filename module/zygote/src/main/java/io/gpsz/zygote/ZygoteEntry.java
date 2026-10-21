package io.gpsz.zygote;

import android.os.Build;
import android.util.Log;

import com.v7878.r8.annotations.DoNotObfuscate;
import com.v7878.r8.annotations.DoNotObfuscateType;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.zygisk.ZygoteLoader;

@DoNotObfuscateType
@DoNotShrinkType
public final class ZygoteEntry {
    static final String TAG = "GpsZ";
    static final String OWN_PACKAGE = "com.heryts.setloc";
    private static volatile boolean systemProcess;

    static void i(String m) {
        if (systemProcess) Log.i(TAG, m);
    }

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
            if ("android".equals(ZygoteLoader.getPackageName())) {
                systemProcess = true;
                i("injected into android (sdk " + Build.VERSION.SDK_INT + ")");
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
