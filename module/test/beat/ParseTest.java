package io.gpsz.zygote;

public class ParseTest {
  static int fails = 0;
  static void eq(String n, Object a, Object b) {
    if (!a.equals(b)) { fails++; System.out.println("FAIL " + n + ": " + a + " != " + b); }
    else System.out.println("ok   " + n);
  }
  static boolean noThrow(String s) {
    try { Config.parse(s); return true; } catch (Throwable t) { System.out.println("     melempar: " + t); return false; }
  }
  static void base() {
    Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=0;spd=0;alt=5;altv=0;rr=8");
  }
  public static void main(String[] x) {
    eq("null tidak melempar", noThrow(null), true);
    eq("null mematikan", Config.enabled, false);
    eq("string \"null\" tidak melempar", noThrow("null"), true);
    eq("string \"null\" mematikan", Config.enabled, false);
    eq("string kosong tidak melempar", noThrow(""), true);
    eq("string kosong -> mati", Config.enabled, false);
    eq("sampah tanpa '=' tidak melempar", noThrow("abc;;;xyz;enabled"), true);
    eq("sampah tanpa '=' -> mati", Config.enabled, false);
    eq("kunci/nilai kosong tidak melempar", noThrow("=;=1;lat=;lng=;;"), true);

    base();
    eq("dasar: enabled", Config.enabled, true);
    eq("dasar: lat", Config.pos.lat, -7.7);
    eq("dasar: lng", Config.pos.lng, 110.4);

    Config.parse("enabled=1;lat=abc;lng=xyz");
    eq("lat/lng bukan angka: lat dipertahankan", Config.pos.lat, -7.7);
    eq("lat/lng bukan angka: lng dipertahankan", Config.pos.lng, 110.4);

    Config.parse("enabled=1;lat=91;lng=181");
    eq("lat/lng di luar rentang: lat dipertahankan", Config.pos.lat, -7.7);
    eq("lat/lng di luar rentang: lng dipertahankan", Config.pos.lng, 110.4);

    Config.parse("enabled=1;lat=NaN;lng=Infinity");
    eq("NaN/Infinity: lat dipertahankan", Config.pos.lat, -7.7);
    eq("NaN/Infinity: lng dipertahankan", Config.pos.lng, 110.4);

    Config.parse("enabled=1;lat=1e300;lng=-1e300");
    eq("angka raksasa: lat dipertahankan", Config.pos.lat, -7.7);
    eq("angka raksasa: lng dipertahankan", Config.pos.lng, 110.4);

    Config.parse("enabled=1;lat=90;lng=-180");
    eq("batas tepat lat 90", Config.pos.lat, 90.0);
    eq("batas tepat lng -180", Config.pos.lng, -180.0);

    base();
    Config.parse("enabled=1;lat=1;lng=2;lat=3;lng=4");
    eq("kunci ganda: yang terakhir menang (lat)", Config.pos.lat, 3.0);
    eq("kunci ganda: yang terakhir menang (lng)", Config.pos.lng, 4.0);

    Config.parse(" enabled = 1 ; lat = 5.5 ; lng = 6.5 ");
    eq("spasi diabaikan: enabled", Config.enabled, true);
    eq("spasi diabaikan: lat", Config.pos.lat, 5.5);

    Config.parse("enabled=1;lat=1;lng=2;spd=-3");
    eq("spd negatif -> 0", Config.spd, 0f);
    Config.parse("enabled=1;lat=1;lng=2;spd=99999");
    eq("spd di luar rentang -> 0", Config.spd, 0f);

    Config.parse("enabled=1;lat=1;lng=2;brg=-10");
    eq("brg negatif -> NaN", Float.isNaN(Config.brg), true);
    Config.parse("enabled=1;lat=1;lng=2;brg=370");
    eq("brg 370 -> 10", Config.brg, 10f);
    Config.parse("enabled=1;lat=1;lng=2;brg=NaN");
    eq("brg NaN -> NaN", Float.isNaN(Config.brg), true);
    Config.parse("enabled=1;lat=1;lng=2;brg=abc");
    eq("brg bukan angka -> NaN", Float.isNaN(Config.brg), true);

    Config.parse("enabled=1;lat=1;lng=2;rr=500");
    eq("rr 500 -> 100", Config.rr, 100f);
    Config.parse("enabled=1;lat=1;lng=2;rr=-5");
    eq("rr negatif -> 0", Config.rr, 0f);
    Config.parse("enabled=1;lat=1;lng=2;rr=1e9");
    eq("rr di luar rentang -> 8", Config.rr, 8f);

    Config.parse("enabled=1;lat=1;lng=2;alt=1e9;altv=-4");
    eq("alt di luar rentang -> 0", Config.alt, 0.0);
    eq("altv negatif -> 0", Config.altVar, 0f);

    Config.parse("enabled=1;lat=1;lng=2;acc=12");
    eq("acc normal terbaca", Config.acc, 12f);
    Config.parse("enabled=1;lat=1;lng=2;acc=1e9");
    eq("acc di luar rentang: nilai sebelumnya", Config.acc, 12f);
    Config.parse("enabled=1;lat=1;lng=2;acc=abc");
    eq("acc bukan angka: nilai sebelumnya", Config.acc, 12f);

    Config.parse("enabled=1;lat=1;lng=2;tk=1");
    eq("tk=1 -> tkMode", Config.tkMode, true);
    Config.parse("enabled=1;lat=1;lng=2;tk=10");
    eq("tk=10 bukan mode jejak", Config.tkMode, false);
    Config.parse("enabled=1;lat=1;lng=2;tk=true");
    eq("tk=true bukan mode jejak", Config.tkMode, false);
    Config.parse("tk=1;enabled=1;lat=1;lng=2");
    eq("tk=1 di awal dikenali", Config.tkMode, true);

    Config.parse("enabled=2;lat=1;lng=2");
    eq("enabled=2 bukan 1 -> mati", Config.enabled, false);
    Config.parse("enabled=1;lat=1;lng=2;pl=1");
    eq("pl=1 -> playing", Config.playing, true);
    Config.parse("enabled=1;lat=1;lng=2;pl=0");
    eq("pl=0 -> tidak playing", Config.playing, false);

    String long1 = "enabled=1;lat=1;lng=2;" + "x=1;".repeat(20000);
    eq("input panjang tidak melempar", noThrow(long1), true);
    eq("input panjang tetap terbaca", Config.enabled, true);

    System.out.println(fails == 0 ? "ALL PASS" : "FAILS: " + fails);
    if (fails != 0) System.exit(1);
  }
}
