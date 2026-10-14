package io.gpsz.zygote;

import android.app.Application;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.v7878.unsafe.Reflection;

import java.lang.reflect.Method;
import java.util.Random;

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
    /** Kecepatan (m/s), arah (derajat 0..360, NaN = tidak ada arah), ketinggian (m). Diisi app lewat bridge.sh. */
    static volatile float spd = 0f;
    static volatile float brg = Float.NaN;
    static volatile double alt = 0.0;
    /** Variasi altitude +- meter (0 = konstan), diterapkan di [altitudeNow]. */
    static volatile float altVar = 0f;
    /** Radius pergeseran acak posisi (meter), dipakai bila random=1. */
    static volatile float rr = 8f;
    /** Cap waktu (epoch ms) saat app menulis posisi terakhir; dipakai hanya untuk mengukur latensi (0 = tidak ada). */
    static volatile long ts = 0;
    private static final Random RND = new Random();
    private static volatile double altNoise = 0.0;
    private static volatile long altAt = 0;

    /** Altitude + derau halus (+-altVar) yang berubah ~1 detik sekali, seperti GPS asli. */
    static double altitudeNow() {
        float v = altVar;
        if (v <= 0f) return alt;
        long now = SystemClock.elapsedRealtime();
        if (now - altAt >= 1000) {
            altAt = now;
            double target = (RND.nextDouble() * 2.0 - 1.0) * v;
            double n = altNoise + (target - altNoise) * 0.5;
            altNoise = Math.max(-v, Math.min(v, n));
        }
        return alt + altNoise;
    }

    // ---- pengukuran latensi app -> modul (lihat `logcat -s GpsZ`): umur data saat modul membacanya
    private static long lastTs = 0, lagN = 0, lagSum = 0, lagMax = 0;

    private static void recordLag() {
        long t = ts;
        if (t <= 0 || t == lastTs) return;
        lastTs = t;
        long lag = System.currentTimeMillis() - t;
        if (lag < 0 || lag > 60000) return;          // cap lama/basi atau jam tidak cocok: abaikan
        lagN++; lagSum += lag; if (lag > lagMax) lagMax = lag;
        if (lagN >= 10) {
            Log.i(ZygoteEntry.TAG, "lag app->modul: rata2=" + (lagSum / lagN) + " ms, maks=" + lagMax + " ms (n=" + lagN + ")");
            lagN = 0; lagSum = 0; lagMax = 0;
        }
    }

    static void parse(String s) {
        if (s == null || s.equals("null")) {
            enabled = false;
            return;
        }
        boolean en = false, jit = false;
        double la = lat, lo = lng;
        float ac = acc;
        // field gerak direset tiap parse: kunci yang tidak ada = nilai awal (diam, tanpa arah, alt 0)
        float sp = 0f, br = Float.NaN, av = 0f, rad = 8f;
        long tsv = 0;
        double al = 0.0;
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
                    case "spd": sp = Math.max(0f, Float.parseFloat(v)); break;
                    case "brg": {
                        float b = Float.parseFloat(v);
                        br = (Float.isNaN(b) || b < 0f) ? Float.NaN : b % 360f;
                        break;
                    }
                    case "alt": al = Double.parseDouble(v); break;
                    case "altv": av = Math.max(0f, Float.parseFloat(v)); break;
                    case "ts": tsv = Long.parseLong(v); break;
                    case "rr": rad = Math.max(0f, Math.min(100f, Float.parseFloat(v))); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) { }
        }
        lat = la; lng = lo; acc = ac; spd = sp; brg = br; alt = al; altVar = av; rr = rad; ts = tsv; jitter = jit; enabled = en;
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
                    recordLag();
                    system = "1".equals(Settings.Global.getString(app.getContentResolver(), KEY_SYS));
                    if (onReady != null && !readyRan && system) {
                        readyRan = true;
                        onReady.run();
                    }
                } catch (Throwable t) {
                    Log.w(ZygoteEntry.TAG, "config read failed: " + t);
                }
                sleep(250);   // Settings.Global di-cache per proses (generation), pembacaan murah
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
