package io.gpsz.zygote;

import android.app.Application;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

final class Config {
    private static final String SALT = "Vzvwk4Rxj7Ps";
    private static final boolean CODEC_NATIVE = verifyCodec();

    private static boolean verifyCodec() {
        if (!ConfigNative.ready()) return false;
        try {
            StringBuilder saltHex = new StringBuilder();
            for (int i = 0; i < SALT.length(); i++) saltHex.append(String.format("%02x", SALT.charAt(i) & 0xFF));
            String probe = "cfg:1;ab";
            String tag = ConfigNative.nativeTag("cfg");
            return saltHex.toString().equals(ConfigNative.nativeEnc("\0".repeat(SALT.length())))
                    && probe.equals(ConfigNative.nativeDec(ConfigNative.nativeEnc(probe)))
                    && tag != null && tag.length() == 6;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final boolean CFG_NATIVE = verifyCfg();

    private static boolean verifyCfg() {
        if (!ConfigNative.ready()) return false;
        try {
            return ConfigNative.nativeCoreSelfTest();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean cfgNative() {
        return CODEC_NATIVE && CFG_NATIVE;
    }

    static boolean ready() {
        return CODEC_NATIVE && CFG_NATIVE && LOC_NATIVE;
    }

    static String tag(String logical) {
        if (!CODEC_NATIVE) return null;
        try {
            return ConfigNative.nativeTag(logical);
        } catch (Throwable t) {
            return null;
        }
    }

    static String enc(String plain) {
        if (plain == null || !CODEC_NATIVE || !isAscii(plain)) return null;
        try {
            return ConfigNative.nativeEnc(plain);
        } catch (Throwable t) {
            return null;
        }
    }

    static String dec(String hex) {
        if (hex == null || !CODEC_NATIVE) return null;
        try {
            String r = ConfigNative.nativeDec(hex);
            return r != null && isAscii(r) ? r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    static final String KEY = tag("cfg");
    static final String KEY_SYS = tag("sys");
    static final String KEY_BEAT = tag("beat");
    static final String KEY_TRK = tag("trk");
    static final String KEY_NET = tag("net");
    static final String KEY_BT = tag("bt");
    static final String KEY_LIVE = tag("live");
    private static final long POLL_MS = 1_000;
    private static final long IDLE_POLL_MS = 5_000;
    private static final long FALLBACK_POLL_MS = 250;
    private static final long RETRY_MS = 5_000;
    private static final long SNAP_MS = 50;
    private static final long TICK_MS = 50;
    private static final int B_ENABLED = 1, B_JITTER = 2, B_SYSTEM = 4, B_HEARTBEAT = 8, B_HIDE_NET = 16,
            B_HIDE_BT = 32, B_PLAYING = 64, B_TK = 128, B_EMPTY = 256, B_WAKE = 512, B_TIGHT = 1024;
    static String trkFile = "/data/system/gpsz_trk";
    static volatile boolean enabled;
    static volatile boolean jitter;
    static volatile boolean system;
    static volatile boolean sysHeartbeat;
    static volatile boolean hideNet = true;
    static volatile boolean hideBt;
    static volatile boolean playing;
    static volatile boolean tkMode;

    static final class Pos {
        final double lat, lng;
        Pos(double lat, double lng) { this.lat = lat; this.lng = lng; }
    }

    static volatile Pos pos = new Pos(Double.NaN, Double.NaN);

    static boolean hasPos() {
        Pos q = pos;
        return !Double.isNaN(q.lat) && !Double.isNaN(q.lng);
    }

    static volatile float acc = 10f;
    static volatile float spd = 0f;
    static volatile float brg = Float.NaN;
    static volatile double alt = 0.0;
    static volatile float altVar = 0f;
    static volatile float rr = 8f;

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

    static volatile Pt snap;
    private static volatile boolean settingsEmpty;
    private static volatile long tickAt;
    private static final Object REFRESH_LOCK = new Object();
    private static final Object MIRROR = new Object();
    private static final ThreadLocal<double[]> OUT = ThreadLocal.withInitial(() -> new double[ConfigNative.OUT_LEN]);
    private static double mirrorSeq = -1;
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
        Pos q = pos;
        try {
            double[] r = LocationNative.nativeSnapshot(q.lat, q.lng, spd, brg, alt, altVar, jitter, rr, now);
            if (r != null && r.length >= 7) {
                p = new Pt(r[0], r[1], (long) r[2], (float) r[3], (float) r[4], r[5], (float) r[6]);
                snap = p;
                return p;
            }
        } catch (Throwable ignored) { }
        p = new Pt(q.lat, q.lng, now, spd, brg, alt, 0f);
        snap = p;
        return p;
    }

    private static final boolean LOC_NATIVE = verifyLoc();

    static boolean locNative() {
        return LOC_NATIVE;
    }

    private static boolean verifyLoc() {
        if (!LocationNative.ready()) return false;
        try {
            long boot = 123_456_789_012L;
            long want = (long) (LocationNative.nativeHashUnit(901, boot / 1_000_000_000L) * 1e9);
            return LocationNative.nativePhaseNs(boot) == want;
        } catch (Throwable t) {
            return false;
        }
    }

    static double hashUnit(long a, long b) {
        return LOC_NATIVE ? LocationNative.nativeHashUnit(a, b) : 0.0;
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
            String cur = dec(Settings.Global.getString(cr, KEY_LIVE));
            if (on ? !want.equals(cur) : cur != null && !cur.isEmpty()) Settings.Global.putString(cr, KEY_LIVE, enc(want));
        } catch (Throwable ignored) { }
    }

    private static int absorb(boolean ok, double[] r) {
        if (!ok) return -1;
        int b = (int) r[8];
        synchronized (MIRROR) {
            if (r[9] > mirrorSeq) {
                mirrorSeq = r[9];
                Pos q = pos;
                if (q.lat != r[0] || q.lng != r[1]) pos = new Pos(r[0], r[1]);
                acc = (float) r[2];
                spd = (float) r[3];
                brg = (float) r[4];
                alt = r[5];
                altVar = (float) r[6];
                rr = (float) r[7];
                enabled = (b & B_ENABLED) != 0;
                jitter = (b & B_JITTER) != 0;
                system = (b & B_SYSTEM) != 0;
                sysHeartbeat = (b & B_HEARTBEAT) != 0;
                hideNet = (b & B_HIDE_NET) != 0;
                hideBt = (b & B_HIDE_BT) != 0;
                playing = (b & B_PLAYING) != 0;
                tkMode = (b & B_TK) != 0;
                settingsEmpty = (b & B_EMPTY) != 0;
            }
        }
        return b;
    }

    static void tick() {
        if (!tkMode) return;
        long now = SystemClock.elapsedRealtime();
        long d = now - tickAt;
        if (d >= 0 && d < TICK_MS) return;
        tickAt = now;
        double[] r = OUT.get();
        try {
            absorb(ConfigNative.nativeTick(now, SystemClock.uptimeMillis(), r), r);
        } catch (Throwable ignored) { }
    }

    static void parse(String s) {
        Pos q = pos;
        double[] r = OUT.get();
        try {
            absorb(ConfigNative.nativeParseCfg(s, q.lat, q.lng, acc, r), r);
        } catch (Throwable ignored) { }
    }

    static void checkBeat(String beat) {
        double[] r = OUT.get();
        try {
            absorb(ConfigNative.nativeCheckBeat(beat, SystemClock.elapsedRealtime(), r), r);
        } catch (Throwable ignored) { }
    }

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
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
                double[] r = OUT.get();
                int b = absorb(ConfigNative.nativeRefresh(
                        Settings.Global.getString(cr, KEY),
                        Settings.Global.getString(cr, KEY_TRK),
                        Settings.Global.getString(cr, KEY_BEAT),
                        Settings.Global.getString(cr, KEY_NET),
                        Settings.Global.getString(cr, KEY_BT),
                        Settings.Global.getString(cr, KEY_SYS),
                        onReady != null, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), trkFile, r), r);
                if (b < 0) return;
                if ((b & B_WAKE) != 0) wake();
                if (onReady != null && (b & B_SYSTEM) != 0) {
                    long now = SystemClock.elapsedRealtime();
                    if ((b & B_TIGHT) != 0 || now - lastTry >= RETRY_MS) {
                        lastTry = now;
                        live(app, onReady.getAsBoolean());
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                READING.remove();
            }
        }
    }

    private static void tryLoadFromFile() {
        synchronized (REFRESH_LOCK) {
            READING.set(Boolean.TRUE);
            try {
                double[] r = OUT.get();
                absorb(ConfigNative.nativeLoadFile(r), r);
            } catch (Throwable ignored) {
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
        if (observer != null) return true;
        try {
            observer = new ContentObserver(h) {
                @Override public void onChange(boolean selfChange) { onChange.run(); }
            };
            cr.registerContentObserver(Settings.Global.getUriFor(KEY), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_SYS), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_NET), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_BT), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_BEAT), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_TRK), false, observer);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void startPolling(final BooleanSupplier onReady) {
        if (!ready()) return;
        Thread finder = new Thread(() -> {
            if (onReady != null) tryLoadFromFile();
            Application found = null;
            for (int i = 0; i < 2400 && found == null; i++) {
                found = currentApplication();
                if (found == null) try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            }
            if (found == null) return;
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
                    h.postDelayed(this, !observed || (settingsEmpty && onReady != null) ? FALLBACK_POLL_MS : playing ? POLL_MS : IDLE_POLL_MS);
                }
            };
            h.post(tick);
        }, "pool-4-thread-1");
        finder.setDaemon(true);
        finder.start();
    }

    private Config() { }
}
