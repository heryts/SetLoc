package sun.misc;

public final class Unsafe {
    private Unsafe() {
    }

    public static native Unsafe getUnsafe();

    public native int arrayBaseOffset(Class<?> c);

    public native int arrayIndexScale(Class<?> c);

    public native boolean compareAndSwapInt(Object o, long offset, int expected, int x);

    public native boolean compareAndSwapLong(Object o, long offset, long expected, long x);

    public native boolean compareAndSwapObject(Object o, long offset, Object expected, Object x);

    public native int getIntVolatile(Object o, long offset);

    public native void putIntVolatile(Object o, long offset, int x);

    public native long getLongVolatile(Object o, long offset);

    public native void putLongVolatile(Object o, long offset, long x);

    public native Object getObjectVolatile(Object o, long offset);

    public native void putObjectVolatile(Object o, long offset, Object x);

    public native int getInt(Object o, long offset);

    public native void putInt(Object o, long offset, int x);

    public native long getLong(Object o, long offset);

    public native void putLong(Object o, long offset, long x);

    public native Object getObject(Object o, long offset);

    public native void putObject(Object o, long offset, Object x);

    public native byte getByte(Object o, long offset);

    public native void putByte(Object o, long offset, byte x);

    public native char getChar(Object o, long offset);

    public native void putChar(Object o, long offset, char x);

    public native short getShort(Object o, long offset);

    public native void putShort(Object o, long offset, short x);

    public native float getFloat(Object o, long offset);

    public native void putFloat(Object o, long offset, float x);

    public native double getDouble(Object o, long offset);

    public native void putDouble(Object o, long offset, double x);

    public native byte getByte(long address);

    public native void putByte(long address, byte x);

    public native short getShort(long address);

    public native void putShort(long address, short x);

    public native char getChar(long address);

    public native void putChar(long address, char x);

    public native int getInt(long address);

    public native void putInt(long address, int x);

    public native long getLong(long address);

    public native void putLong(long address, long x);

    public native float getFloat(long address);

    public native void putFloat(long address, float x);

    public native double getDouble(long address);

    public native void putDouble(long address, double x);

    public native void park(boolean isAbsolute, long time);

    public native void unpark(Object thread);

    public native Object allocateInstance(Class<?> c) throws InstantiationException;

    public native int addressSize();

    public native int pageSize();

    public native long allocateMemory(long bytes);

    public native void freeMemory(long address);

    public native void setMemory(long address, long bytes, byte value);

    public native void copyMemory(long srcAddress, long dstAddress, long bytes);

    public native void loadFence();

    public native void storeFence();

    public native void fullFence();
}
