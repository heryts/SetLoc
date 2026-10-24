package com.v7878.sun.unsafe;

import sun.misc.Unsafe;

public final class SunUnsafe {
    public static final Unsafe INSTANCE = Getter.newUnsafe();

    public SunUnsafe() {
    }

    public static Class<?> getUnsafeClass() {
        return Unsafe.class;
    }

    public static int arrayBaseOffset(Class<?> c) {
        return INSTANCE.arrayBaseOffset(c);
    }

    public static int arrayIndexScale(Class<?> c) {
        return INSTANCE.arrayIndexScale(c);
    }

    public static boolean compareAndSwapInt(Object o, long off, int expected, int x) {
        return INSTANCE.compareAndSwapInt(o, off, expected, x);
    }

    public static boolean compareAndSwapLong(Object o, long off, long expected, long x) {
        return INSTANCE.compareAndSwapLong(o, off, expected, x);
    }

    public static boolean compareAndSwapObject(Object o, long off, Object expected, Object x) {
        return INSTANCE.compareAndSwapObject(o, off, expected, x);
    }

    public static int getIntVolatile(Object o, long off) {
        return INSTANCE.getIntVolatile(o, off);
    }

    public static void putIntVolatile(Object o, long off, int x) {
        INSTANCE.putIntVolatile(o, off, x);
    }

    public static long getLongVolatile(Object o, long off) {
        return INSTANCE.getLongVolatile(o, off);
    }

    public static void putLongVolatile(Object o, long off, long x) {
        INSTANCE.putLongVolatile(o, off, x);
    }

    public static Object getObjectVolatile(Object o, long off) {
        return INSTANCE.getObjectVolatile(o, off);
    }

    public static void putObjectVolatile(Object o, long off, Object x) {
        INSTANCE.putObjectVolatile(o, off, x);
    }

    public static int getInt(Object o, long off) {
        return INSTANCE.getInt(o, off);
    }

    public static void putInt(Object o, long off, int x) {
        INSTANCE.putInt(o, off, x);
    }

    public static long getLong(Object o, long off) {
        return INSTANCE.getLong(o, off);
    }

    public static void putLong(Object o, long off, long x) {
        INSTANCE.putLong(o, off, x);
    }

    public static Object getObject(Object o, long off) {
        return INSTANCE.getObject(o, off);
    }

    public static void putObject(Object o, long off, Object x) {
        INSTANCE.putObject(o, off, x);
    }

    public static void park(boolean absolute, long time) {
        INSTANCE.park(absolute, time);
    }

    public static void unpark(Object thread) {
        INSTANCE.unpark(thread);
    }

    public static Object allocateInstance(Class<?> c) throws InstantiationException {
        return INSTANCE.allocateInstance(c);
    }

    public static int addressSize() {
        return INSTANCE.addressSize();
    }

    public static int pageSize() {
        return INSTANCE.pageSize();
    }

    public static long allocateMemory(long bytes) {
        return INSTANCE.allocateMemory(bytes);
    }

    public static void freeMemory(long address) {
        INSTANCE.freeMemory(address);
    }

    public static void setMemory(long address, long bytes, byte value) {
        INSTANCE.setMemory(address, bytes, value);
    }

    public static boolean getBoolean(Object o, long off) {
        return INSTANCE.getByte(o, off) != 0;
    }

    public static void putBoolean(Object o, long off, boolean x) {
        INSTANCE.putByte(o, off, (byte) (x ? 1 : 0));
    }

    public static byte getByte(Object o, long off) {
        return INSTANCE.getByte(o, off);
    }

    public static void putByte(Object o, long off, byte x) {
        INSTANCE.putByte(o, off, x);
    }

    public static char getChar(Object o, long off) {
        return INSTANCE.getChar(o, off);
    }

    public static void putChar(Object o, long off, char x) {
        INSTANCE.putChar(o, off, x);
    }

    public static short getShort(Object o, long off) {
        return INSTANCE.getShort(o, off);
    }

    public static void putShort(Object o, long off, short x) {
        INSTANCE.putShort(o, off, x);
    }

    public static float getFloat(Object o, long off) {
        return INSTANCE.getFloat(o, off);
    }

    public static void putFloat(Object o, long off, float x) {
        INSTANCE.putFloat(o, off, x);
    }

    public static double getDouble(Object o, long off) {
        return INSTANCE.getDouble(o, off);
    }

    public static void putDouble(Object o, long off, double x) {
        INSTANCE.putDouble(o, off, x);
    }

    public static boolean getBoolean(long address) {
        return INSTANCE.getByte(address) != 0;
    }

    public static void putBoolean(long address, boolean x) {
        INSTANCE.putByte(address, (byte) (x ? 1 : 0));
    }

    public static byte getByte(long address) {
        return INSTANCE.getByte(address);
    }

    public static void putByte(long address, byte x) {
        INSTANCE.putByte(address, x);
    }

    public static short getShort(long address) {
        return INSTANCE.getShort(address);
    }

    public static void putShort(long address, short x) {
        INSTANCE.putShort(address, x);
    }

    public static char getChar(long address) {
        return INSTANCE.getChar(address);
    }

    public static void putChar(long address, char x) {
        INSTANCE.putChar(address, x);
    }

    public static int getInt(long address) {
        return INSTANCE.getInt(address);
    }

    public static void putInt(long address, int x) {
        INSTANCE.putInt(address, x);
    }

    public static long getLong(long address) {
        return INSTANCE.getLong(address);
    }

    public static void putLong(long address, long x) {
        INSTANCE.putLong(address, x);
    }

    public static float getFloat(long address) {
        return INSTANCE.getFloat(address);
    }

    public static void putFloat(long address, float x) {
        INSTANCE.putFloat(address, x);
    }

    public static double getDouble(long address) {
        return INSTANCE.getDouble(address);
    }

    public static void putDouble(long address, double x) {
        INSTANCE.putDouble(address, x);
    }

    public static void copyMemory(long src, long dst, long bytes) {
        INSTANCE.copyMemory(src, dst, bytes);
    }

    public static void loadFence() {
        INSTANCE.loadFence();
    }

    public static void storeFence() {
        INSTANCE.storeFence();
    }

    public static void fullFence() {
        INSTANCE.fullFence();
    }
}
