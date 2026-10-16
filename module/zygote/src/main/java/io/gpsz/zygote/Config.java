package io.gpsz.zygote;

import android.app.Application;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.v7878.unsafe.Reflection;

import java.lang.reflect.Method;
import java.util.Random;

/** Konfigurasi dari Settings.Global "gpsz_cfg" (ditulis root), mis. enabled=1;lat=-6.2;lng=106.8;acc=10;random=0. */
final class Config {
    static final String KEY = "gpsz_cfg";
    /** 1 = system hook penuh, 2 = heartbeat. Key terpisah supaya tidak ikut tertimpa bridge.sh. */
    static final String KEY_SYS = "gpsz_sys";
    /** Detak daemon bridge.sh (angka yang berubah tiap ~1 dtk saat rute diputar, pl=1). */
    static final String KEY_BEAT = "gpsz_beat";
    /** Detak berhenti >= ini: posisi dibekukan (speed 0). */
    private static final long FREEZE_MS = 10_000;
    /** Detak berhenti >= ini: spoof dimatikan sepenuhnya (lokasi asli kembali). */
    private static final long OFF_MS = 60_000;
    /** Jarak antar-poll lebih dari ini = proses sendiri yang dibekukan/tidur, bukan daemon yang mati. */
    private static final long GAP_MS = 2_000;

    static volatile boolean enabled;
    static volatile boolean jitter;
    static volatile boolean system;
    static volatile boolean sysHeartbeat;
    /** pl=1: rute sedang diputar daemon; hanya saat ini detak daemon diperiksa. */
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

    static volatile double snapLat = Double.NaN;
    static volatile double snapLng = Double.NaN;
    private static long snapAt = 0;

    // Status pemeriksaan detak; hanya disentuh thread polling. Memakai jam hook sendiri (elapsedRealtime),
    // bukan jam absolut, jadi sisa detak dari boot sebelumnya tidak berpengaruh.
    private static String beatSeen = null;
    private static long beatChangedAt = 0;
    private static long lastPollAt = 0;
    private static boolean beatArmed = false;

    /** Satu titik (dengan jitter bila aktif) dipakai ulang 200 ms supaya lat/lng yang dibaca berurutan sama. */
    static synchronized void refreshSnapshot() {
        long now = SystemClock.elapsedRealtime();
        if (now - snapAt < 200 && !Double.isNaN(snapLat)) return;
        double la = lat, lo = lng;
        if (jitter) {
            double c = Math.cos(Math.toRadians(la));
            if (Math.abs(c) < 1e-6) c = 1e-6;
            la += meters() / 111320.0;
            lo += meters() / (111320.0 * c);
        }
        snapLat = la; snapLng = lo; snapAt = now;
    }

    private static double meters() {
        return (RND.nextDouble() * 2.0 - 1.0) * rr;
    }

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

    /** Tandai system hook termuat di boot ini: gpsz_sys_live = boot_count. */
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
        // field gerak direset tiap parse: kunci yang tidak ada = nilai awal
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

    /**
     * Dipanggil sesudah parse() di tiap poll. Saat rute diputar (pl=1) dan detak daemon berhenti berubah:
     * >= 10 dtk -> speed 0 (posisi diam, tidak tampak "melaju" di titik terakhir); >= 60 dtk -> spoof mati.
     * Detak berubah lagi (daemon dinyalakan ulang supervisor) -> kembali normal di poll berikutnya.
     * Spoof titik tunggal (pl tidak ada) tidak disentuh sama sekali.
     */
    static void checkBeat(String beat) {
        long now = SystemClock.elapsedRealtime();
        boolean gap = lastPollAt != 0 && now - lastPollAt > GAP_MS;
        lastPollAt = now;
        if (!playing) {
            beatArmed = false;
            return;
        }
        // awal pemutaran, atau proses ini sendiri baru bangun/dibekukan: mulai hitung dari nol (masa tenggang)
        if (!beatArmed || gap || !java.util.Objects.equals(beat, beatSeen)) {
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

    /** onReady (boleh null) jalan sekali di thread polling begitu Application ada dan gpsz_sys terbaca aktif. */
    static void startPolling(final Runnable onReady) {
        Thread th = new Thread(() -> {
            Application app = null;
            for (int i = 0; i < 2400 && app == null; i++) {
                app = currentApplication();
                if (app == null) sleep(50);
            }
            if (app == null) { Log.w(ZygoteEntry.TAG, "no Application"); return; }
            boolean readyRan = false;
            for (;;) {
                try {
                    parse(Settings.Global.getString(app.getContentResolver(), KEY));
                    checkBeat(Settings.Global.getString(app.getContentResolver(), KEY_BEAT));
                    String sv = Settings.Global.getString(app.getContentResolver(), KEY_SYS);
                    system = "1".equals(sv) || "2".equals(sv);
                    sysHeartbeat = "2".equals(sv);
                    if (onReady != null && !readyRan && system) {
                        readyRan = true;
                        onReady.run();
                        publishLive(app);
                    }
                } catch (Throwable t) {
                    Log.w(ZygoteEntry.TAG, "config read failed: " + t);
                }
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
