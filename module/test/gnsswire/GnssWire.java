package io.gpsz.zygote;

import android.os.SystemClock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

public final class GnssWire {
    private static int fail;

    private static void expect(String what, boolean cond) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) fail++;
    }

    private static int calls(boolean fake) throws Exception {
        if (!fake) return 0;
        Field f = LocationNative.class.getDeclaredField("calls");
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static void breakIt(int mode) throws Exception {
        Field f = LocationNative.class.getDeclaredField("broken");
        f.setAccessible(true);
        f.setInt(null, mode);
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

    public static void main(String[] args) throws Exception {
        String[] spec = (args.length > 0 ? args[0] : "fake:on").split(":");
        boolean fake = spec[0].equals("fake");
        if (!fake) expect("library lochooks termuat", LocationNative.ensureLoaded());
        SystemClock.fake = 123_456_000L;

        {
            Random r = new Random(11);
            int badSnap = 0, n = 0;
            for (int k = 0; k < 400; k++) {
                long t = (long) (r.nextDouble() * 2.0e8);
                boolean fixed = (k & 1) == 0;
                GnssFake.Snapshot j = GnssRef.snapshotJava(t, fixed);
                double[] a = LocationNative.nativeGnssSnapshot(t, fixed);
                n++;
                if (a == null || a.length != 1 + j.n * GnssFake.STRIDE_OUT) { badSnap++; continue; }
                GnssFake.Snapshot b = new GnssFake.Snapshot((int) a[0]);
                b.flagsRaw = new int[b.n];
                b.basebandRaw = new float[b.n];
                for (int i = 0; i < b.n; i++) {
                    int o = 1 + i * GnssFake.STRIDE_OUT;
                    b.type[i] = (int) a[o];
                    b.svid[i] = (int) a[o + 1];
                    b.cn0[i] = (float) a[o + 2];
                    b.elev[i] = (float) a[o + 3];
                    b.az[i] = (float) a[o + 4];
                    b.carrier[i] = (float) a[o + 5];
                    b.used[i] = a[o + 6] != 0.0;
                    b.flagsRaw[i] = (int) a[o + 7];
                    b.basebandRaw[i] = (float) a[o + 8];
                }
                if (!GnssRef.sameSnapshot(j, b)) badSnap++;
            }
            expect("snapshot native sama dengan Java (" + n + " kasus, fixed dan tidak)", badSnap == 0);

            GnssFake.Snapshot base = GnssRef.snapshotJava(1_234_567L, true);
            GnssFake.Snapshot idle = GnssRef.snapshotJava(86_400L, false);
            int badNmea = 0, badMeas = 0;
            for (int k = 0; k < 600; k++) {
                GnssFake.Snapshot sn = (k & 1) == 0 ? base : idle;
                double lat = (r.nextDouble() - 0.5) * 180, lng = (r.nextDouble() - 0.5) * 360;
                float acc = (float) (r.nextDouble() * 60);
                boolean hs = r.nextBoolean(), hb = r.nextBoolean(), ha = r.nextBoolean();
                float spd = (float) (r.nextDouble() * 30), brg = (float) (r.nextDouble() * 360);
                double alt = (r.nextDouble() - 0.2) * 3000;
                if (k % 7 == 0) alt = Math.round(alt * 10) / 10.0 + 0.05;
                if (k % 11 == 0) spd = (float) (Math.round(spd * 100) / 100.0 + 0.005);
                if (k % 13 == 0) acc = 0f;
                if (k % 17 == 0) acc = 1000f;
                long ms = 946_684_800_000L + (long) (r.nextDouble() * 2.5e12);
                List<String> j = GnssRef.nmeaJava(sn, ms, lat, lng, acc, hs, spd, hb, brg, ha, alt);
                List<String> nn = GnssFake.nmeaNative(sn, ms, lat, lng, acc, hs, spd, hb, brg, ha, alt);
                if (nn == null || !j.equals(nn)) {
                    badNmea++;
                    if (badNmea <= 3) System.out.println("    beda NMEA k=" + k + "\n      java  " + j.get(0).trim() + "\n      native " + (nn == null ? "null" : nn.get(0).trim()));
                }
                long now = 1_500_000_000_000L + (long) (r.nextDouble() * 1.0e12);
                long tn = (long) (r.nextDouble() * 4.0e14);
                long[] mj = GnssRef.measJava(sn, now, tn);
                long[] mn = GnssFake.measNative(sn, now, tn);
                if (mn == null || !java.util.Arrays.equals(mj, mn)) badMeas++;
            }
            expect("NMEA native sama persis dengan Java (600 kasus: waktu, koordinat, pembulatan, hdop, knot)", badNmea == 0);
            expect("angka measurements native sama persis dengan Java (600 kasus)", badMeas == 0);

            int c0 = calls(fake);
            GnssFake.Snapshot s = GnssFake.snapshot(true);
            expect("snapshot() memakai native (hitungan panggilan naik)", !fake || calls(fake) > c0);
            expect("snapshot() sama dengan jalur Java di waktu yang sama", GnssRef.sameSnapshot(GnssRef.snapshotJava(123_456L, true), s));
            expect("flags() dan baseband() dari native sama dengan hitungan Java",
                java.util.Arrays.equals(s.flags(), GnssRef.snapshotJava(123_456L, true).flags())
                    && java.util.Arrays.equals(s.baseband(), GnssRef.snapshotJava(123_456L, true).baseband()));
            c0 = calls(fake);
            List<String> v = GnssFake.nmea(s);
            expect("nmea(): kalimat valid (GGA ... RMC, checksum, CRLF)", valid(v));
            expect("nmea() memakai native", !fake || calls(fake) > c0);
        }

        if (fake) {
            String[] names = {"null", "melempar", "array salah ukuran"};
            for (int mode = 1; mode <= 3; mode++) {
                breakIt(mode);
                GnssFake.Snapshot s = GnssFake.snapshot(true);
                expect("native " + names[mode - 1] + ": snapshot() kosong, tidak melempar", s != null && s.n == 0);
                expect("native " + names[mode - 1] + ": nmea() kosong, tidak melempar", GnssFake.nmea(GnssRef.snapshotJava(123_456L, true)).isEmpty());
                expect("native " + names[mode - 1] + ": measNative null", GnssFake.measNative(GnssRef.snapshotJava(123_456L, true), 1_700_000_000_000L, 5_000_000_000L) == null);
                expect("native " + names[mode - 1] + ": measurementsEvent null", GnssFake.measurementsEvent(GnssRef.snapshotJava(123_456L, true)) == null);
            }
            breakIt(0);
        }
        System.out.println(fail == 0 ? "SEMUA LOLOS" : fail + " GAGAL");
        if (fail != 0) System.exit(1);
    }
}
