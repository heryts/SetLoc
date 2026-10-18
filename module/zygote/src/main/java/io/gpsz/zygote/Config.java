package io.gpsz.zygote;

import android.app.Application;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.v7878.unsafe.Reflection;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Random;
import java.util.function.BooleanSupplier;

final class Config {
    static final String KEY = "gpsz_cfg";
    static final String KEY_SYS = "gpsz_sys";
    static final String KEY_BEAT = "gpsz_beat";
    private static final long FREEZE_MS = 10_000;
    private static final long OFF_MS = 60_000;
    private static final long GAP_MS = 3_000;
    private static final long POLL_MS = 1_000;
    private static final long FALLBACK_POLL_MS = 250;
    private static final long RETRY_MS = 5_000;
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
    private static volatile int beatStale = 0;
    private static final Object REFRESH_LOCK = new Object();
    private static long lastTry = -RETRY_MS;
    private static ContentObserver observer;

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

    private static void clearLive(Application app) {
        try {
            ContentResolver cr = app.getContentResolver();
            String v = Settings.Global.getString(cr, "gpsz_sys_live");
            if (v != null && !v.isEmpty()) Settings.Global.putString(cr, "gpsz_sys_live", "");
        } catch (Throwable t) {
            Log.w(ZygoteEntry.TAG, "clear live failed: " + t);
        }
    }

    private static void publishLive(Application app) {
        try {
            ContentResolver cr = app.getContentResolver();
            int bc = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1);
            if (bc < 0) return;
            String v = Integer.toString(bc);
            if (!v.equals(Settings.Global.getString(cr, "gpsz_sys_live"))) Settings.Global.putString(cr, "gpsz_sys_live", v);
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
                }
            } catch (NumberFormatException ignored) { }
        }
        lat = la; lng = lo; acc = ac; spd = beatStale >= 1 ? 0f : sp; brg = br; alt = al; altVar = av; rr = rad; jitter = jit; enabled = en && beatStale < 2; playing = pl;
    }

    static void checkBeat(String beat) {
        long now = SystemClock.elapsedRealtime();
        boolean gap = lastPollAt != 0 && now - lastPollAt > GAP_MS;
        lastPollAt = now;
        if (!playing) {
            beatArmed = false;
            beatStale = 0;
            return;
        }

        if (!beatArmed || gap || !Objects.equals(beat, beatSeen)) {
            beatArmed = true;
            beatSeen = beat;
            beatChangedAt = now;
            beatStale = 0;
            return;
        }
        long stale = now - beatChangedAt;
        if (stale >= OFF_MS) {
            beatStale = 2;
            enabled = false;
        } else if (stale >= FREEZE_MS) {
            beatStale = 1;
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

    static void refresh(ContentResolver cr, Application app, BooleanSupplier onReady) {
        synchronized (REFRESH_LOCK) {
            try {
                parse(Settings.Global.getString(cr, KEY));
                checkBeat(Settings.Global.getString(cr, KEY_BEAT));
                String sv = Settings.Global.getString(cr, KEY_SYS);
                boolean wasSystem = system, wasHeartbeat = sysHeartbeat;
                system = "1".equals(sv) || "2".equals(sv);
                sysHeartbeat = "2".equals(sv);
                boolean tightened = system && (!wasSystem || (wasHeartbeat && !sysHeartbeat));
                if (onReady != null && system) {
                    long now = SystemClock.elapsedRealtime();
                    if (tightened || now - lastTry >= RETRY_MS) {
                        lastTry = now;
                        if (onReady.getAsBoolean()) publishLive(app); else clearLive(app);
                    }
                }
            } catch (Throwable t) {
                ZygoteEntry.warnOnce("config refresh", t);
            }
        }
    }

    private static boolean observe(ContentResolver cr, final Runnable onChange) {
        try {
            HandlerThread ht = new HandlerThread("gpsz-observer");
            ht.start();
            observer = new ContentObserver(new Handler(ht.getLooper())) {
                @Override public void onChange(boolean selfChange) { onChange.run(); }
            };
            cr.registerContentObserver(Settings.Global.getUriFor(KEY), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_SYS), false, observer);
            return true;
        } catch (Throwable t) {
            Log.w(ZygoteEntry.TAG, "observer failed, polling fast: " + t);
            return false;
        }
    }

    static void startPolling(final BooleanSupplier onReady) {
        Thread th = new Thread(() -> {
            Application found = null;
            for (int i = 0; i < 2400 && found == null; i++) {
                found = currentApplication();
                if (found == null) sleep(50);
            }
            if (found == null) { Log.w(ZygoteEntry.TAG, "no Application"); return; }
            final Application app = found;
            final ContentResolver cr = app.getContentResolver();
            final Runnable refresh = () -> refresh(cr, app, onReady);
            final long interval = observe(cr, refresh) ? POLL_MS : FALLBACK_POLL_MS;
            for (;;) {
                refresh.run();
                sleep(interval);
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
