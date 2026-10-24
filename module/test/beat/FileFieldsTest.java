package io.gpsz.zygote;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;

public class FileFieldsTest {
  static int fails = 0;
  static void eq(String n, Object a, Object b) {
    boolean same = a == null ? b == null : a.equals(b);
    if (!same) { fails++; System.out.println("FAIL " + n + ": " + a + " != " + b); }
    else System.out.println("ok   " + n);
  }
  static String[] read(File f) throws Exception {
    Method m = Config.class.getDeclaredMethod("readFileFields", File.class);
    m.setAccessible(true);
    return (String[]) m.invoke(null, f);
  }
  static File tmp(String body) throws Exception {
    File f = File.createTempFile("gpsz", ".cfg");
    f.deleteOnExit();
    Files.write(f.toPath(), body.getBytes("UTF-8"));
    return f;
  }
  public static void main(String[] x) throws Exception {
    ConfigNative.ensureLoaded(); LocationNative.ensureLoaded();
    String[] r = read(tmp("cfg:enabled=1;lat=1;lng=2\nsys:2\nnet:0\nbt:1\n"));
    eq("cfg terbaca", r[0], "enabled=1;lat=1;lng=2");
    eq("sys terbaca", r[1], "2");
    eq("net terbaca", r[2], "0");
    eq("bt terbaca", r[3], "1");
    r = read(tmp("cfg:enabled=0\nsys:null\nnet:\n"));
    eq("nilai null/kosong dilewati: sys", r[1], null);
    eq("nilai null/kosong dilewati: net", r[2], null);
    eq("tanpa cfg -> null", read(tmp("sys:1\nnet:0\n")), null);
    eq("file kosong -> null", read(tmp("")), null);
    eq("baris tanpa ':' diabaikan", read(tmp("sampah\ncfg:a=1\n"))[0], "a=1");
    StringBuilder big = new StringBuilder("cfg:enabled=1\n");
    for (int i = 0; i < 2000; i++) big.append("zzz:").append(i).append('\n');
    eq("file melebihi batas tidak melempar", read(tmp(big.toString()))[0], "enabled=1");
    if (fails > 0) { System.out.println("GAGAL " + fails); System.exit(1); }
    System.out.println("ALL PASS");
  }
}
