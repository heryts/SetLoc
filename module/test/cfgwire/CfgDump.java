package io.gpsz.zygote;

import android.os.SystemClock;
import java.util.Map;
import java.util.Random;

public class CfgDump {
    static String d(double v) { return Long.toHexString(Double.doubleToLongBits(v)); }

    static String f(float v) { return Integer.toHexString(Float.floatToIntBits(v)); }

    static void state(String tag) {
        Config.Pos q = Config.pos;
        System.out.println(tag + "|" + Config.enabled + "|" + Config.jitter + "|" + Config.playing + "|" + Config.tkMode + "|" + d(q.lat) + "|" + d(q.lng)
                + "|" + f(Config.acc) + "|" + f(Config.spd) + "|" + f(Config.brg) + "|" + d(Config.alt) + "|" + f(Config.altVar) + "|" + f(Config.rr));
    }

    static final String[] KEYS = {"enabled", "lat", "lng", "acc", "random", "spd", "brg", "alt", "altv", "rr", "pl", "tk", "junk", " lat ", "LAT", "enabled "};
    static final String[] NUMS = {"0", "-0", "-0.0", "1", "-7.5", "110.4", "90", "-90", "91", "180", "181", "-180.5", "1e3", "1E3", "1e-3", "-1e5", "99999", "100000", "100001",
        "1e9", "1e40", "-1e40", "1e400", "1e-400", "5.", " .5", ".5", "+3", "3d", "5f", "3D", "NaN", "Infinity", "-Infinity", "0x1p3", "abc", "", " ", "1_0", "360", "359.99999",
        "725", "-1", "-0.5", "100", "250", "10000", "10001", "12345678901234567890", "0.1", "0.7", "3.4028236e38", "3.4028235e38", "1.17549435E-38", "4.9e-324", "1e", "e5", "--1",
        "1.2.3", "٣", "1\t", "\t2", "+", "-", ".", "0.30000000000000004", "16777217", "8.0", "99.99", "100.0", "100.00001"};

    static String num(Random r) {
        int k = r.nextInt(4);
        if (k == 0) return Double.toString((r.nextDouble() - 0.5) * 400);
        if (k == 1) return String.format("%.6f", (r.nextDouble() - 0.5) * 200);
        if (k == 2) return Integer.toString(r.nextInt(2000) - 1000);
        return NUMS[r.nextInt(NUMS.length)];
    }

    static String cfg(Random r) {
        StringBuilder sb = new StringBuilder();
        int n = r.nextInt(12);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(r.nextInt(30) == 0 ? ";;" : ";");
            String k = KEYS[r.nextInt(KEYS.length)];
            if (r.nextInt(25) == 0) { sb.append(k); continue; }
            String v;
            switch (k.trim()) {
                case "enabled": case "random": case "pl": case "tk": v = r.nextInt(5) == 0 ? NUMS[r.nextInt(NUMS.length)] : (r.nextBoolean() ? "1" : "0"); break;
                default: v = num(r);
            }
            if (r.nextInt(15) == 0) v = " " + v + " ";
            sb.append(k).append('=').append(v);
        }
        return sb.toString();
    }

    static void parseFuzz() {
        Random r = new Random(20261023L);
        Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10");
        state("p0");
        String[] fixed = {null, "null", "", " null", "NULL", "abc", "enabled=1", "enabled=1;lat=1;lng=2;acc=3;spd=4;brg=5;alt=6;altv=7;rr=8;pl=1;tk=0;random=1",
            "enabled=1;spd=-0;altv=-0;rr=-0", "enabled=1;rr=-0.0;brg=-0", "lat=5;lat=abc;lat=7"};
        for (int i = 0; i < fixed.length; i++) { Config.parse(fixed[i]); state("pf" + i); }
        for (int i = 0; i < 6000; i++) {
            String s = r.nextInt(60) == 0 ? (r.nextBoolean() ? null : "null") : cfg(r);
            Config.parse(s);
            state("p" + (i + 1));
        }
    }

    static long fold(Config.Track t) {
        long h = 17;
        for (int i = 0; i < t.n; i++) {
            h = h * 31 + Double.doubleToLongBits(t.la[i]);
            h = h * 31 + Double.doubleToLongBits(t.lo[i]);
            h = h * 31 + Double.doubleToLongBits(t.al[i]);
            h = h * 31 + Float.floatToIntBits(t.sp[i]);
            h = h * 31 + Float.floatToIntBits(t.br[i]);
        }
        return h;
    }

    static String row(Random r, boolean junk) {
        StringBuilder sb = new StringBuilder(r.nextInt(5) == 0 ? "," : "");
        int fields = 5 + (r.nextInt(12) == 0 ? r.nextInt(3) : 0);
        if (junk && r.nextInt(3) == 0) fields = 2 + r.nextInt(3);
        for (int i = 0; i < fields; i++) {
            if (i > 0) sb.append(',');
            String v;
            switch (i) {
                case 0: v = String.format("%.6f", (r.nextDouble() - 0.5) * 170); break;
                case 1: v = String.format("%.6f", (r.nextDouble() - 0.5) * 360); break;
                case 2: v = r.nextBoolean() ? String.format("%.3f", r.nextDouble() * 30) : Float.toString(r.nextFloat() * 30); break;
                case 3: v = r.nextBoolean() ? String.format("%.2f", r.nextDouble() * 360) : Float.toString(r.nextFloat() * 360); break;
                default: v = r.nextBoolean() ? String.format("%.2f", r.nextDouble() * 900) : Double.toString(r.nextDouble() * 900);
            }
            if (junk && r.nextInt(14) == 0) v = NUMS[r.nextInt(NUMS.length)];
            sb.append(v);
        }
        if (r.nextInt(10) == 0) sb.append(',');
        return sb.toString();
    }

    static String trackStr(Random r, boolean junk) {
        int n = 1 + r.nextInt(40);
        StringBuilder sb = new StringBuilder();
        long t0 = 100000 + r.nextInt(1000000);
        int k = junk && r.nextInt(8) == 0 ? new int[]{0, -5, 0}[r.nextInt(3)] : 100 + r.nextInt(2000);
        sb.append("t=").append(t0);
        if (r.nextBoolean()) sb.append(";a=").append(t0 - r.nextInt(5000) + r.nextInt(3000));
        sb.append(";k=").append(junk && r.nextInt(10) == 0 ? new String[]{"3000000000", "abc", "5d", " 5", "+7", "1e3", ""}[r.nextInt(7)] : Integer.toString(k));
        if (junk && r.nextInt(10) == 0) sb.append(";junk").append(r.nextBoolean() ? ";t=" + NUMS[r.nextInt(NUMS.length)] : "");
        sb.append(";d=");
        for (int i = 0; i < n; i++) sb.append(row(r, junk)).append('|');
        if (junk && r.nextInt(6) == 0) {
            int p = r.nextInt(sb.length());
            if (r.nextBoolean()) sb.deleteCharAt(p); else sb.setCharAt(p, "=;|,x5 ".charAt(r.nextInt(7)));
        }
        return sb.toString();
    }

    static void trackParseFuzz() {
        Random r = new Random(77L);
        String[] fixed = {"", "t=1000;k=1000;d=1,2,3,4,5", "t=1000;k=1000;d=", "k=1000;d=1,2,3,4,5", "t=1;d=1,2,3,4,5", "t=1;k=5", "t=5;k=5;d=|||", "t=5;k=5;d=,,,|,1,2,3,4,5",
            "junk;t=5;k=5;d=1,2,3,4,5", "t=5;k=5;d=1,2,3,4,5,", "t=5;k=5;d=1,2,3,4", "t=5;k=5;d=1,2,3,4,", "t=5;k=5;d=1,,3,4,5", "t=+5;k=+5;d=+1,-2,3,4,5",
            "t=5;k=5;d=1,2,3,4,5;d=6,7,8,9,10", "t=5;a=abc;k=5;d=1,2,3,4,5", "t=9223372036854775807;k=5;d=1,2,3,4,5", "t=9223372036854775808;k=5;d=1,2,3,4,5",
            "t=5;k=2147483647;d=1,2,3,4,5", "t=5;k=2147483648;d=1,2,3,4,5", "t=5;k=5;d=1e2,2E-1,3.,.4,-.5e+1"};
        for (int i = 0; i < fixed.length; i++) printTrack("tf" + i, Config.parseTrack(fixed[i]));
        for (int i = 0; i < 3000; i++) printTrack("tp" + i, Config.parseTrack(trackStr(r, i % 2 == 1)));
    }

    static void printTrack(String tag, Config.Track t) {
        if (t == null) System.out.println(tag + "|null");
        else System.out.println(tag + "|" + t.t0 + "|" + t.wrote + "|" + t.tk + "|" + t.n + "|" + Long.toHexString(fold(t)));
    }

    static void trackApplyFuzz() {
        Map<String, String> st = android.provider.Settings.Global.store;
        Random r = new Random(4242L);
        for (int round = 0; round < 60; round++) {
            String trk = trackStr(r, false);
            Config.Track t = Config.parseTrack(trk);
            long base = t == null ? 100000 : t.t0;
            long span = t == null ? 10000 : (long) t.n * t.tk;
            String cfg = "enabled=1;lat=-7.0;lng=110.0;acc=10;random=0;spd=0;alt=5;altv=0;rr=8;tk=1;pl=1";
            SystemClock.fake = base - 50;
            st.put(Config.KEY, Config.enc(cfg));
            st.put(Config.KEY_TRK, trk);
            Config.refresh(null, null, null);
            state("ra" + round);
            long now = base - 500;
            for (int i = 0; i < 70; i++) {
                int pick = r.nextInt(10);
                if (pick == 0) now -= r.nextInt(400);
                else if (pick < 3) now += 3000 + r.nextInt(80000);
                else now += 50 + r.nextInt((int) Math.max(60, span / 20 + 1));
                SystemClock.fake = now;
                Config.tick();
                state("rt" + round + "_" + i);
            }
            SystemClock.fake = now + 1000;
            st.put(Config.KEY_TRK, trackStr(r, false));
            Config.refresh(null, null, null);
            state("rn" + round);
            SystemClock.fake = now + 1100;
            st.put(Config.KEY, Config.enc("enabled=1;lat=1;lng=2;tk=0"));
            Config.refresh(null, null, null);
            SystemClock.fake = now + 1200;
            Config.tick();
            state("rz" + round);
        }
    }

    static void bigTrack() {
        Map<String, String> st = android.provider.Settings.Global.store;
        Random r = new Random(9L);
        int n = 200_001;
        StringBuilder sb = new StringBuilder("t=500000;k=100;d=");
        for (int i = 0; i < n; i++) sb.append(String.format("%.6f,%.6f,%.2f,%.1f,%.1f|", -7.0 - i * 1e-6, 110.0 + i * 1e-6, r.nextDouble() * 20, r.nextDouble() * 360, 5 + r.nextDouble()));
        SystemClock.fake = 499_900;
        st.put(Config.KEY, Config.enc("enabled=1;lat=-7.0;lng=110.0;acc=10;tk=1;pl=1"));
        st.put(Config.KEY_TRK, sb.toString());
        Config.refresh(null, null, null);
        state("bg0");
        for (int i = 0; i < 40; i++) {
            SystemClock.fake = 500_000 + (long) i * 7919;
            Config.tick();
            state("bg" + (i + 1));
        }
    }

    static void beatFuzz() {
        Random r = new Random(555L);
        long[] steps = {0, 1, 250, 999, 1000, 1001, 2999, 3000, 3001, 5000, 9999, 10000, 10001, 59999, 60000, 60001, 120000};
        SystemClock.fake = 1_000_000;
        Config.parse(null);
        String beat = "1";
        for (int i = 0; i < 4000; i++) {
            int op = r.nextInt(10);
            if (op == 0) Config.parse("enabled=1;pl=" + (r.nextInt(4) == 0 ? "0" : "1") + ";lat=1;lng=2;spd=5");
            else if (op == 1 && r.nextInt(3) == 0) Config.parse(r.nextBoolean() ? null : "null");
            else {
                SystemClock.fake += steps[r.nextInt(steps.length)];
                if (op >= 7) beat = r.nextInt(5) == 0 ? null : Integer.toString(r.nextInt(3));
                Config.checkBeat(beat);
            }
            state("b" + i);
        }
    }

    static String plainCfg(Random r) {
        String[] keys = {"enabled", "lat", "lng", "acc", "random", "spd", "brg", "alt", "altv", "rr", "pl", "tk", "x"};
        StringBuilder sb = new StringBuilder();
        int n = 1 + r.nextInt(12);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(';');
            String k = keys[r.nextInt(keys.length)];
            String v = k.equals("enabled") || k.equals("random") || k.equals("pl") || k.equals("tk") ? (r.nextBoolean() ? "1" : "0")
                    : k.equals("x") ? "zzz" : r.nextBoolean() ? Double.toString((r.nextDouble() - 0.5) * 1000) : Integer.toString(r.nextInt(400) - 200);
            sb.append(' ').append(k).append(" = ").append(v).append(' ');
        }
        return sb.toString();
    }

    static void nativeAccepts() {
        if (!Config.cfgNative()) return;
        Random r = new Random(31L);
        String[] valid = {"t=1000;k=1000;d=1,2,3,4,5", "junk;t=5;k=5;d=1,2,3,4,5", "t=5;k=5;d=1,2,3,4,5,", "t=+5;k=+5;d=+1,-2,3,4,5", "t=5;k=5;d=1e2,2E-1,3.,.4,-.5e+1",
            "t=5;k=5;d=,,,|,1,2,3,4,5", "t=5;k=5;d=1,2,3,4,5;d=6,7,8,9,10", "t=5;k=5;d=1,2,3,4,5,6,7", "t=5;a=7;k=5;d=1,2,3,4,5"};
        for (String v : valid) {
            if (ConfigNative.nativeParseTrack(v, new long[4]) == null) System.out.println("FAIL native menolak track valid: " + v);
        }
        for (int i = 0; i < 1500; i++) {
            String v = trackStr(r, false);
            if (ConfigNative.nativeParseTrack(v, new long[4]) == null) System.out.println("FAIL native menolak track valid acak #" + i);
        }
        for (int i = 0; i < 3000; i++) {
            String v = plainCfg(r);
            if (ConfigNative.nativeParse(v, 0.0, 0.0, 10f) == null) System.out.println("FAIL native menolak config valid acak: " + v);
        }
        for (String v : new String[]{"", "abc", "enabled=1", " null", "a=b;;c"}) {
            if (ConfigNative.nativeParse(v, 0.0, 0.0, 10f) == null) System.out.println("FAIL native menolak config valid: " + v);
        }
    }

    static void beatScenarios() {
        SystemClock.fake = 5_000_000;
        Config.parse("enabled=1;pl=1;lat=1;lng=2;spd=5");
        Config.checkBeat("7");
        for (int i = 0; i < 11; i++) { SystemClock.fake += 1000; Config.checkBeat("7"); }
        state("s0");
        Config.parse(null);
        Config.parse("enabled=1;pl=1;lat=1;lng=2;spd=5");
        state("s1");
        SystemClock.fake += 1000;
        Config.checkBeat("7");
        state("s2");
        for (int i = 0; i < 12; i++) { SystemClock.fake += 1000; Config.checkBeat("7"); }
        state("s3");
        Config.parse("enabled=1;pl=0;lat=1;lng=2;spd=5");
        SystemClock.fake += 1000;
        Config.checkBeat("7");
        Config.parse("enabled=1;pl=1;lat=1;lng=2;spd=5");
        SystemClock.fake += 1000;
        Config.checkBeat("7");
        state("s4");
        for (int i = 0; i < 61; i++) { SystemClock.fake += 1000; Config.checkBeat("7"); }
        Config.parse("enabled=1;pl=1;lat=1;lng=2;spd=5");
        state("s5");
        Config.parse(null);
        Config.parse("enabled=1;pl=1;lat=1;lng=2;spd=5");
        SystemClock.fake += 1000;
        Config.checkBeat("7");
        Config.parse("enabled=1;pl=1;lat=1;lng=2;spd=5");
        state("s6");
    }

    public static void main(String[] a) {
        nativeAccepts();
        beatScenarios();
        parseFuzz();
        trackParseFuzz();
        trackApplyFuzz();
        bigTrack();
        beatFuzz();
    }
}
