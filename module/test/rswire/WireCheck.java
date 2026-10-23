package io.gpsz.zygote;

import android.location.Location;
import android.os.SystemClock;

/** args[0] = native | java | badseed: apa yang diharapkan dari pemilihan jalur. */
public class WireCheck {
    static int pass, fail;

    static void ok(String n, boolean c) {
        if (c) { pass++; System.out.println("  ok   " + n); } else { fail++; System.out.println("  FAIL " + n); }
    }

    static int counter(String cls) {
        try {
            return ((Integer) Class.forName("io.gpsz.zygote." + cls).getDeclaredField("calls").get(null));
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void main(String[] a) {
        boolean wantNative = a[0].equals("native");
        SystemClock.fake = 700_000;
        Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8;spd=5;alt=100;altv=3;brg=45");
        ok("locNative() sesuai mode " + a[0], Config.locNative() == wantNative);

        int before = counter("LocationNative");
        Config.snap = null;
        Config.Pt p = Config.snapshot();
        ok("snapshot menghasilkan titik dekat posisi config", Math.abs(p.lat + 7.7) < 0.001 && Math.abs(p.lng - 110.4) < 0.001);
        Location l = new Location("gps");
        Fix.apply(l, "gps", p);
        ok("apply gps: motion + bearing + extras terisi", l.hasAlt && l.hasSpd && l.hasVacc && l.hasSacc && l.hasBrg && l.hasBacc
                && l.extras != null && l.extras.getInt("satellites", -1) >= 5);
        Location n = new Location("network");
        Fix.apply(n, "network", p);
        ok("apply network: motion dibersihkan, akurasi >= 25", !n.hasAlt && !n.hasSpd && !n.hasBrg && !n.hasVacc && n.acc >= 25f);
        Config.Pt still = new Config.Pt(-7.7, 110.4, 0, 0f, Float.NaN, 100.0, 0f);
        Location q = new Location("passive");
        Fix.apply(q, "passive", still);
        ok("apply passive tanpa bearing: removeBearing dipanggil, tanpa extras", q.hasSpd && !q.hasBrg && !q.hasBacc && q.extras == null);

        long prev = 0;
        boolean mono = true, age = true, wall = true;
        for (int i = 0; i < 40; i++) {
            SystemClock.fake += 1000 + (i % 3) * 7;
            long nowNs = SystemClock.elapsedRealtimeNanos();
            Location s = new Location("gps");
            Fix.stamp(s, "gps");
            long ageNs = nowNs - s.ns;
            if (i > 0 && s.ns <= prev) mono = false;
            if (ageNs < 0 || ageNs > 200_000_000L) age = false;
            if (Math.abs(s.ms - (System.currentTimeMillis() - ageNs / 1_000_000L)) > 100) wall = false;
            prev = s.ns;
        }
        ok("stamp: monoton naik", mono);
        ok("stamp: umur fix 0..200 ms", age);
        ok("stamp: waktu dinding konsisten dengan umur", wall);

        long nowNs = SystemClock.elapsedRealtimeNanos();
        Location lk = Fix.lastKnown("gps");
        ok("lastKnown: memakai stamp terakhir bila < 5 dtk", lk.ns == prev);
        SystemClock.fake += 20_000;
        nowNs = SystemClock.elapsedRealtimeNanos();
        Location lk2 = Fix.lastKnown("gps");
        ok("lastKnown: sesudah 5 dtk jatuh ke grid fase, tidak di masa depan, umur < 1 dtk",
                lk2.ns <= nowNs && nowNs - lk2.ns < 1_000_000_000L);
        long phase = (long) (Config.hashUnit(901, 700) * 1e9); // Fix pertama dipakai saat jam palsu = 700000 ms
        ok("lastKnown: jatuh tepat di grid fase (PHASE_NS diteruskan)", Math.floorMod(lk2.ns, 1_000_000_000L) == phase);
        ok("lastKnown: waktu dinding konsisten", Math.abs(lk2.ms - (System.currentTimeMillis() - (nowNs - lk2.ns) / 1_000_000L)) <= 100);

        int used = counter("LocationNative") - before;
        if (wantNative) ok("jalur native benar-benar dipakai (" + used + " panggilan)", used >= 8);
        else if (a[0].equals("badseed")) ok("seed tidak cocok: tidak ada panggilan native sesudah verifikasi", used == 0);
        else ok("mode java: kelas asli tidak punya penghitung (tidak ada jalur native)", counter("LocationNative") == -1);


        System.out.println(fail == 0 ? "semua lulus (" + pass + ")" : "GAGAL " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
