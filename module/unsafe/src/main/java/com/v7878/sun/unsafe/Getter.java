package com.v7878.sun.unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

final class Getter {
    private Getter() {
    }

    static Unsafe newUnsafe() {
        Throwable failure;
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failure = e;
        }
        try {
            Constructor<Unsafe> constructor = Unsafe.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            failure.addSuppressed(e);
        }
        try {
            return Unsafe.getUnsafe();
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
        throw new ExceptionInInitializerError(failure);
    }
}
