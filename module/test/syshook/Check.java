package io.gpsz.zygote;

import android.location.Location;
import android.location.LocationResult;
import android.os.IInterface;
import com.android.server.location.LocationManagerService;
import com.android.server.location.provider.LocationProviderManager;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public final class Check {
    private static int fail;

    private static void expect(String what, boolean cond) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) fail++;
    }

    public static final class Listener implements IInterface {
        volatile int started;
        public Object asBinder() { return this; }
        public void onGnssStarted() { started++; }
        public void onGnssStopped() { }
        public void onFirstFix(int ttff) { }
    }

    private static Method find(Class<?> c, String name) throws Exception {
        for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name)) return m;
        throw new NoSuchMethodException(name);
    }

    private static Location loc(String provider, double lat) {
        Location l = new Location(provider);
        l.lat = lat;
        l.lng = lat;
        l.ns = 7_000_000_000L;
        l.ms = System.currentTimeMillis();
        return l;
    }

    private static LocationResult one(Location l) {
        return LocationResult.wrap(Collections.singletonList(l));
    }

    private static LocationResult lastMain() {
        List<LocationResult> m = LocationProviderManager.MAIN;
        return m.isEmpty() ? null : m.get(m.size() - 1);
    }

    private static boolean near(Location l, double lat, double lng, double tol) {
        return l != null && Math.abs(l.lat - lat) < tol && Math.abs(l.lng - lng) < tol;
    }

    private static boolean waitFor(java.util.function.BooleanSupplier c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return c.getAsBoolean();
    }

    private static Method LAST, INJECT, STATUS, NMEA, MEAS, UNREG, BATCH, NAV, BATCHING, REPORT;

    private static void resolve() throws Exception {
        Class<?> lms = LocationManagerService.class;
        LAST = find(lms, "getLastLocation");
        INJECT = find(lms, "injectLocation");
        STATUS = find(lms, "registerGnssStatusCallback");
        NMEA = find(lms, "registerGnssNmeaCallback");
        MEAS = find(lms, "addGnssMeasurementsListener");
        UNREG = find(lms, "unregisterGnssStatusCallback");
        BATCH = find(lms, "startGnssBatch");
        NAV = find(lms, "addGnssNavigationMessageListener");
        BATCHING = find(lms, "addGnssBatchingCallback");
        REPORT = find(LocationProviderManager.class, "onReportLocation");
    }

    public static void main(String[] args) throws Throwable {
        String mode = args.length > 0 ? args[0] : "ls";
        resolve();
        LsNative.ready = !mode.equals("notready");
        LsNative.sys = !mode.equals("off");
        if (mode.equals("partial")) LsNative.failOn = REPORT;
        Config.enabled = false;
        Config.system = false;
        Config.sysHeartbeat = false;

        System.out.println("[pemasangan: " + mode + "]");
        boolean live = SystemHooks.install();
        int ls = LsNative.HOOKS.size();
        boolean wantLive = mode.equals("ls");
        expect("install() melaporkan " + (wantLive ? "hidup" : "tidak hidup (tanpa hook, tanpa jalur cadangan)"), live == wantLive);
        switch (mode) {
            case "ls":
                expect("10 method terpasang lewat LSPlant (" + ls + ")", ls == 10);
                break;
            case "off":
            case "notready":
                expect("tidak ada hook (tanpa jalur cadangan)", ls == 0);
                break;
            case "partial":
                expect("9 method lewat LSPlant (" + ls + ")", ls == 9);
                expect("onReportLocation tidak dihook, sehingga hook LPM tidak hidup (tanpa jalur cadangan)", !LsNative.HOOKS.containsKey(REPORT));
                break;
            default:
                throw new IllegalArgumentException(mode);
        }
        if (!mode.equals("ls")) {
            System.out.println(fail == 0 ? "semua lulus" : fail + " gagal");
            System.exit(fail == 0 ? 0 : 1);
        }

        LocationManagerService lms = new LocationManagerService();
        Listener lst = new Listener();

        System.out.println("[tidak aktif: semuanya diteruskan apa adanya]");
        Location real = loc("gps", 5.0);
        LocationManagerService.real = real;
        expect("getLastLocation -> objek asli", LsNative.dispatch(LAST, lms, "gps", "pkg") == real);
        LocationManagerService.deny = true;
        Throwable t = null;
        try {
            LsNative.dispatch(LAST, lms, "gps", "pkg");
        } catch (Throwable e) {
            t = e;
        }
        expect("SecurityException dari method asli tetap dilempar", t instanceof SecurityException);
        LocationManagerService.deny = false;
        LocationProviderManager gps = new LocationProviderManager("gps");
        LocationResult in = one(loc("gps", 5.0));
        LsNative.dispatch(REPORT, gps, in);
        expect("onReportLocation -> hasil asli", lastMain() == in);
        Location orig = loc("gps", 5.0);
        LsNative.dispatch(INJECT, lms, orig);
        expect("injectLocation -> argumen asli", LocationManagerService.injected == orig);
        LsNative.dispatch(BATCH, lms, 1000L, false);
        expect("startGnssBatch diteruskan", LocationManagerService.batch == 1);
        expect("addGnssNavigationMessageListener diteruskan (true)", Boolean.TRUE.equals(LsNative.dispatch(NAV, lms, lst, "com.app")) && LocationManagerService.nav == 1);
        LsNative.dispatch(STATUS, lms, lst, "com.app", "a");
        expect("registerGnssStatusCallback diteruskan", LocationManagerService.feeds == 1);

        System.out.println("[aktif: mode penuh]");
        Config.enabled = true;
        Config.system = true;
        Config.sysHeartbeat = false;
        Config.pos = new Config.Pos(-7.7, 110.3);

        Object r = LsNative.dispatch(LAST, lms, "gps", "pkg");
        Location lk = (Location) r;
        expect("getLastLocation: lat/lng dekat posisi palsu", lk != null && lk != real && Math.abs(lk.lat + 7.7) < 1e-3 && Math.abs(lk.lng - 110.3) < 1e-3);
        expect("getLastLocation: provider gps", lk != null && "gps".equals(lk.provider));
        expect("objek asli tidak diubah", real.lat == 5.0);

        LocationManagerService.enabled = false;
        expect("provider nonaktif untuk user -> hasil asli", LsNative.dispatch(LAST, lms, "gps", "pkg") == real);
        LocationManagerService.enabled = true;

        LocationManagerService.deny = true;
        t = null;
        try {
            LsNative.dispatch(LAST, lms, "gps", "pkg");
        } catch (Throwable e) {
            t = e;
        }
        expect("method asli menolak: SecurityException tetap sampai ke pemanggil", t instanceof SecurityException);
        LocationManagerService.deny = false;

        Location src = loc("gps", 5.0);
        LocationResult res = one(src);
        LsNative.dispatch(REPORT, gps, res);
        LocationResult out = lastMain();
        expect("onReportLocation: hasil diganti", out != null && out != res && out.size() == 1);
        Location rl = out.get(0);
        expect("onReportLocation: lat/lng palsu", Math.abs(rl.lat + 7.7) < 1e-3 && Math.abs(rl.lng - 110.3) < 1e-3);
        expect("onReportLocation: Location asli tidak diubah", src.lat == 5.0 && res.get(0) == src);

        LocationProviderManager net = new LocationProviderManager("network");
        LsNative.dispatch(REPORT, net, one(loc("network", 6.0)));
        Location nl = lastMain().get(0);
        expect("onReportLocation network: dekat posisi palsu dan akurasi diset", Math.abs(nl.lat + 7.7) < 0.01 && nl.acc > 0);

        Location toInject = loc("gps", 5.0);
        LsNative.dispatch(INJECT, lms, toInject);
        Location inj = LocationManagerService.injected;
        expect("injectLocation: argumen diganti sebelum method asli", inj != toInject && Math.abs(inj.lat + 7.7) < 1e-3);

        int b0 = LocationManagerService.batch, n0 = LocationManagerService.nav, bc0 = LocationManagerService.batching;
        LsNative.dispatch(BATCH, lms, 1000L, false);
        expect("startGnssBatch diblokir (method asli tidak dipanggil)", LocationManagerService.batch == b0);
        Object nv = LsNative.dispatch(NAV, lms, lst, "com.app");
        expect("addGnssNavigationMessageListener -> false, asli tidak dipanggil", Boolean.FALSE.equals(nv) && LocationManagerService.nav == n0);
        Object bt = LsNative.dispatch(BATCHING, lms, lst, "com.app");
        expect("addGnssBatchingCallback -> false, asli tidak dipanggil", Boolean.FALSE.equals(bt) && LocationManagerService.batching == bc0);

        int f0 = LocationManagerService.feeds, m0 = LocationManagerService.measure;
        LsNative.dispatch(STATUS, lms, lst, "com.app", "a");
        expect("registerGnssStatusCallback app biasa: asli tidak dipanggil", LocationManagerService.feeds == f0);
        expect("pendengar mendapat onGnssStarted dari GnssFake", waitFor(() -> lst.started >= 1, 3000));
        Object mv = LsNative.dispatch(MEAS, lms, new Listener(), "com.app", "a");
        expect("addGnssMeasurementsListener -> true, asli tidak dipanggil", Boolean.TRUE.equals(mv) && LocationManagerService.measure == m0);
        LsNative.dispatch(NMEA, lms, new Listener(), "com.app", "a");
        expect("registerGnssNmeaCallback: asli tidak dipanggil", LocationManagerService.feeds == f0);
        LsNative.dispatch(STATUS, lms, new Listener(), "android", "a");
        expect("paket tepercaya (android) tetap diteruskan ke method asli", LocationManagerService.feeds == f0 + 1);
        int u0 = LocationManagerService.unfeeds;
        LsNative.dispatch(UNREG, lms, lst);
        expect("unregisterGnssStatusCallback tetap diteruskan", LocationManagerService.unfeeds == u0 + 1);

        System.out.println("[darurat]");
        LocationProviderManager emg = new LocationProviderManager("gps");
        emg.emergency().on = true;
        LocationResult e1 = one(loc("gps", 5.0));
        LsNative.dispatch(REPORT, emg, e1);
        expect("onReportLocation saat darurat: tidak diganti", lastMain() == e1);
        expect("getLastLocation saat darurat: hasil asli", LsNative.dispatch(LAST, lms, "gps", "pkg") == real);
        emg.emergency().on = false;
        LocationResult e2 = one(loc("gps", 5.0));
        LsNative.dispatch(REPORT, emg, e2);
        expect("darurat selesai: diganti lagi", lastMain() != e2 && Math.abs(lastMain().get(0).lat + 7.7) < 1e-3);

        System.out.println("[mode heartbeat]");
        Config.sysHeartbeat = true;
        LocationResult h1 = one(loc("gps", 5.0));
        LsNative.dispatch(REPORT, emg, h1);
        expect("heartbeat: onReportLocation tidak diganti", lastMain() == h1);
        expect("heartbeat: getLastLocation tidak diganti", LsNative.dispatch(LAST, lms, "gps", "pkg") == real);
        int b1 = LocationManagerService.batch;
        LsNative.dispatch(BATCH, lms, 1000L, false);
        expect("heartbeat: startGnssBatch diteruskan", LocationManagerService.batch == b1 + 1);

        System.out.println("[mati lagi]");
        Config.enabled = false;
        Config.sysHeartbeat = false;
        LocationResult o1 = one(loc("gps", 5.0));
        LsNative.dispatch(REPORT, emg, o1);
        expect("config mati: onReportLocation tidak diganti", lastMain() == o1);

        System.out.println(fail == 0 ? "semua lulus" : fail + " gagal");
        System.exit(fail == 0 ? 0 : 1);
    }
}
