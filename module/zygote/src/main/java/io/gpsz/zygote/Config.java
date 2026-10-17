package io.gpsz.zygote;

import android.app.Application;
import android.content.ContentResolver;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.v7878.unsafe.Reflection;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Random;

final class Config {
    static final String KEY = "gpsz_cfg";

    static final String KEY_SYS = "gpsz_sys";

    static final String KEY_BEAT = "gpsz_beat";

    private static final long FREEZE_MS = 10_000;

    private static final long OFF_MS = 60_000;

    private static final long GAP_MS = 2_000;

    static volatile boolean enabled;
    static volatile boolean jitter;
    static volatile boolean system;
    static volatile boolean sysHeartbeat;

    static volatile boolean playing;
    static volatile double lat = Double.NaN;
    static volatile double lng = Double.NaN;
    static volatile float acc = 10f;
    static volatile float spd = 0f;
    static volatile float brg = Float.NaN;
    static volatile double alt = 0.0;
    static volatile float altVar = 0f;
    static volatile float rr = 8f;
    private static final Random RND = new Random();
    private static volatile double altNoise = 0.0;
    private static volatile long altAt = 0;

    static final class Pt {
        final double lat, lng;
        final long at;
        Pt(double lat, double lng, long at) { this.lat = lat; this.lng = lng; this.at = at; }
    }

    private static volatile Pt snap;

    private static String beatSeen = null;
    private static long beatChangedAt = 0;
    private static long lastPollAt = 0;
    private static boolean beatArmed = false;

    static Pt snapshot() {
        Pt p = snap;
        if (p != null && SystemClock.elapsedRealtime() - p.at < 200) return p;
        return renewSnapshot();
    }

    private static synchronized Pt renewSnapshot() {
        long now = SystemClock.elapsedRealtime();
        Pt p = snap;
        if (p != null && now - p.at < 200) return p;
        double la = lat, lo = lng;
        if (jitter) {
            double c = Math.cos(Math.toRadians(la));
            if (Math.abs(c) < 1e-6) c = 1e-6;
            la += meters() / 111320.0;
            lo += meters() / (111320.0 * c);
        }
        p = new Pt(la, lo, now);
        snap = p;
        return p;
    }

    private static double meters() {
        return (RND.nextDouble() * 2.0 - 1.0) * rr;
    }

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

    private static void publishLive(Application app) {
        try {
            int bc = Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            Settings.Global.putString(app.getContentResolver(), "gpsz_sys_live", Integer.toString(bc));
        } catch (Throwable t) {
            Log.w(ZygoteEntry.TAG, "publish live failed: " + t);
        }
    }

    static void parse(String s) {
        if (s == null || s.equals("null")) {
            enabled = false;
            playing = false;
            return;
        }
        boolean en = false, jit = false, pl = false;
        double la = lat, lo = lng;
        float ac = acc;

        float sp = 0f, br = Float.NaN, av = 0f, rad = 8f;
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
                    case "rr": rad = Math.max(0f, Math.min(100f, Float.parseFloat(v))); break;
                    case "pl": pl = v.equals("1"); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) { }
        }
        lat = la; lng = lo; acc = ac; spd = sp; brg = br; alt = al; altVar = av; rr = rad; jitter = jit; enabled = en; playing = pl;
    }

    static void checkBeat(String beat) {
        long now = SystemClock.elapsedRealtime();
        boolean gap = lastPollAt != 0 && now - lastPollAt > GAP_MS;
        lastPollAt = now;
        if (!playing) {
            beatArmed = false;
            return;
        }

        if (!beatArmed || gap || !Objects.equals(beat, beatSeen)) {
            beatArmed = true;
            beatSeen = beat;
            beatChangedAt = now;
            return;
        }
        long stale = now - beatChangedAt;
        if (stale >= OFF_MS) {
            enabled = false;
        } else if (stale >= FREEZE_MS) {
            spd = 0f;
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m;
            try {
                m = at.getDeclaredMethod("currentApplication");
            } catch (Throwable t) {
                m = Reflection.getDeclaredMethod(at, "currentApplication");
            }
            m.setAccessible(true);
            return (Application) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static void startPolling() { startPolling(null); }

    static void startPolling(final Runnable onReady) {
        Thread th = new Thread(() -> {
            Application app = null;
            for (int i = 0; i < 2400 && app == null; i++) {
                app = currentApplication();
                if (app == null) sleep(50);
            }
            if (app == null) { Log.w(ZygoteEntry.TAG, "no Application"); return; }
            final ContentResolver cr = app.getContentResolver();
            boolean readyRan = false;
            for (;;) {
                try {
                    parse(Settings.Global.getString(cr, KEY));
                    checkBeat(Settings.Global.getString(cr, KEY_BEAT));
                    String sv = Settings.Global.getString(cr, KEY_SYS);
                    system = "1".equals(sv) || "2".equals(sv);
                    sysHeartbeat = "2".equals(sv);
                    if (onReady != null && !readyRan && system) {
                        readyRan = true;
                        onReady.run();
                        publishLive(app);
                    }
                } catch (Throwable ignored) { }
                sleep(250);
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
