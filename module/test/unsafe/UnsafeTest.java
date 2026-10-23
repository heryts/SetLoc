package com.v7878.sun.unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UnsafeTest {
  static int fails;

  static void eq(String n, Object a, Object b) {
    if (a == null ? b != null : !a.equals(b)) {
      fails++;
      System.out.println("FAIL " + n + ": " + a + " != " + b);
    } else {
      System.out.println("ok   " + n);
    }
  }

  public static void main(String[] args) throws Exception {
    List<String> want = Files.readAllLines(Paths.get(args[0]));
    want.removeIf(String::isEmpty);
    Collections.sort(want);
    List<String> have = new ArrayList<>();
    boolean onlyPublicStatic = true;
    for (Method m : SunUnsafe.class.getDeclaredMethods()) {
      int mod = m.getModifiers();
      if (!Modifier.isPublic(mod) || !Modifier.isStatic(mod)) onlyPublicStatic = false;
      StringBuilder sb = new StringBuilder(m.getName()).append('(');
      Class<?>[] p = m.getParameterTypes();
      for (int i = 0; i < p.length; i++) sb.append(i > 0 ? "," : "").append(p[i].getSimpleName());
      have.add(sb.append(')').append(m.getReturnType().getSimpleName()).toString());
    }
    Collections.sort(have);
    eq("API: daftar method sama dengan SunUnsafeWrapper v1.0.2 (" + want.size() + ")", have, want);
    eq("API: semua method public static", onlyPublicStatic, true);
    eq("API: konstruktor publik tanpa argumen ada", Modifier.isPublic(SunUnsafe.class.getConstructor().getModifiers()), true);
    Field f = SunUnsafe.class.getField("INSTANCE");
    eq("API: INSTANCE public static final bertipe sun.misc.Unsafe", Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()) && f.getType().getName().equals("sun.misc.Unsafe"), true);
    Object inst = f.get(null);
    eq("INSTANCE terisi", inst != null, true);
    Method ng = Class.forName("com.v7878.sun.unsafe.Getter").getDeclaredMethod("newUnsafe");
    ng.setAccessible(true);
    eq("Getter.newUnsafe mengembalikan instance yang sama dengan INSTANCE", ng.invoke(null), inst);
    eq("getUnsafeClass", SunUnsafe.getUnsafeClass().getName(), "sun.misc.Unsafe");

    long a = SunUnsafe.allocateMemory(64);
    SunUnsafe.setMemory(a, 64, (byte) 0);
    SunUnsafe.putLong(a, 0x0102030405060708L);
    eq("alamat: long", SunUnsafe.getLong(a), 0x0102030405060708L);
    eq("alamat: byte rendah", SunUnsafe.getByte(a), (byte) (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? 8 : 1));
    SunUnsafe.putInt(a + 8, -5);
    eq("alamat: int", SunUnsafe.getInt(a + 8), -5);
    SunUnsafe.putShort(a + 12, (short) -7);
    eq("alamat: short", SunUnsafe.getShort(a + 12), (short) -7);
    SunUnsafe.putChar(a + 14, 'Z');
    eq("alamat: char", SunUnsafe.getChar(a + 14), 'Z');
    SunUnsafe.putFloat(a + 16, 1.5f);
    eq("alamat: float", SunUnsafe.getFloat(a + 16), 1.5f);
    SunUnsafe.putDouble(a + 24, -2.25);
    eq("alamat: double", SunUnsafe.getDouble(a + 24), -2.25);
    SunUnsafe.putByte(a + 32, (byte) 9);
    eq("alamat: byte", SunUnsafe.getByte(a + 32), (byte) 9);
    SunUnsafe.putBoolean(a + 33, true);
    SunUnsafe.putBoolean(a + 34, false);
    eq("alamat: boolean true/false", SunUnsafe.getBoolean(a + 33) && !SunUnsafe.getBoolean(a + 34), true);
    SunUnsafe.copyMemory(a, a + 40, 8);
    eq("copyMemory", SunUnsafe.getLong(a + 40), 0x0102030405060708L);
    SunUnsafe.freeMemory(a);

    long[] la = new long[3];
    long lb = SunUnsafe.arrayBaseOffset(long[].class);
    int ls = SunUnsafe.arrayIndexScale(long[].class);
    eq("arrayIndexScale long[]", ls, 8);
    SunUnsafe.putLong(la, lb + ls, 77L);
    eq("objek: putLong/getLong", SunUnsafe.getLong(la, lb + ls) == 77L && la[1] == 77L, true);
    SunUnsafe.putLongVolatile(la, lb, 5L);
    eq("objek: long volatile", SunUnsafe.getLongVolatile(la, lb), 5L);
    eq("CAS long sukses", SunUnsafe.compareAndSwapLong(la, lb, 5L, 6L) && la[0] == 6L, true);
    eq("CAS long gagal", SunUnsafe.compareAndSwapLong(la, lb, 5L, 7L), false);

    int[] ia = new int[2];
    long ib = SunUnsafe.arrayBaseOffset(int[].class);
    SunUnsafe.putInt(ia, ib, 3);
    eq("objek: int", SunUnsafe.getInt(ia, ib), 3);
    SunUnsafe.putIntVolatile(ia, ib + 4, 4);
    eq("objek: int volatile", SunUnsafe.getIntVolatile(ia, ib + 4), 4);
    eq("CAS int sukses", SunUnsafe.compareAndSwapInt(ia, ib, 3, 8) && ia[0] == 8, true);
    eq("CAS int gagal", SunUnsafe.compareAndSwapInt(ia, ib, 3, 9), false);

    Object[] oa = new Object[2];
    long ob = SunUnsafe.arrayBaseOffset(Object[].class);
    SunUnsafe.putObject(oa, ob, "x");
    eq("objek: object", SunUnsafe.getObject(oa, ob), "x");
    SunUnsafe.putObjectVolatile(oa, ob + SunUnsafe.arrayIndexScale(Object[].class), "y");
    eq("objek: object volatile", SunUnsafe.getObjectVolatile(oa, ob + SunUnsafe.arrayIndexScale(Object[].class)), "y");
    eq("CAS object sukses", SunUnsafe.compareAndSwapObject(oa, ob, "x", "z") && "z".equals(oa[0]), true);
    eq("CAS object gagal", SunUnsafe.compareAndSwapObject(oa, ob, "x", "w"), false);

    byte[] ba = new byte[4];
    long bb = SunUnsafe.arrayBaseOffset(byte[].class);
    SunUnsafe.putBoolean(ba, bb, true);
    SunUnsafe.putByte(ba, bb + 1, (byte) 5);
    eq("objek: boolean dan byte", SunUnsafe.getBoolean(ba, bb) && !SunUnsafe.getBoolean(ba, bb + 2) && SunUnsafe.getByte(ba, bb + 1) == 5 && ba[0] == 1, true);
    short[] sa = new short[1];
    SunUnsafe.putShort(sa, SunUnsafe.arrayBaseOffset(short[].class), (short) 11);
    eq("objek: short", SunUnsafe.getShort(sa, SunUnsafe.arrayBaseOffset(short[].class)), (short) 11);
    char[] ca = new char[1];
    SunUnsafe.putChar(ca, SunUnsafe.arrayBaseOffset(char[].class), 'Q');
    eq("objek: char", SunUnsafe.getChar(ca, SunUnsafe.arrayBaseOffset(char[].class)), 'Q');
    float[] fa = new float[1];
    SunUnsafe.putFloat(fa, SunUnsafe.arrayBaseOffset(float[].class), 2.5f);
    eq("objek: float", SunUnsafe.getFloat(fa, SunUnsafe.arrayBaseOffset(float[].class)), 2.5f);
    double[] da = new double[1];
    SunUnsafe.putDouble(da, SunUnsafe.arrayBaseOffset(double[].class), 3.5);
    eq("objek: double", SunUnsafe.getDouble(da, SunUnsafe.arrayBaseOffset(double[].class)), 3.5);

    eq("allocateInstance", SunUnsafe.allocateInstance(StringBuilder.class) instanceof StringBuilder, true);
    eq("addressSize", SunUnsafe.addressSize() == 4 || SunUnsafe.addressSize() == 8, true);
    eq("pageSize", SunUnsafe.pageSize() > 0, true);
    SunUnsafe.loadFence();
    SunUnsafe.storeFence();
    SunUnsafe.fullFence();
    SunUnsafe.unpark(Thread.currentThread());
    long t0 = System.nanoTime();
    SunUnsafe.park(false, 0L);
    eq("unpark lalu park kembali segera", System.nanoTime() - t0 < 2_000_000_000L, true);
    SunUnsafe.park(false, 1_000_000L);
    eq("park berjangka kembali", true, true);

    System.out.println(fails == 0 ? "ALL PASS" : "FAILURES " + fails);
    System.exit(fails);
  }
}
