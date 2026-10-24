package io.gpsz.zygote;

import android.location.Location;
import android.os.SystemClock;

public class JniCheck {
  static int fails = 0;

  static void check(String n, boolean ok) {
    if (!ok) { fails++; System.out.println("FAIL " + n); } else System.out.println("ok   " + n);
  }

  static boolean near(double a, double b, double tol) {
    return (Double.isNaN(a) && Double.isNaN(b)) || Math.abs(a - b) <= tol * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
  }

  public static void main(String[] x) {
    check("library configcore dan lochooks termuat", ConfigNative.ensureLoaded() && LocationNative.ensureLoaded());
    check("Config.ready(): codec, cfg, dan loc lolos verifikasi", Config.ready());
    String[] provs = {"gps", "network", "fused", "passive"};
    float[][] vars = {{0f, Float.NaN, 0.2f}, {5f, 90f, 0.2f}, {0.3f, 10f, -0.6f}, {1f, Float.NaN, -10f}, {33f, 359.5f, 0.9f}};
    long[] ts = {0, 77000, 1234567, 99999999};
    boolean applyOk = true;
    for (String pr : provs) for (long t : ts) for (float[] v : vars) {
      SystemClock.fake = t;
      Location l = new Location(pr);
      Fix.apply(l, pr, new Config.Pt(-7.7, 110.4, t, v[0], v[1], 100.0, v[2]));
      double[] r = LocationNative.nativeApply(pr, -7.7, 110.4, v[0], v[1], 100.0, v[2], 10f, t);
      int f = (int) r[3];
      boolean ok = near(l.lat, r[0], 1e-12) && near(l.lng, r[1], 1e-12) && near(l.acc, r[2], 2e-6)
          && l.hasAlt == ((f & 1) != 0) && l.hasSpd == ((f & 1) != 0) && l.hasVacc == ((f & 1) != 0) && l.hasSacc == ((f & 1) != 0)
          && l.hasBrg == ((f & 2) != 0) && l.hasBacc == ((f & 2) != 0)
          && (l.extras != null) == ((f & 4) != 0);
      if (ok && (f & 1) != 0) {
        ok = near(l.alt, r[4], 1e-12) && near(l.spd, r[5], 1e-6) && near(l.vacc, r[6], 1e-6) && near(l.sacc, r[7], 1e-6);
        if (ok && (f & 2) != 0) ok = near(l.brg, r[8], 1e-6) && near(l.bacc, r[9], 1e-6);
      }
      if (ok && (f & 4) != 0) {
        ok = l.extras.getInt("satellites", -1) == (int) r[10] && l.extras.getInt("meanCn0", -1) == (int) r[11] && l.extras.getInt("maxCn0", -1) == (int) r[12];
      }
      if (!ok) { applyOk = false; System.out.println("  beda: " + pr + " t=" + t + " v=" + java.util.Arrays.toString(v)); }
    }
    check("nativeApply via JNI sama dengan Fix.apply (4 provider x 4 waktu x 5 varian)", applyOk);

    boolean intoOk = true;
    double[] buf = new double[13];
    for (String pr : provs) for (long t : ts) for (float[] v : vars) {
      double[] r = LocationNative.nativeApply(pr, -7.7, 110.4, v[0], v[1], 100.0, v[2], 10f, t);
      java.util.Arrays.fill(buf, -999.0);
      boolean ok = LocationNative.nativeApplyInto(pr, -7.7, 110.4, v[0], v[1], 100.0, v[2], 10f, t, buf);
      for (int i = 0; i < 13 && ok; i++) if (Double.doubleToLongBits(r[i]) != Double.doubleToLongBits(buf[i])) ok = false;
      if (!ok) { intoOk = false; System.out.println("  beda into: " + pr + " t=" + t); }
    }
    check("nativeApplyInto sama bit-per-bit dengan nativeApply", intoOk);
    check("nativeApplyInto menolak array null dan array pendek",
        !LocationNative.nativeApplyInto("gps", 0, 0, 0f, 0f, 0, 0f, 10f, 0L, null)
        && !LocationNative.nativeApplyInto("gps", 0, 0, 0f, 0f, 0, 0f, 10f, 0L, new double[12]));

    long[] a = LocationNative.nativeStamp("gps", 10_000_000_000L, 1_000_000L);
    long[] b = LocationNative.nativeStamp("gps", 11_000_000_000L, 1_001_000L);
    check("nativeStamp: ns tidak lebih dari now, monoton, time konsisten",
        a != null && b != null && a[0] <= 10_000_000_000L && b[0] > a[0] && b[0] <= 11_000_000_000L
        && a[1] == 1_000_000L - (10_000_000_000L - a[0]) / 1_000_000L);
    long[] k = LocationNative.nativeLastKnown("gps", 12_000_000_000L, 1_002_000L, 123L);
    check("nativeLastKnown memakai stamp terakhir bila < 5 dtk", k != null && k[0] == b[0]);
    long[] pb = new long[2];
    boolean si = LocationNative.nativeStampInto("gps", 13_000_000_000L, 1_003_000L, pb);
    check("nativeStampInto: ns tidak lebih dari now, monoton terhadap stamp sebelumnya, time konsisten",
        si && pb[0] <= 13_000_000_000L && pb[0] > b[0] && pb[1] == 1_003_000L - (13_000_000_000L - pb[0]) / 1_000_000L);
    long[] pk = new long[2];
    boolean ki = LocationNative.nativeLastKnownInto("gps", 14_000_000_000L, 1_004_000L, 123L, pk);
    long[] k2 = LocationNative.nativeLastKnown("gps", 14_000_000_000L, 1_004_000L, 123L);
    check("nativeLastKnownInto sama dengan nativeLastKnown", ki && k2 != null && pk[0] == k2[0] && pk[1] == k2[1]);
    check("nativeStampInto dan nativeLastKnownInto menolak null dan array pendek",
        !LocationNative.nativeStampInto("gps", 1L, 1L, null) && !LocationNative.nativeStampInto("gps", 1L, 1L, new long[1])
        && !LocationNative.nativeLastKnownInto("gps", 1L, 1L, 1L, null) && !LocationNative.nativeLastKnownInto("gps", 1L, 1L, 1L, new long[1]));
    long ph = LocationNative.nativePhaseNs(123_000_000_005L);
    check("nativePhaseNs dalam [0, 1 dtk)", ph >= 0 && ph < 1_000_000_000L);
    double hu = LocationNative.nativeHashUnit(901, 123L);
    check("nativeHashUnit dalam [0, 1) dan deterministik", hu >= 0.0 && hu < 1.0 && hu == LocationNative.nativeHashUnit(901, 123L));
    check("nativePhaseNs sama dengan nativeHashUnit(901, detik) dalam ns", LocationNative.nativePhaseNs(123_000_000_005L) == (long) (LocationNative.nativeHashUnit(901, 123L) * 1e9));
    SystemClock.fake = 700000;
    Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8;spd=5;alt=100;altv=3;brg=45");
    Config.snap = null;
    Config.Pt jp = Config.snapshot();
    double[] sp = LocationNative.nativeSnapshot(-7.7, 110.4, 5f, 45f, 100.0, 3f, true, 8f, 700000);
    check("nativeSnapshot via JNI sama dengan Config.snapshot",
        near(jp.lat, sp[0], 1e-12) && near(jp.lng, sp[1], 1e-12) && near(jp.alt, sp[5], 1e-12) && near(jp.accJit, sp[6], 1e-6) && near(jp.spd, sp[3], 1e-9) && near(jp.brg, sp[4], 1e-9));
    System.out.println(fails == 0 ? "SEMUA LULUS" : "GAGAL: " + fails);
    System.exit(fails == 0 ? 0 : 1);
  }
}
