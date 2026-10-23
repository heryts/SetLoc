package io.gpsz.zygote;

public class WireDump {
    public static void main(String[] x) {
        String[] plain = {"", "a", "enabled=1;lat=-7.7;lng=110.4;acc=10", "0123456789abcdefghij", "x\u00e9y", "\u20ac"};
        for (String p : plain) {
            String e = Config.enc(p);
            System.out.println("enc|" + p.length() + "|" + e + "|" + Config.dec(e));
        }
        String[] hex = {"", "00", "ff", "7a", "abc", "a", "zz", "+f", "-1", "0g", "ABCDEF", "dead", "80ff", "  ", "\u00e9\u00e9"};
        for (String h : hex) System.out.println("dec|" + h + "|" + Config.dec(h));
        for (String t : new String[]{"cfg", "sys", "beat", "trk", "net", "bt", "live", ""}) System.out.println("tag|" + t + "|" + Config.tag(t));
        System.out.println("null|" + Config.enc(null) + "|" + Config.dec(null));
        System.out.println("keys|" + Config.KEY + "|" + Config.KEY_SYS + "|" + Config.KEY_BEAT);
    }
}
