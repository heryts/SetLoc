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
    System.load(System.getenv("LOCHOOKS_SO"));
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
    check("nativeApply via JNI sama dengan Fix.apply Java (4 provider x 4 waktu x 5 varian)", applyOk);

    String[] merged = LocationNative.nativeMergeProviders(new String[]{"passive", "network"});
    check("nativeMergeProviders: urutan dan isi", java.util.Arrays.equals(merged, new String[]{"passive", "network", "gps", "fused"}));
    check("nativeMergeProviders: array kosong", java.util.Arrays.equals(LocationNative.nativeMergeProviders(new String[0]), new String[]{"gps", "network", "fused"}));
    check("nativeIsSpoofProvider", LocationNative.nativeIsSpoofProvider("gps") && !LocationNative.nativeIsSpoofProvider("passive") && !LocationNative.nativeIsSpoofProvider(null));
    check("nativeRoute: Create, Set, Providers, Snap, Ext", LocationNative.nativeRoute(41) == 0 && LocationNative.nativeRoute(54) == 1 && LocationNative.nativeRoute(56) == 2 && LocationNative.nativeRoute(0) == 5 && LocationNative.nativeRoute(32) == 16);
    check("nativeIsSnapKind", LocationNative.nativeIsSnapKind(13) && LocationNative.nativeIsSnapKind(43) && !LocationNative.nativeIsSnapKind(11));
    check("nativeExtValue: BSSID palsu hanya saat hideNet", LocationNative.nativeExtValue(32, true, false) == 1 && LocationNative.nativeExtValue(32, false, true) == 0 && LocationNative.nativeExtValue(39, false, true) == 5);
    check("nativeActive", LocationNative.nativeActive(false, true, true, false) && !LocationNative.nativeActive(true, true, true, false));
    check("nativeSnapDecide: lewati bila mark sama, clamp waktu masa depan, tolak provider null",
        LocationNative.nativeSnapDecide(7, 9, "gps", true, 7, 9, 100) == -1
        && LocationNative.nativeSnapDecide(7, 9, "gps", false, 0, 0, 100) == 9
        && LocationNative.nativeSnapDecide(7, 200_000, "gps", false, 0, 0, 100) == 100
        && LocationNative.nativeSnapDecide(7, 9, null, false, 0, 0, 100) == -1);
    check("nativeIsFix", LocationNative.nativeIsFix(1, 1, "gps") && !LocationNative.nativeIsFix(0, 1, "gps") && !LocationNative.nativeIsFix(1, 1, ""));
    long[] a = LocationNative.nativeStamp("gps", 10_000_000_000L, 1_000_000L);
    long[] b = LocationNative.nativeStamp("gps", 11_000_000_000L, 1_001_000L);
    check("nativeStamp: ns tidak lebih dari now, monoton, time konsisten",
        a != null && b != null && a[0] <= 10_000_000_000L && b[0] > a[0] && b[0] <= 11_000_000_000L
        && a[1] == 1_000_000L - (10_000_000_000L - a[0]) / 1_000_000L);
    long[] k = LocationNative.nativeLastKnown("gps", 12_000_000_000L, 1_002_000L, 123L);
    check("nativeLastKnown memakai stamp terakhir bila < 5 dtk", k != null && k[0] == b[0]);
    long ph = LocationNative.nativePhaseNs(123_000_000_005L);
    check("nativePhaseNs dalam [0, 1 dtk)", ph >= 0 && ph < 1_000_000_000L);
    SystemClock.fake = 700000;
    Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8;spd=5;alt=100;altv=3;brg=45");
    Config.snap = null;
    Config.Pt jp = Config.snapshot();
    double[] sp = LocationNative.nativeSnapshot(-7.7, 110.4, 5f, 45f, 100.0, 3f, true, 8f, 700000);
    check("nativeSnapshot via JNI sama dengan Config.snapshot Java",
        near(jp.lat, sp[0], 1e-12) && near(jp.lng, sp[1], 1e-12) && near(jp.alt, sp[5], 1e-12) && near(jp.accJit, sp[6], 1e-6) && near(jp.spd, sp[3], 1e-9) && near(jp.brg, sp[4], 1e-9));
    check("nativeSnapFresh", LocationNative.nativeSnapFresh(100, 149) && !LocationNative.nativeSnapFresh(100, 150));
    System.out.println(fails == 0 ? "SEMUA LULUS" : "GAGAL: " + fails);
    System.exit(fails == 0 ? 0 : 1);
  }
}
