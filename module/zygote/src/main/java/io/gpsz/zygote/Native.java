package io.gpsz.zygote;

import android.util.Log;

import com.v7878.r8.annotations.DoNotObfuscate;
import com.v7878.r8.annotations.DoNotObfuscateType;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.unsafe.Reflection;
import com.v7878.zygisk.ZygoteLoader;

import java.lang.reflect.Method;

@DoNotObfuscateType
@DoNotShrinkType
final class Native {
    private static final String LIB = "/data/adb/modules/setloc_zygisk/native/libgpsz.so";
    private static final String TAG = "GpsZ";
    private static final int MAGIC = 0x6770737a;
    private static int state;

    @DoNotObfuscate
    @DoNotShrink
    static native int probe();

    @DoNotObfuscate
    @DoNotShrink
    static native double hashUnit(long a, long b);

    @DoNotObfuscate
    @DoNotShrink
    static native float networkAccuracy(long elapsedMs, float gpsAcc);

    static synchronized boolean load() {
        if (state == 0) {
            String err = null;
            try {
                System.load(LIB);
                if (probe() != MAGIC) {
                    err = "probe";
                } else if (hashUnit(1, 2) != Config.hashUnit(1, 2)) {
                    err = "hashUnit";
                } else if (Math.abs(networkAccuracy(123_456L, 8f) - Config.networkAccuracyAt(123_456L, 8f)) > 1e-4f) {
                    err = "networkAccuracy";
                }
            } catch (Throwable t) {
                err = t.toString();
            }
            state = err == null ? 1 : -1;
            report(err);
        }
        return state > 0;
    }

    private static void report(String err) {
        try {
            String pkg = ZygoteLoader.getPackageName();
            if (!"android".equals(pkg) && !debug()) return;
            Log.i(TAG, "native " + (err == null ? "ok" : "fail " + err) + " pkg=" + pkg);
        } catch (Throwable ignored) { }
    }

    private static boolean debug() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m;
            try {
                m = sp.getDeclaredMethod("get", String.class);
            } catch (Throwable t) {
                m = Reflection.getDeclaredMethod(sp, "get", String.class);
            }
            m.setAccessible(true);
            return "1".equals(m.invoke(null, "debug.gpsz.native"));
        } catch (Throwable t) {
            return false;
        }
    }

    private Native() { }
}
