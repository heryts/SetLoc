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

    @DoNotObfuscate
    @DoNotShrink
    public static void premain() { }

    @DoNotObfuscate
    @DoNotShrink
    public static void main() {
        try {
            Log.i(TAG, "injected into " + ZygoteLoader.getPackageName()
                    + " (sdk " + android.os.Build.VERSION.SDK_INT + ")");
            if ("android".equals(ZygoteLoader.getPackageName())) {
                Config.startPolling(SystemHooks::install);
                return;
            }
            LocationHooks.install();
            Config.startPolling();
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
        }
    }

    private ZygoteEntry() { }
}
