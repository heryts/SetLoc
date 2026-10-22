import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RotateSalt {
    private static final String[] LOGICAL = {"cfg", "sys", "beat", "trk", "net", "bt"};
    private static final String[] SH_NAMES = {"KEY", "SYSKEY", "BEATKEY", "TRKKEY", "NETKEY", "BTKEY"};
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int LEN = 12;

    private static final Path CONFIG = Paths.get("module/zygote/src/main/java/io/gpsz/zygote/Config.java");
    private static final Path PREF = Paths.get("app/app/src/main/java/com/heryts/setloc/utils/PrefManager.kt");
    private static final Path BRIDGE = Paths.get("module/zygote/src/main/assets/bridge.sh");
    private static final Path GPSZ = Paths.get("module/zygote/src/main/assets/gpsz.sh");
    private static final Path WEB = Paths.get("module/zygote/src/main/assets/webroot/index.html");
    private static final Path TEST = Paths.get("module/test/run.sh");
    private static final Path SALT_FILE = Paths.get(".salt");

    static String tag(String salt, String logical) {
        String s = salt + logical;
        int h = 0x811C9DC5;
        for (int i = 0; i < s.length(); i++) {
            int o = s.charAt(i) & 0xFF;
            h = ((h ^ o) * 16777619) & 0x7FFFFFFF;
        }
        return String.format("%06x", h & 0xFFFFFF);
    }

    static String random() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LEN; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    static String sub(String text, String regex, String replacement, Path file) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
        if (!m.find()) throw new IllegalStateException("pola tidak ditemukan di " + file + ": " + regex);
        return m.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    static String group(String text, String regex, Path file) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
        if (!m.find()) throw new IllegalStateException("pola tidak ditemukan di " + file + ": " + regex);
        return m.group(1);
    }

    static String kv(String salt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < salt.length(); i++) sb.append((int) (salt.charAt(i) & 0xFF)).append(' ');
        return sb.toString();
    }

    static String tags(String salt, boolean withLive) {
        StringBuilder sb = new StringBuilder();
        for (String l : LOGICAL) sb.append(tag(salt, l)).append(' ');
        if (withLive) sb.append(tag(salt, "live")).append(' ');
        return sb.toString().trim();
    }

    public static void main(String[] args) throws Exception {
        boolean fresh = args.length > 0 && args[0].equals("--new");
        String oldSalt = group(read(CONFIG), "SALT = \"([^\"]+)\"", CONFIG);
        String salt;
        if (!fresh && Files.exists(SALT_FILE)) salt = read(SALT_FILE).trim();
        else salt = random();
        if (!salt.matches("[A-Za-z0-9]{" + LEN + "}")) throw new IllegalStateException(".salt harus " + LEN + " karakter huruf/angka");
        Files.write(SALT_FILE, (salt + "\n").getBytes(StandardCharsets.UTF_8));

        String config = sub(read(CONFIG), "SALT = \"[^\"]+\"", "SALT = \"" + salt + "\"", CONFIG);
        String pref = sub(read(PREF), "SALT = \"[^\"]+\"", "SALT = \"" + salt + "\"", PREF);

        String bridge = read(BRIDGE);
        String gpsz = read(GPSZ);
        String oldKeys = group(bridge, "^OLDKEYS=\"([^\"]*)\"", BRIDGE);
        Set<String> legacy = new LinkedHashSet<>();
        for (String k : oldKeys.split("\\s+")) if (!k.isEmpty()) legacy.add(k);
        if (!oldSalt.equals(salt)) for (String k : tags(oldSalt, true).split(" ")) legacy.add(k);
        for (String k : tags(salt, true).split(" ")) legacy.remove(k);
        String legacyLine = "OLDKEYS=\"" + String.join(" ", legacy) + "\"";

        bridge = sub(bridge, "^OLDKEYS=\"[^\"]*\"", legacyLine, BRIDGE);
        for (Path p : new Path[]{BRIDGE, GPSZ}) {
            String t = p == BRIDGE ? bridge : gpsz;
            t = sub(t, "^KV=\"[^\"]*\"", "KV=\"" + kv(salt) + "\"", p);
            for (int i = 0; i < LOGICAL.length; i++) {
                t = sub(t, "^" + SH_NAMES[i] + "=[0-9a-f]+", SH_NAMES[i] + "=" + tag(salt, LOGICAL[i]), p);
            }
            if (p == BRIDGE) bridge = t; else gpsz = t;
        }

        String web = sub(read(WEB), "^const SALT = '[^']*';", "const SALT = '" + salt + "';", WEB);
        web = sub(web, "^const SK = \\{.*\\};",
                "const SK = { cfg: '" + tag(salt, "cfg") + "', sys: '" + tag(salt, "sys") + "', net: '" + tag(salt, "net")
                        + "', bt: '" + tag(salt, "bt") + "', trk: '" + tag(salt, "trk") + "' };", WEB);

        String test = sub(read(TEST), "^SALT=\"[^\"]+\"", "SALT=\"" + salt + "\"", TEST);
        test = sub(test, "^SYSTAG=[0-9a-f]+; TRKTAG=[0-9a-f]+; BEATTAG=[0-9a-f]+",
                "SYSTAG=" + tag(salt, "sys") + "; TRKTAG=" + tag(salt, "trk") + "; BEATTAG=" + tag(salt, "beat"), TEST);

        Files.write(CONFIG, config.getBytes(StandardCharsets.UTF_8));
        Files.write(PREF, pref.getBytes(StandardCharsets.UTF_8));
        Files.write(BRIDGE, bridge.getBytes(StandardCharsets.UTF_8));
        Files.write(GPSZ, gpsz.getBytes(StandardCharsets.UTF_8));
        Files.write(WEB, web.getBytes(StandardCharsets.UTF_8));
        Files.write(TEST, test.getBytes(StandardCharsets.UTF_8));
        System.out.println("salt " + (oldSalt.equals(salt) ? "tetap" : "diganti") + ", key: " + tags(salt, false));
    }
}
