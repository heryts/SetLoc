package io.gpsz.zygote;

import static io.gpsz.zygote.GnssFake.*;

import android.os.SystemClock;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class GnssRef {
    private static final long GPS_EPOCH_MS = 315_964_800_000L;
    private static final int LEAP_S = 18;
    private static final long WEEK_NS = 604_800L * 1_000_000_000L;
    private static final double LIGHT = 299_792_458.0;

    private static final class Sat {
        final int type, svid;
        final double az0, rate, phase, period;

        Sat(int type, int svid, double az0, double rate, double phase, double period) {
            this.type = type;
            this.svid = svid;
            this.az0 = az0;
            this.rate = rate;
            this.phase = phase;
            this.period = period;
        }
    }

    private static Sat[] buildPool() {
        java.util.Random r = new java.util.Random(Config.seed());
        int[][] plan = {{T_GPS, 32, 11}, {T_GLO, 24, 6}, {T_GAL, 36, 6}, {T_BDS, 37, 6}};
        List<Sat> out = new ArrayList<>();
        for (int[] p : plan) {
            List<Integer> ids = new ArrayList<>();
            for (int i = 1; i <= p[1]; i++) ids.add(i);
            Collections.shuffle(ids, r);
            for (int i = 0; i < p[2]; i++) {
                double rate = (r.nextBoolean() ? 1 : -1) * (0.004 + r.nextDouble() * 0.006);
                out.add(new Sat(p[0], ids.get(i), r.nextDouble() * 360.0, rate,
                        r.nextDouble() * 2 * Math.PI, 600 + r.nextDouble() * 900));
            }
        }
        return out.toArray(new Sat[0]);
    }

    private static float carrier(int type, int svid) {
        switch (type) {
            case T_GLO: return 1_602_000_000f + (svid % 14 - 7) * 562_500f;
            case T_BDS: return 1_561_098_000f;
            default: return 1_575_420_000f;
        }
    }

    private static final Sat[] POOL = buildPool();

    static boolean sameSnapshot(Snapshot a, Snapshot b) {
        if (a == null || b == null || a.n != b.n) return false;
        int[] fa = a.flags(), fb = b.flags();
        float[] ba = a.baseband(), bb = b.baseband();
        for (int i = 0; i < a.n; i++) {
            if (a.type[i] != b.type[i] || a.svid[i] != b.svid[i] || a.used[i] != b.used[i]) return false;
            if (fa[i] != fb[i]) return false;
            if (Math.abs(a.cn0[i] - b.cn0[i]) > 0.01f || Math.abs(a.elev[i] - b.elev[i]) > 0.01f) return false;
            if (Math.abs(a.az[i] - b.az[i]) > 0.05f || Math.abs(ba[i] - bb[i]) > 0.01f) return false;
            if (a.carrier[i] != b.carrier[i]) return false;
        }
        return true;
    }

    static Snapshot snapshotJava(long nowSec, boolean fixed) {
        int[] g = Config.gnssExtrasAt(nowSec);
        int total = POOL.length;
        int want = Math.min(total, g[0] + 3 + (g[0] & 1));
        double t = nowSec;
        final double[] el = new double[total];
        final double[] score = new double[total];
        Integer[] order = new Integer[total];
        for (int i = 0; i < total; i++) {
            Sat s = POOL[i];
            el[i] = 8.0 + 77.0 * (0.5 + 0.5 * Math.sin(s.phase + t / s.period));
            score[i] = el[i] + (Config.hashUnit(s.type * 1000L + s.svid, nowSec / 5) - 0.5) * 20.0;
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(score[b], score[a]));
        Snapshot out = new Snapshot(want);
        int usedLeft = fixed ? g[0] : 0;
        Integer[] usedOrder = null;
        if (usedLeft > 0 && want > 0) {
            usedOrder = new Integer[want];
            for (int k = 0; k < want; k++) usedOrder[k] = k;
            final long us = nowSec;
            Arrays.sort(usedOrder, (a, b) -> {
                Sat sa = POOL[order[a]];
                Sat sb = POOL[order[b]];
                double wa = el[order[a]] + (Config.hashUnit(sa.type * 100L + sa.svid, us) - 0.5) * 15.0;
                double wb = el[order[b]] + (Config.hashUnit(sb.type * 100L + sb.svid, us) - 0.5) * 15.0;
                return Double.compare(wb, wa);
            });
        }
        boolean[] usedFlag = new boolean[want];
        if (usedOrder != null) {
            for (int k = 0; k < usedLeft && k < want; k++) usedFlag[usedOrder[k]] = true;
        }
        for (int k = 0; k < want; k++) {
            int i = order[k];
            Sat s = POOL[i];
            double norm = (el[i] - 8.0) / 77.0;
            double c = g[1] - 7.0 + 14.0 * norm + (Config.hashUnit(s.type * 100L + s.svid, (long) t) - 0.5) * 2.4;
            out.type[k] = s.type;
            out.svid[k] = s.svid;
            out.cn0[k] = (float) Math.max(14.0, Math.min(47.0, c));
            out.elev[k] = (float) el[i];
            out.az[k] = (float) (((s.az0 + s.rate * t) % 360.0 + 360.0) % 360.0);
            out.carrier[k] = carrier(s.type, s.svid);
            out.used[k] = usedFlag[k];
        }
        return out;
    }

    private static String talker(int type) {
        switch (type) {
            case T_GLO: return "GL";
            case T_GAL: return "GA";
            case T_BDS: return "GB";
            default: return "GP";
        }
    }

    private static int systemId(int type) {
        switch (type) {
            case T_GLO: return 2;
            case T_GAL: return 3;
            case T_BDS: return 4;
            default: return 1;
        }
    }

    private static int signalId(int type) {
        return type == T_GAL ? 7 : 1;
    }

    private static int nmeaId(int type, int svid) {
        return type == T_GLO ? 64 + svid : svid;
    }

    private static String sentence(String body) {
        int cs = 0;
        for (int i = 0; i < body.length(); i++) cs ^= body.charAt(i);
        return "$" + body + "*" + String.format(Locale.US, "%02X", cs) + "\r\n";
    }

    private static String coord(double v, boolean lat) {
        long t = Math.round(Math.abs(v) * 60_000_000.0);
        long m = t % 60_000_000L;
        return String.format(Locale.US, lat ? "%02d%02d.%06d" : "%03d%02d.%06d",
                t / 60_000_000L, m / 1_000_000L, m % 1_000_000L);
    }

    static List<String> nmeaJava(Snapshot s, long ms, double lat, double lng, float acc,
                                 boolean hasSpd, float spd, boolean hasBrg, float brg,
                                 boolean hasAlt, double alt0) {
        ms -= ms % 1000L;
        ZonedDateTime z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
        String hms = String.format(Locale.US, "%02d%02d%02d.00", z.getHour(), z.getMinute(), z.getSecond());
        String dmy = String.format(Locale.US, "%02d%02d%02d", z.getDayOfMonth(), z.getMonthValue(), z.getYear() % 100);
        char ns = lat >= 0 ? 'N' : 'S', ew = lng >= 0 ? 'E' : 'W';
        double hdop = Math.max(0.7, Math.min(5.0, acc / 5.0));
        double vdop = hdop * 1.1;
        double pdop = Math.sqrt(hdop * hdop + vdop * vdop);
        double knots = hasSpd ? spd * 1.943844 : 0.0;
        double course = hasBrg ? brg : 0.0;
        double alt = hasAlt ? alt0 : 0.0;
        int used = s.usedCount();
        int fixQuality = used > 0 ? 1 : 0;
        int fixType = used >= 4 ? 3 : 1;
        List<String> out = new ArrayList<>();
        out.add(sentence(String.format(Locale.US, "GNGGA,%s,%s,%c,%s,%c,%d,%02d,%.1f,%.1f,M,%.1f,M,,",
                hms, coord(lat, true), ns, coord(lng, false), ew, fixQuality, used, hdop, alt, 0.0)));
        int[] groups = {T_GPS, T_GLO, T_GAL, T_BDS};
        for (int type : groups) {
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < s.n; i++) if (s.type[i] == type && s.used[i]) ids.add(nmeaId(type, s.svid[i]));
            if (ids.isEmpty()) continue;
            StringBuilder slots = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                if (i > 0) slots.append(',');
                if (i < ids.size()) slots.append(String.format(Locale.US, "%02d", ids.get(i)));
            }
            out.add(sentence(String.format(Locale.US, "GNGSA,A,%d,%s,%.1f,%.1f,%.1f,%d",
                    fixType, slots, pdop, hdop, vdop, systemId(type))));
        }
        for (int type : groups) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < s.n; i++) if (s.type[i] == type) idx.add(i);
            if (idx.isEmpty()) continue;
            int pages = (idx.size() + 3) / 4;
            for (int p = 0; p < pages; p++) {
                StringBuilder sb = new StringBuilder(String.format(Locale.US, "%sGSV,%d,%d,%02d",
                        talker(type), pages, p + 1, idx.size()));
                for (int j = p * 4; j < Math.min(idx.size(), p * 4 + 4); j++) {
                    int i = idx.get(j);
                    sb.append(String.format(Locale.US, ",%02d,%02d,%03d,%02d",
                            nmeaId(type, s.svid[i]), Math.round(s.elev[i]), Math.round(s.az[i]) % 360, Math.round(s.cn0[i])));
                }
                sb.append(',').append(signalId(type));
                out.add(sentence(sb.toString()));
            }
        }
        out.add(sentence(String.format(Locale.US, "GNRMC,%s,%c,%s,%c,%s,%c,%.2f,%.2f,%s,,,A",
                hms, used > 0 ? 'A' : 'V', coord(lat, true), ns, coord(lng, false), ew, knots, course, dmy)));
        return out;
    }

    static long[] measJava(Snapshot s, long nowMs, long timeNs) {
        long gpsNs = (nowMs - GPS_EPOCH_MS + LEAP_S * 1000L) * 1_000_000L;
        long[] out = new long[2 + 2 * s.n];
        out[0] = gpsNs;
        out[1] = gpsNs % WEEK_NS;
        long bucket = timeNs / 1_000_000_000L;
        for (int i = 0; i < s.n; i++) {
            double norm = (s.elev[i] - 8.0) / 77.0;
            out[2 + 2 * i] = (long) ((20_200_000.0 + (1.0 - norm) * 5_000_000.0) / LIGHT * 1e9);
            double base = ((s.svid[i] * 37 % 1600) - 800) * 0.9;
            double jitter = (Config.hashUnit(s.type[i] * 100L + s.svid[i], bucket) - 0.5) * 6.0;
            out[3 + 2 * i] = Double.doubleToRawLongBits(base + jitter);
        }
        return out;
    }

    private GnssRef() { }
}