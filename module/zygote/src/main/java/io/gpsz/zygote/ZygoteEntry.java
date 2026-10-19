package io.gpsz.zygote;

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
    static volatile boolean verbose = false;

    static void i(String m) {
        if (verbose) Log.i(TAG, m);
    }

    static void w(String m) {
        if (verbose) Log.w(TAG, m);
    }

    static void e(String m, Throwable t) {
        if (verbose) Log.e(TAG, m, t);
    }

    @DoNotObfuscate
    @DoNotShrink
    public static void premain() { }

    @DoNotObfuscate
    @DoNotShrink
    public static void main() {
        try {
            String pkg = ZygoteLoader.getPackageName();
            if ("android".equals(pkg)) {
                verbose = true;
                i("injected into " + pkg + " (sdk " + android.os.Build.VERSION.SDK_INT + ")");
                Config.startPolling(SystemHooks::install);
                return;
            }
            LocationHooks.install();
            Config.startPolling(null);
        } catch (Throwable t) {
            e("init failed", t);
        }
    }

    private static final java.util.Set<String> SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void warnOnce(String where, Throwable t) {
        String msg = where + ": " + t;
        if (verbose && SEEN.size() < 64 && SEEN.add(msg)) Log.w(TAG, msg);
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private ZygoteEntry() { }
}
