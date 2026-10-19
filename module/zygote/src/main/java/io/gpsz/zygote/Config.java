package io.gpsz.zygote;

import android.app.Application;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;

import com.v7878.unsafe.Reflection;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Random;
import java.util.function.BooleanSupplier;

final class Config {
    static final String KEY = "gpsz_cfg";
    static final String KEY_SYS = "gpsz_sys";
    static final String KEY_BEAT = "gpsz_beat";
    static final String KEY_TRK = "gpsz_trk";
    static final String KEY_NET = "gpsz_net";
    static final String KEY_BT = "gpsz_bt";
    private static final long FREEZE_MS = 10_000;
    private static final long OFF_MS = 60_000;
    private static final long TRK_GRACE_MS = 3_000;
    private static final long GAP_MS = 3_000;
    private static final long POLL_MS = 1_000;
    private static final long IDLE_POLL_MS = 5_000;
    private static final long FALLBACK_POLL_MS = 250;
    private static final long RETRY_MS = 5_000;
    private static final long SNAP_MS = 50;
    static volatile boolean enabled;
    static volatile boolean jitter;
    static volatile boolean system;
    static volatile boolean sysHeartbeat;
    static volatile boolean hideNet = true;
    static volatile boolean hideBt = false;
    static volatile boolean playing;
    static volatile boolean tkMode;
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
    private static final double JITTER_TAU_S = 10.0;
    private static double jitN = 0.0, jitE = 0.0;
    private static long jitAt = 0;

    static final class Pt {
        final double lat, lng;
        final long at;
        final float spd, brg, accJit;
        final double alt;
        Pt(double lat, double lng, long at, float spd, float brg, double alt, float accJit) {
            this.lat = lat; this.lng = lng; this.at = at;
            this.spd = spd; this.brg = brg; this.alt = alt; this.accJit = accJit;
        }
    }

    static final class Track {
        final long t0, wrote;
        final int tk, n;
        final double[] la, lo, al;
        final float[] sp, br;
        Track(long t0, long wrote, int tk, int n, double[] la, double[] lo, double[] al, float[] sp, float[] br) {
            this.t0 = t0; this.wrote = wrote; this.tk = tk; this.n = n;
            this.la = la; this.lo = lo; this.al = al; this.sp = sp; this.br = br;
        }
    }

    private static volatile Pt snap;
    private static volatile Track track;
    private static String trkSeen;
    private static boolean cfgEn;
    private static volatile int trkStale = 0;
    private static long trkMissAt = 0;
    private static volatile long tickAt = 0;
    private static String beatSeen = null;
    private static long beatChangedAt = 0;
    private static long lastPollAt = 0;
    private static boolean beatArmed = false;
    private static volatile int beatStale = 0;
    private static final Object REFRESH_LOCK = new Object();
    private static long lastTry = -RETRY_MS;
    private static ContentObserver observer;
    static final Object WAKE = new Object();
    static final ThreadLocal<Boolean> READING = new ThreadLocal<>();

    static Pt snapshot() {
        Pt p = snap;
        if (p != null && SystemClock.elapsedRealtime() - p.at < SNAP_MS) return p;
        return renewSnapshot();
    }

    private static synchronized Pt renewSnapshot() {
        tick();
        long now = SystemClock.elapsedRealtime();
        Pt p = snap;
        if (p != null && now - p.at < SNAP_MS) return p;
        double la = lat, lo = lng;
        float sSpd = spd, sBrg = brg;
        double sAlt = altitudeNow();
        if (jitter) {
            stepJitter(now);
            double c = Math.cos(Math.toRadians(la));
            if (Math.abs(c) < 1e-6) c = 1e-6;
            la += jitN / 111320.0;
            lo += jitE / (111320.0 * c);
        } else {
            jitAt = 0;
        }
        float jit = (float) (RND.nextGaussian() * 0.35);
        p = new Pt(la, lo, now, sSpd, sBrg, sAlt, jit);
        snap = p;
        return p;
    }

    private static void stepJitter(long now) {
        double r = rr;
        if (r <= 0f) { jitN = 0; jitE = 0; jitAt = now; return; }
        double sigma = r / 3.0;
        if (jitAt == 0) {
            jitN = RND.nextGaussian() * sigma;
            jitE = RND.nextGaussian() * sigma;
        } else {
            double dt = Math.max(0, Math.min(60_000, now - jitAt)) / 1000.0;
            double a = Math.exp(-dt / JITTER_TAU_S);
            double s = sigma * Math.sqrt(1.0 - a * a);
            jitN = jitN * a + RND.nextGaussian() * s;
            jitE = jitE * a + RND.nextGaussian() * s;
        }
        double d = Math.hypot(jitN, jitE);
        if (d > r) {
            jitN *= r / d;
            jitE *= r / d;
        }
        jitAt = now;
    }

    static float accNow(Pt p) {
        return Math.max(1f, acc + (p != null ? p.accJit : 0f));
    }

    private static int gSat = 0, gMean = 0;
    private static long gAt = 0;
    private static double netLat = 0, netLng = 0;
    private static long netAt = 0;

    static synchronized int[] gnssExtras() {
        long now = SystemClock.elapsedRealtime();
        if (gSat == 0) {
            gSat = 7 + RND.nextInt(6);
            gMean = 25 + RND.nextInt(8);
            gAt = now;
        } else if (now - gAt >= 1000) {
            gAt = now;
            int r = RND.nextInt(10);
            if (r == 0) gSat++; else if (r == 1) gSat--;
            r = RND.nextInt(6);
            if (r == 0) gMean++; else if (r == 1) gMean--;
            gSat = Math.max(5, Math.min(14, gSat));
            gMean = Math.max(22, Math.min(36, gMean));
        }
        return new int[]{gSat, gMean, gMean + 9 + RND.nextInt(3)};
    }

    static float vAcc(Pt p) {
        return Math.max(1f, accNow(p) * 1.3f + p.accJit * 1.5f);
    }

    static float sAcc(Pt p) {
        return Math.max(0.1f, 0.4f + Math.abs(p.accJit) * 0.5f);
    }

    static float bAcc(Pt p) {
        return Math.max(1f, 10f + Math.abs(p.accJit) * 8f);
    }

    static synchronized double[] networkOffset(double refLat) {
        long now = SystemClock.elapsedRealtime();
        if (netAt == 0 || now - netAt > 20_000 + RND.nextInt(20_000)) {
            double ang = RND.nextDouble() * 2 * Math.PI;
            double dist = 35 + RND.nextDouble() * 90;
            double c = Math.cos(Math.toRadians(refLat));
            if (Math.abs(c) < 1e-6) c = 1e-6;
            netLat = Math.sin(ang) * dist / 111320.0;
            netLng = Math.cos(ang) * dist / (111320.0 * c);
            netAt = now;
        }
        return new double[]{netLat, netLng};
    }

    static float networkAccuracy(float gpsAcc) {
        return Math.max(25f, gpsAcc * 2.5f + 20f + (float) (RND.nextGaussian() * 6));
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

    private static void live(Application app, boolean on) {
        try {
            ContentResolver cr = app.getContentResolver();
            String want = "";
            if (on) {
                int bc = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1);
                if (bc < 0) return;
                want = Integer.toString(bc);
            }
            String cur = Settings.Global.getString(cr, "gpsz_sys_live");
            if (on ? !want.equals(cur) : cur != null && !cur.isEmpty()) Settings.Global.putString(cr, "gpsz_sys_live", want);
        } catch (Throwable t) {
            ZygoteEntry.w("live failed: " + t);
        }
    }

    static Track parseTrack(String s) {
        if (s == null) return null;
        long t0 = Long.MIN_VALUE, a = Long.MIN_VALUE;
        int tk = 0;
        String d = null;
        for (String kv : s.split(";")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            String v = kv.substring(i + 1);
            try {
                switch (kv.substring(0, i)) {
                    case "t": t0 = Long.parseLong(v); break;
                    case "a": a = Long.parseLong(v); break;
                    case "k": tk = Integer.parseInt(v); break;
                    case "d": d = v; break;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (d == null || tk <= 0 || t0 == Long.MIN_VALUE) return null;
        String[] rows = d.split("\\|");
        double[] la = new double[rows.length], lo = new double[rows.length], al = new double[rows.length];
        float[] sp = new float[rows.length], br = new float[rows.length];
        int n = 0;
        for (String r : rows) {
            int st = 0;
            while (st < r.length() && r.charAt(st) == ',') st++;
            if (st == r.length()) continue;
            String[] f = r.substring(st).split(",");
            if (f.length < 5) return null;
            try {
                la[n] = Double.parseDouble(f[0]);
                lo[n] = Double.parseDouble(f[1]);
                sp[n] = Float.parseFloat(f[2]);
                br[n] = Float.parseFloat(f[3]);
                al[n] = Double.parseDouble(f[4]);
            } catch (NumberFormatException e) {
                return null;
            }
            n++;
        }
        if (n == 0) return null;
        return new Track(t0, a == Long.MIN_VALUE ? t0 : a, tk, n, la, lo, al, sp, br);
    }

    private static int trackStale(long now) {
        Track t = track;
        long late;
        if (t == null) {
            if (trkMissAt == 0) trkMissAt = now;
            late = now - trkMissAt;
        } else {
            trkMissAt = 0;
            late = now - Math.max(t.t0 + (long) (t.n - 1) * t.tk, t.wrote);
        }
        return late >= OFF_MS ? 2 : late >= TRK_GRACE_MS ? 1 : 0;
    }

    private static float lerpAngle(float a, float b, double f) {
        double d = ((b - a + 540.0) % 360.0) - 180.0;
        return (float) ((a + d * f + 360.0) % 360.0);
    }

    static void tick() {
        if (!tkMode) return;
        long now = SystemClock.elapsedRealtime();
        long d = now - tickAt;
        if (d >= 0 && d < 50) return;
        tickAt = now;
        applyTrack(now);
    }

    private static void applyTrack(long now) {
        int st = trackStale(now);
        trkStale = st;
        enabled = cfgEn && st < 2;
        Track t = track;
        if (t == null) return;
        long rel = now - t.t0;
        double pos = rel <= 0 ? 0.0 : (double) rel / t.tk;
        int i;
        double f;
        if (pos >= t.n - 1) { i = t.n - 1; f = 0.0; } else { i = (int) pos; f = pos - i; }
        int j = f > 0.0 ? i + 1 : i;
        lat = t.la[i] + (t.la[j] - t.la[i]) * f;
        lng = t.lo[i] + (t.lo[j] - t.lo[i]) * f;
        alt = t.al[i] + (t.al[j] - t.al[i]) * f;
        spd = st >= 1 ? 0f : Math.max(0f, (float) (t.sp[i] + (t.sp[j] - t.sp[i]) * f));
        brg = lerpAngle(t.br[i], t.br[j], f);
    }

    private static double inRange(double v, double limit, double fallback) {
        return Math.abs(v) <= limit ? v : fallback;
    }

    static void parse(String s) {
        if (s == null || s.equals("null")) {
            enabled = false;
            playing = false;
            tkMode = false;
            cfgEn = false;
            return;
        }
        boolean en = false, jit = false, pl = false, tk = false;
        double la = lat, lo = lng;
        float ac = acc, sp = 0f, br = Float.NaN, av = 0f, rad = 8f;
        double al = 0.0;
        for (String kv : s.split(";")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            String k = kv.substring(0, i).trim();
            String v = kv.substring(i + 1).trim();
            try {
                switch (k) {
                    case "enabled": en = v.equals("1"); break;
                    case "lat": la = inRange(Double.parseDouble(v), 90.0, la); break;
                    case "lng": lo = inRange(Double.parseDouble(v), 180.0, lo); break;
                    case "acc": ac = (float) inRange(Float.parseFloat(v), 1e5, ac); break;
                    case "random": jit = v.equals("1"); break;
                    case "spd": sp = Math.max(0f, (float) inRange(Float.parseFloat(v), 1e4, 0.0)); break;
                    case "brg": {
                        float b = Float.parseFloat(v);
                        br = (Float.isNaN(b) || Float.isInfinite(b) || b < 0f) ? Float.NaN : b % 360f;
                        break;
                    }
                    case "alt": al = inRange(Double.parseDouble(v), 1e5, 0.0); break;
                    case "altv": av = Math.max(0f, (float) inRange(Float.parseFloat(v), 1e4, 0.0)); break;
                    case "rr": rad = Math.max(0f, Math.min(100f, (float) inRange(Float.parseFloat(v), 1e4, 8.0))); break;
                    case "pl": pl = v.equals("1"); break;
                    case "tk": tk = v.equals("1"); break;
                }
            } catch (NumberFormatException ignored) { }
        }
        tkMode = tk;
        cfgEn = en;
        if (tk) { beatStale = 0; beatArmed = false; }
        acc = ac; altVar = av; rr = rad; jitter = jit; playing = pl;
        if (!(tk && track != null)) {
            lat = la; lng = lo; spd = beatStale >= 1 ? 0f : sp; brg = br; alt = al;
        }
        enabled = en && beatStale < 2 && (!tk || trkStale < 2);
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
            READING.set(Boolean.TRUE);
            try {
                boolean wasOn = enabled && system;
                String cs = Settings.Global.getString(cr, KEY);
                long rt = SystemClock.elapsedRealtime();
                if (cs != null && cs.contains(";tk=1")) {
                    String ts = Settings.Global.getString(cr, KEY_TRK);
                    if (!Objects.equals(ts, trkSeen)) { trkSeen = ts; track = parseTrack(ts); }
                    trkStale = trackStale(rt);
                } else {
                    track = null;
                    trkSeen = null;
                    trkMissAt = 0;
                    trkStale = 0;
                }
                parse(cs);
                if (tkMode) applyTrack(rt); else checkBeat(Settings.Global.getString(cr, KEY_BEAT));
                hideNet = !"0".equals(Settings.Global.getString(cr, KEY_NET));
                hideBt = "1".equals(Settings.Global.getString(cr, KEY_BT));
                String sv = Settings.Global.getString(cr, KEY_SYS);
                boolean wasSystem = system, wasHeartbeat = sysHeartbeat;
                system = "1".equals(sv) || "2".equals(sv);
                sysHeartbeat = "2".equals(sv);
                if (!wasOn && enabled && system) wake();
                boolean tightened = system && (!wasSystem || (wasHeartbeat && !sysHeartbeat));
                if (onReady != null && system) {
                    long now = SystemClock.elapsedRealtime();
                    if (tightened || now - lastTry >= RETRY_MS) {
                        lastTry = now;
                        live(app, onReady.getAsBoolean());
                    }
                }
            } catch (Throwable t) {
                ZygoteEntry.warnOnce("config refresh", t);
            } finally {
                READING.remove();
            }
        }
    }

    static boolean ownObserver(Object o) {
        return o != null && o == observer;
    }

    static void wake() {
        synchronized (WAKE) { WAKE.notifyAll(); }
    }

    private static boolean observe(ContentResolver cr, Handler h, final Runnable onChange) {
        try {
            observer = new ContentObserver(h) {
                @Override public void onChange(boolean selfChange) { onChange.run(); }
            };
            cr.registerContentObserver(Settings.Global.getUriFor(KEY), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_SYS), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_NET), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_BT), false, observer);
            return true;
        } catch (Throwable t) {
            ZygoteEntry.w("observer failed, polling fast: " + t);
            return false;
        }
    }

    static void startPolling(final BooleanSupplier onReady) {
        Thread finder = new Thread(() -> {
            Application found = null;
            for (int i = 0; i < 2400 && found == null; i++) {
                found = currentApplication();
                if (found == null) try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            }
            if (found == null) { ZygoteEntry.w("no Application"); return; }
            final Application app = found;
            final ContentResolver cr = app.getContentResolver();
            final Runnable refresh = () -> refresh(cr, app, onReady);
            HandlerThread ht = new HandlerThread("pool-4-thread-2");
            ht.start();
            final Handler h = new Handler(ht.getLooper());
            final boolean observed = observe(cr, h, refresh);
            final Runnable tick = new Runnable() {
                @Override public void run() {
                    refresh.run();
                    h.postDelayed(this, !observed ? FALLBACK_POLL_MS : playing ? POLL_MS : IDLE_POLL_MS);
                }
            };
            h.post(tick);
        }, "pool-4-thread-1");
        finder.setDaemon(true);
        finder.start();
    }

    private Config() { }
}
