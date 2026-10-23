package io.gpsz.zygote;

import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.telephony.CellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public final class Check {
    private static int fail;

    private static void expect(String what, boolean cond) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) fail++;
    }

    private static Method find(Class<?> c, String name, Class<?>... ps) throws Exception {
        return c.getDeclaredMethod(name, ps);
    }

    private static Object call(Object self, String name, Class<?>[] ps, Object... args) throws Throwable {
        return LsNative.dispatch(find(self.getClass(), name, ps), self, args);
    }

    private static Object call(Object self, String name, Object... args) throws Throwable {
        for (Method m : self.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                return LsNative.dispatch(m, self, args);
            }
        }
        throw new IllegalStateException(name);
    }

    private static Location loc(String provider, double lat) {
        Location l = new Location(provider);
        l.lat = lat;
        l.lng = lat;
        l.ns = 7_000_000_000L;
        l.ms = System.currentTimeMillis();
        return l;
    }

    private static void reset() {
        LsNative.HOOKS.clear();
        LsNative.failOn = null;
    }

    public static void main(String[] args) throws Throwable {
        System.out.println("[Frames.coerce]");
        expect("void -> null", Frames.coerce(void.class, Boolean.TRUE) == null);
        expect("boolean null -> false", Boolean.FALSE.equals(Frames.coerce(boolean.class, null)));
        expect("int salah tipe -> 0", Integer.valueOf(0).equals(Frames.coerce(int.class, Boolean.TRUE)));
        expect("float benar tetap", Float.valueOf(2.5f).equals(Frames.coerce(float.class, 2.5f)));
        expect("double null -> 0.0", Double.valueOf(0d).equals(Frames.coerce(double.class, null)));
        expect("long salah tipe (Integer) -> 0L", Long.valueOf(0L).equals(Frames.coerce(long.class, 3)));
        expect("referensi tipe salah -> null", Frames.coerce(String.class, 5) == null);
        expect("referensi cocok tetap", "x".equals(Frames.coerce(String.class, "x")));

        System.out.println("[pemasangan lewat LSPlant]");
        reset();
        LsNative.ready = true;
        LsNative.loc = true;
        LocationHooks.install();
        int n = LsNative.HOOKS.size();
        expect("banyak method terpasang lewat LSPlant (" + n + ")", n >= 45);
        expect("SettingsGuard dan PackageGuardLs dipasang sekali", SettingsGuard.installs == 1 && PackageGuardLs.installs == 1);
        expect("Location.getLatitude", LsNative.HOOKS.containsKey(find(Location.class, "getLatitude")));
        expect("LocationManager.getCurrentLocation", LsNative.HOOKS.containsKey(
                find(LocationManager.class, "getCurrentLocation", String.class, Object.class, java.util.concurrent.Executor.class, Consumer.class)));
        expect("AppOpsManager.checkOpNoThrow", LsNative.HOOKS.containsKey(find(AppOpsManager.class, "checkOpNoThrow", int.class, int.class, String.class)));
        expect("WifiInfo.getBSSID", LsNative.HOOKS.containsKey(find(WifiInfo.class, "getBSSID")));
        Object creator = Location.class.getField("CREATOR").get(null);
        boolean creatorHooked = false;
        for (Method m : creator.getClass().getDeclaredMethods()) creatorHooked |= LsNative.HOOKS.containsKey(m);
        expect("CREATOR.createFromParcel", creatorHooked);

        System.out.println("[perilaku: config mati]");
        Config.enabled = false;
        Location l0 = loc("gps", 5.0);
        expect("getLatitude lewat apa adanya", Double.valueOf(5.0).equals(call(l0, "getLatitude")));
        expect("isLocationEnabled asli", Boolean.FALSE.equals(call(new LocationManager(), "isLocationEnabled")));

        System.out.println("[perilaku: config aktif]");
        Config.enabled = true;
        Config.pos = new Config.Pos(-7.7, 110.3);
        Config.hideNet = true;
        Config.hideBt = false;
        Location l1 = loc("gps", 5.0);
        Object lat = call(l1, "getLatitude");
        expect("getLatitude -> Double dekat posisi palsu", lat instanceof Double && Math.abs((Double) lat + 7.7) < 1e-4);
        Object lng = call(l1, "getLongitude");
        expect("getLongitude -> Double dekat posisi palsu", lng instanceof Double && Math.abs((Double) lng - 110.3) < 1e-4);
        expect("getAccuracy -> Float >= 1", call(l1, "getAccuracy") instanceof Float && (Float) call(l1, "getAccuracy") >= 1f);
        expect("hasSpeed -> Boolean", call(l1, "hasSpeed") instanceof Boolean);
        expect("isFromMockProvider false", Boolean.FALSE.equals(call(l1, "isFromMockProvider")));
        Location a = loc("gps", 1.0), b = loc("gps", 2.0);
        Object dist = call(a, "distanceTo", b);
        expect("distanceTo -> Float, kedua lokasi dibetulkan", dist instanceof Float && Math.abs(a.lat + 7.7) < 1e-4 && Math.abs(b.lat + 7.7) < 1e-4);
        Location noFix = new Location("gps");
        noFix.lat = 9;
        expect("Location tanpa waktu/elapsed tidak diubah", Double.valueOf(9).equals(call(noFix, "getLatitude")));

        LocationManager lm = new LocationManager();
        expect("isLocationEnabled -> true", Boolean.TRUE.equals(call(lm, "isLocationEnabled")));
        expect("isProviderEnabled(gps) -> true", Boolean.TRUE.equals(call(lm, "isProviderEnabled", "gps")));
        expect("isProviderEnabled(passive) asli", Boolean.FALSE.equals(call(lm, "isProviderEnabled", "passive")));
        List<?> provs = (List<?>) LsNative.dispatch(find(LocationManager.class, "getProviders", boolean.class), lm, true);
        expect("getProviders digabung (gps, network, fused, passive)",
                provs.containsAll(Arrays.asList("gps", "network", "fused", "passive")) && provs.size() == 4);
        List<?> all = (List<?>) LsNative.dispatch(find(LocationManager.class, "getAllProviders"), lm);
        expect("getAllProviders digabung", all.containsAll(Arrays.asList("gps", "network", "fused")));

        LocationManager.providerEnabled = false;
        expect("getLastKnownLocation: provider mati -> hasil asli (null)", call(lm, "getLastKnownLocation", "gps") == null);
        LocationManager.providerEnabled = true;
        Object lk = call(lm, "getLastKnownLocation", "gps");
        expect("getLastKnownLocation: provider hidup -> lokasi palsu",
                lk instanceof Location && Math.abs(((Location) lk).lat + 7.7) < 1e-4 && ((Location) lk).ns != 0);
        LocationManager.throwLastKnown = true;
        boolean sec = false;
        try { call(lm, "getLastKnownLocation", "gps"); } catch (SecurityException e) { sec = true; }
        LocationManager.throwLastKnown = false;
        expect("SecurityException dari method asli diteruskan", sec);

        AppOpsManager ops = new AppOpsManager();
        Method chk = find(AppOpsManager.class, "checkOpNoThrow", int.class, int.class, String.class);
        expect("checkOpNoThrow(58) -> MODE_IGNORED (1)", Integer.valueOf(1).equals(LsNative.dispatch(chk, ops, 58, 1000, "p")));
        expect("checkOpNoThrow(lain) asli (0)", Integer.valueOf(0).equals(LsNative.dispatch(chk, ops, 1, 1000, "p")));

        WifiInfo wi = new WifiInfo();
        expect("getBSSID -> 02:00:00:00:00:00", "02:00:00:00:00:00".equals(call(wi, "getBSSID")));
        Object ssid = call(wi, "getSSID");
        expect("getSSID -> String bukan nilai asli", ssid instanceof String && !"\"home\"".equals(ssid));
        TelephonyManager tm = new TelephonyManager();
        expect("getAllCellInfo -> daftar kosong", ((List<?>) call(tm, "getAllCellInfo")).isEmpty());
        expect("getCellLocation -> null", call(tm, "getCellLocation") == null);
        final List<CellInfo>[] got = new List[1];
        TelephonyManager.updateCalls = 0;
        call(tm, "requestCellInfoUpdate", (java.util.concurrent.Executor) Runnable::run, (TelephonyManager.CellInfoCallback) c -> got[0] = c);
        expect("requestCellInfoUpdate: callback kosong, method asli tidak dipanggil", got[0] != null && got[0].isEmpty() && TelephonyManager.updateCalls == 0);

        PhoneStateListener.IPhoneStateListenerStub st = new PhoneStateListener.IPhoneStateListenerStub();
        call(st, "onCellInfoChanged", new ArrayList<>(Arrays.asList(new CellInfo())));
        expect("onCellInfoChanged: argumen diganti daftar kosong sebelum method asli",
                PhoneStateListener.IPhoneStateListenerStub.lastCells != null && PhoneStateListener.IPhoneStateListenerStub.lastCells.isEmpty());
        call(st, "onCellLocationChanged", "x");
        expect("onCellLocationChanged dibuang", PhoneStateListener.IPhoneStateListenerStub.locCalls == 0);

        BluetoothLeScanner.scans = 0;
        call(new BluetoothLeScanner(), "startScan", new Object());
        expect("Bluetooth: hideBt=false -> method asli jalan", BluetoothLeScanner.scans == 1);
        expect("startDiscovery hideBt=false -> asli (false)", Boolean.FALSE.equals(call(new BluetoothAdapter(), "startDiscovery")));
        Config.hideBt = true;
        call(new BluetoothLeScanner(), "startScan", new Object());
        expect("Bluetooth: hideBt=true -> startScan dibuang", BluetoothLeScanner.scans == 1);
        expect("startDiscovery hideBt=true -> true", Boolean.TRUE.equals(call(new BluetoothAdapter(), "startDiscovery")));
        Config.hideBt = false;

        Location created = null;
        for (Method m : creator.getClass().getDeclaredMethods()) {
            if (m.getName().equals("createFromParcel")) created = (Location) LsNative.dispatch(m, creator, new Object[]{null});
        }
        expect("createFromParcel: hasil dibetulkan memakai snapshot", created != null && Math.abs(created.lat + 7.7) < 1e-4);

        Location target = loc("gps", 3.0), src = loc("gps", 4.0);
        call(target, "set", src);
        expect("Location.set: hasil dibetulkan setelah method asli", Math.abs(target.lat + 7.7) < 1e-4);

        final Location[] delivered = new Location[1];
        LocationManager.providerEnabled = true;
        Object r = LsNative.dispatch(find(LocationManager.class, "getCurrentLocation", String.class, Object.class,
                java.util.concurrent.Executor.class, Consumer.class), lm, "gps", null,
                (java.util.concurrent.Executor) Runnable::run, (Consumer<Location>) x -> delivered[0] = x);
        expect("getCurrentLocation: consumer menerima lokasi palsu, return true",
                Boolean.TRUE.equals(r) && delivered[0] != null && Math.abs(delivered[0].lat + 7.7) < 1e-4);
        Config.sysHeartbeat = true;
        delivered[0] = null;
        r = LsNative.dispatch(find(LocationManager.class, "getCurrentLocation", String.class, Object.class,
                java.util.concurrent.Executor.class, Consumer.class), lm, "gps", null,
                (java.util.concurrent.Executor) Runnable::run, (Consumer<Location>) x -> delivered[0] = x);
        expect("getCurrentLocation: heartbeat sistem -> method asli (false, tanpa lokasi)", Boolean.FALSE.equals(r) && delivered[0] == null);
        Config.sysHeartbeat = false;

        System.out.println("[tanpa jalur cadangan]");
        reset();
        LsNative.loc = false;
        LocationHooks.install();
        expect("disable_ls_loc: tidak ada hook sama sekali", LsNative.HOOKS.isEmpty());
        reset();
        LsNative.loc = true;
        LsNative.ready = false;
        LocationHooks.install();
        expect("LSPlant tidak siap: tidak ada hook, install tidak melempar", LsNative.HOOKS.isEmpty());
        reset();
        LsNative.ready = true;
        LsNative.failOn = find(Location.class, "getLatitude");
        LocationHooks.install();
        expect("satu method gagal di LSPlant: hanya itu yang tidak dihook (" + LsNative.HOOKS.size() + ")",
                !LsNative.HOOKS.containsKey(find(Location.class, "getLatitude")) && LsNative.HOOKS.size() >= 40
                        && LsNative.HOOKS.containsKey(find(Location.class, "getLongitude")));
        reset();

        System.out.println(fail == 0 ? "SEMUA LOLOS" : fail + " GAGAL");
        System.exit(fail == 0 ? 0 : 1);
    }
}
