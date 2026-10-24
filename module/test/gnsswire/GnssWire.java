package io.gpsz.zygote;

import android.os.SystemClock;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class GnssWire {
    private static int fail;

    private static void expect(String what, boolean cond) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) fail++;
    }

    private static final String[] PROBE_NMEA = {
            "$GNGGA,221523.00,0612.528000,S,10650.736000,E,1,03,2.5,8.3,M,0.0,M,,*5F\r\n",
            "$GNGSA,A,1,05,,,,,,,,,,,,3.7,2.5,2.8,1*3F\r\n",
            "$GNGSA,A,1,74,,,,,,,,,,,,3.7,2.5,2.8,2*3A\r\n",
            "$GNGSA,A,1,21,,,,,,,,,,,,3.7,2.5,2.8,4*3C\r\n",
            "$GPGSV,1,1,01,05,63,124,39,1*58\r\n",
            "$GLGSV,1,1,01,74,33,250,31,1*4F\r\n",
            "$GAGSV,1,1,01,12,15,012,27,7*43\r\n",
            "$GBGSV,1,1,01,21,79,000,42,1*4C\r\n",
            "$GNRMC,221523.00,A,0612.528000,S,10650.736000,E,2.33,270.50,141123,,,A*54\r\n"
    };
    private static final long[] PROBE_TRAVEL_NS = {72253448L, 78589000L, 82541951L, 68733697L};

    private static GnssFake.Snapshot probe() {
        GnssFake.Snapshot s = new GnssFake.Snapshot(4);
        int[] type = {GnssFake.T_GPS, GnssFake.T_GLO, GnssFake.T_GAL, GnssFake.T_BDS};
        int[] svid = {5, 10, 12, 21};
        float[] cn0 = {38.5f, 31.25f, 27f, 41.75f};
        float[] elev = {62.5f, 33.25f, 15f, 78.75f};
        float[] az = {123.5f, 250.25f, 12f, 359.5f};
        boolean[] used = {true, true, false, true};
        for (int i = 0; i < 4; i++) {
            s.type[i] = type[i];
            s.svid[i] = svid[i];
            s.cn0[i] = cn0[i];
            s.elev[i] = elev[i];
            s.az[i] = az[i];
            s.carrier[i] = i == 1 ? 1_602_562_500f : 1_575_420_000f;
            s.used[i] = used[i];
        }
        return s;
    }

    private static boolean valid(List<String> v) {
        if (v == null || v.size() < 3) return false;
        if (!v.get(0).startsWith("$GNGGA,") || !v.get(v.size() - 1).startsWith("$GNRMC,")) return false;
        for (String line : v) {
            if (!line.endsWith("\r\n")) return false;
            int star = line.lastIndexOf('*');
            if (star < 2 || star + 3 != line.length() - 2) return false;
            int cs = 0;
            for (int i = 1; i < star; i++) cs ^= line.charAt(i);
            if (!line.substring(star + 1, star + 3).equals(String.format("%02X", cs))) return false;
        }
        return true;
    }

    private static boolean same(GnssFake.Snapshot a, GnssFake.Snapshot b) {
        return a != null && b != null && a.n == b.n && Arrays.equals(a.type, b.type) && Arrays.equals(a.svid, b.svid)
                && Arrays.equals(a.cn0, b.cn0) && Arrays.equals(a.elev, b.elev) && Arrays.equals(a.az, b.az)
                && Arrays.equals(a.carrier, b.carrier) && Arrays.equals(a.used, b.used)
                && Arrays.equals(a.flags, b.flags) && Arrays.equals(a.baseband, b.baseband);
    }

    private static boolean sane(GnssFake.Snapshot s) {
        if (s == null || s.n < 5 || s.n > 20) return false;
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < s.n; i++) {
            int t = s.type[i];
            if (t != GnssFake.T_GPS && t != GnssFake.T_GLO && t != GnssFake.T_BDS && t != GnssFake.T_GAL) return false;
            if (!seen.add(t * 1000 + s.svid[i])) return false;
            if (s.cn0[i] < 14f || s.cn0[i] > 47f || s.elev[i] < 8f || s.elev[i] > 85f || s.az[i] < 0f || s.az[i] > 360f) return false;
            if (!(s.carrier[i] > 1.0e9f)) return false;
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        expect("library configcore dan lochooks termuat", ConfigNative.ensureLoaded() && LocationNative.ensureLoaded());
        expect("Config.ready(): codec, cfg, dan loc lolos verifikasi", Config.ready());
        SystemClock.fake = 123_456_000L;
        expect("verifikasi GnssFake: nativeOn == true", GnssFake.nativeOn());

        Random r = new Random(11);
        int badSnap = 0, badDet = 0, badIdle = 0, n = 400;
        for (int k = 0; k < n; k++) {
            long t = (long) (r.nextDouble() * 2.0e8);
            boolean fixed = (k & 1) == 0;
            GnssFake.Snapshot a = GnssFake.snapshotNative(t, fixed);
            GnssFake.Snapshot b = GnssFake.snapshotNative(t, fixed);
            if (!sane(a)) badSnap++;
            if (!same(a, b)) badDet++;
            if (a != null && !fixed && a.usedCount() != 0) badIdle++;
            if (a != null && fixed && a.usedCount() < 1) badIdle++;
        }
        expect("snapshot native lolos invarian (" + n + " kasus: jumlah, rentang, satelit unik)", badSnap == 0);
        expect("snapshot native deterministik untuk waktu yang sama", badDet == 0);
        expect("snapshot native: tanpa fix tidak ada satelit dipakai, dengan fix ada", badIdle == 0);

        GnssFake.Snapshot pr = probe();
        List<String> pn = GnssFake.nmeaNative(pr, 1_700_000_123_456L, -6.2088, 106.8456, 12.5f, true, 1.2f, true, 270.5f, true, 8.3);
        expect("NMEA native sama persis dengan vektor tetap", pn != null && pn.equals(Arrays.asList(PROBE_NMEA)));
        long[] pm = GnssFake.measNative(pr, 1_700_000_123_456L, 98_765_432_101_234L);
        boolean pmOk = pm != null && pm.length == 2 + 2 * pr.n
                && pm[0] == 1_384_035_341_456_000_000L && pm[1] == 252_941_456_000_000L;
        for (int i = 0; pmOk && i < pr.n; i++) {
            pmOk = pm[2 + 2 * i] == PROBE_TRAVEL_NS[i];
            double rate = Double.longBitsToDouble(pm[3 + 2 * i]);
            double base = ((pr.svid[i] * 37 % 1600) - 800) * 0.9;
            pmOk = pmOk && !Double.isNaN(rate) && Math.abs(rate - base) <= 3.0;
        }
        expect("measurements native: epoch dan waktu tempuh sama persis, laju pseudorange dekat dasar", pmOk);

        GnssFake.Snapshot base = GnssFake.snapshotNative(1_234_567L, true);
        GnssFake.Snapshot idle = GnssFake.snapshotNative(86_400L, false);
        int badNmea = 0, badMeas = 0;
        for (int k = 0; k < 600; k++) {
            GnssFake.Snapshot sn = (k & 1) == 0 ? base : idle;
            double lat = (r.nextDouble() - 0.5) * 180, lng = (r.nextDouble() - 0.5) * 360;
            float acc = (float) (r.nextDouble() * 60);
            boolean hs = r.nextBoolean(), hb = r.nextBoolean(), ha = r.nextBoolean();
            float spd = (float) (r.nextDouble() * 30), brg = (float) (r.nextDouble() * 360);
            double alt = (r.nextDouble() - 0.2) * 3000;
            if (k % 13 == 0) acc = 0f;
            if (k % 17 == 0) acc = 1000f;
            long ms = 946_684_800_000L + (long) (r.nextDouble() * 2.5e12);
            List<String> a = GnssFake.nmeaNative(sn, ms, lat, lng, acc, hs, spd, hb, brg, ha, alt);
            List<String> b = GnssFake.nmeaNative(sn, ms, lat, lng, acc, hs, spd, hb, brg, ha, alt);
            if (!valid(a) || !a.equals(b)) {
                badNmea++;
                if (badNmea <= 3) System.out.println("    NMEA k=" + k + " " + (a == null ? "null" : a.get(0).trim()));
            }
            long now = 1_500_000_000_000L + (long) (r.nextDouble() * 1.0e12);
            long tn = (long) (r.nextDouble() * 4.0e14);
            long[] m = GnssFake.measNative(sn, now, tn);
            boolean ok = m != null && m.length == 2 + 2 * sn.n && Arrays.equals(m, GnssFake.measNative(sn, now, tn));
            for (int i = 0; ok && i < sn.n; i++) {
                long tt = m[2 + 2 * i];
                double rate = Double.longBitsToDouble(m[3 + 2 * i]);
                double bs = ((sn.svid[i] * 37 % 1600) - 800) * 0.9;
                ok = tt > 50_000_000L && tt < 100_000_000L && !Double.isNaN(rate) && Math.abs(rate - bs) <= 3.0;
            }
            if (!ok) badMeas++;
        }
        expect("NMEA native valid dan deterministik (600 kasus: checksum, CRLF, GGA ... RMC)", badNmea == 0);
        expect("measurements native konsisten (600 kasus: panjang, waktu tempuh, laju, deterministik)", badMeas == 0);

        SystemClock.fake = 123_456_000L;
        GnssFake.Snapshot s = GnssFake.snapshot(true);
        expect("snapshot() memakai native dan sama dengan snapshotNative di detik yang sama",
                same(GnssFake.snapshotNative(123_456L, true), s) && sane(s));
        expect("flags() dan baseband() sama dengan isi snapshot",
                Arrays.equals(s.flags(), s.flags) && Arrays.equals(s.baseband(), s.baseband));
        Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8;spd=5;alt=100;altv=3;brg=45");
        List<String> v = GnssFake.nmea(s);
        expect("nmea(): kalimat valid (GGA ... RMC, checksum, CRLF)", valid(v));

        System.out.println(fail == 0 ? "SEMUA LOLOS" : fail + " GAGAL");
        if (fail != 0) System.exit(1);
    }
}
