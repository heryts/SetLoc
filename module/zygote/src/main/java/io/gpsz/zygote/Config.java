package io.gpsz.zygote;

import android.app.Application;
import android.provider.Settings;
import android.util.Log;

import com.v7878.unsafe.Reflection;

import java.lang.reflect.Method;

/**
 * Config channel: Settings.Global key "gpsz_cfg", written by root (see gpsz.sh), e.g.
 *   enabled=1;lat=-6.2;lng=106.8;acc=10;random=0
 * App processes cannot read /data/adb, but any app can read Settings.Global.
 */
final class Config {
    static final String KEY = "gpsz_cfg";
    /** Separate key so bridge.sh rewriting gpsz_cfg never drops it: "1" = system_server hooks allowed. */
    static final String KEY_SYS = "gpsz_sys";

    static volatile boolean enabled;
    static volatile boolean jitter;
    static volatile boolean system;
    static volatile double lat = Double.NaN;
    static volatile double lng = Double.NaN;
    static volatile float acc = 10f;

    static void parse(String s) {
        if (s == null || s.equals("null")) {
            enabled = false;
            return;
        }
        boolean en = false, jit = false;
        double la = lat, lo = lng;
        float ac = acc;
        for (String kv : s.split(";")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            String k = kv.substring(0, i).trim();
            String v = kv.substring(i + 1).trim();
            try {
                switch (k) {
                    case "enabled": en = v.equals("1"); break;
                    case "lat": la = Double.parseDouble(v); break;
                    case "lng": lo = Double.parseDouble(v); break;
                    case "acc": ac = Float.parseFloat(v); break;
                    case "random": jit = v.equals("1"); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) { }
        }
        lat = la; lng = lo; acc = ac; jitter = jit; enabled = en;
    }

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m;
            try {
                m = at.getDeclaredMethod("currentApplication");
            } catch (Throwable t) {
                m = Reflection.getDeclaredMethod(at, "currentApplication"); // hidden-API bypass
            }
            m.setAccessible(true);
            return (Application) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static void startPolling() { startPolling(null); }

    /** onReady (may be null) runs on the polling thread once the Application exists and gpsz_sys=1 was read. */
    static void startPolling(final Runnable onReady) {
        Thread th = new Thread(() -> {
            Application app = null;
            // Application appears shortly after main(); poll fast so the real-location leak window stays small
            for (int i = 0; i < 2400 && app == null; i++) { // ~2 min max
                app = currentApplication();
                if (app == null) sleep(50);
            }
            if (app == null) { Log.w(ZygoteEntry.TAG, "no Application, giving up"); return; }
            boolean readyRan = false;
            for (;;) {
                try {
                    parse(Settings.Global.getString(app.getContentResolver(), KEY));
                    system = "1".equals(Settings.Global.getString(app.getContentResolver(), KEY_SYS));
                    if (onReady != null && !readyRan && system) {
                        readyRan = true;
                        onReady.run();
                    }
                } catch (Throwable t) {
                    Log.w(ZygoteEntry.TAG, "config read failed: " + t);
                }
                sleep(1000);
            }
        }, "gpsz-config");
        th.setDaemon(true);
        th.start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private Config() { }
}
