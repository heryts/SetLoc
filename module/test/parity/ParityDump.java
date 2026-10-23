package io.gpsz.zygote;

import android.location.Location;
import android.os.SystemClock;

public class ParityDump {
  static void out(String k, Object... v) {
    StringBuilder b = new StringBuilder(k);
    for (Object o : v) b.append('|').append(o);
    System.out.println(b);
  }

  static Config.Pt pt(double lat, double lng, long at, float spd, float brg, double alt, float jit) {
    return new Config.Pt(lat, lng, at, spd, brg, alt, jit);
  }

  public static void main(String[] x) {
    long[] as = {0, 1, 11, 20, 41, 100, 101, 901, -5};
    long[] bs = {0, 1, 2, 17, 1000, 123456789012L, -3};
    for (long a : as) for (long b : bs) out("h", a, b, Config.hashUnit(a, b));
    long[] gs = {31, 51};
    long[] gb = {0, 1, 50, 99999, 10000000001L};
    for (long s : gs) for (long b : gb) out("g", s, b, Config.gauss(s, b));
    long[] ts = {0, 1, 999, 1000, 29999, 30000, 35001, 123456, 500000, 36000123, 99999999};
    Config.alt = 100.0;
    Config.altVar = 3f;
    Config.acc = 10f;
    double[] lats = {-7.7, 0.0, 89.9999999, 45.5};
    for (long t : ts) {
      SystemClock.fake = t;
      for (double la : lats) {
        double[] o = Config.networkOffset(la);
        out("net", t, la, o[0], o[1]);
      }
      out("nacc", t, (double) Config.networkAccuracy(10f), (double) Config.networkAccuracy(1f));
      out("alt", t, Config.altitudeNow());
      int[] g = Config.gnssExtras();
      out("gnss", t, g[0], g[1], g[2]);
    }
    float[] jits = {0f, 0.35f, -0.7f, 1.0f, -1.05f};
    for (float j : jits) {
      Config.Pt p = pt(0, 0, 0, 0f, Float.NaN, 0, j);
      out("acc", (double) j, (double) Config.accNow(p), (double) Config.vAcc(p), (double) Config.sAcc(p), (double) Config.bAcc(p));
    }
    String[] provs = {"gps", "network", "fused", "passive"};
    float[][] vars = {{0f, Float.NaN, 0.2f}, {5f, 90f, 0.2f}, {0.3f, 10f, -0.6f}, {1f, Float.NaN, -10f}, {33f, 359.5f, 0.9f}};
    long[] ft = {0, 77000, 1234567};
    for (String pr : provs) for (long t : ft) for (float[] v : vars) {
      SystemClock.fake = t;
      Location l = new Location(pr);
      Fix.apply(l, pr, pt(-7.7, 110.4, t, v[0], v[1], 100.0, v[2]));
      int sat = -1, mean = -1, mx = -1;
      if (l.extras != null) {
        sat = l.extras.getInt("satellites", -1);
        mean = l.extras.getInt("meanCn0", -1);
        mx = l.extras.getInt("maxCn0", -1);
      }
      out("fix", pr, t, (double) v[0], (double) v[1], (double) v[2], l.lat, l.lng, (double) l.acc,
          l.hasAlt, l.alt, l.hasSpd, (double) l.spd, l.hasVacc, (double) l.vacc,
          l.hasSacc, (double) l.sacc, l.hasBrg, (double) l.brg, l.hasBacc, (double) l.bacc, sat, mean, mx);
    }
    String[] cfgs = {
      "enabled=1;lat=-7.7;lng=110.4;acc=10;random=0;rr=8;spd=0;alt=100;altv=0",
      "enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8;spd=5;alt=100;altv=3;brg=45",
      "enabled=1;lat=89.99999;lng=179.99999;acc=10;random=1;rr=30;spd=0;alt=5;altv=1",
      "enabled=1;lat=0;lng=-179.99999;acc=10;random=1;rr=50;spd=0;alt=5;altv=0"
    };
    int ci = 0;
    for (String c : cfgs) {
      Config.parse(c);
      long t = 700000;
      for (int i = 0; i < 12; i++) {
        SystemClock.fake = t;
        Config.snap = null;
        Config.Pt p = Config.snapshot();
        out("snap", ci, t, p.lat, p.lng, p.alt, (double) p.accJit, (double) p.spd);
        t += 51 + i * 313;
      }
      ci++;
    }
  }
}
