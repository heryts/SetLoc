package io.gpsz.zygote;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

final class Frames {
    static final int RET = -1;

    interface Fr {
        Object get(int idx);

        void set(int idx, Object value);
    }

    interface Orig {
        void invoke() throws Throwable;
    }

    interface Tx {
        void run(Orig original, Fr frame) throws Throwable;
    }

    private static final Map<Class<?>, Class<?>> BOX = new HashMap<>();
    static {
        BOX.put(boolean.class, Boolean.class);
        BOX.put(byte.class, Byte.class);
        BOX.put(char.class, Character.class);
        BOX.put(short.class, Short.class);
        BOX.put(int.class, Integer.class);
        BOX.put(long.class, Long.class);
        BOX.put(float.class, Float.class);
        BOX.put(double.class, Double.class);
    }

    private static final class LsFrame implements Fr, Orig {
        final Object[] args;
        final LsHook.Call original;
        Object ret;

        LsFrame(Object[] args, LsHook.Call original) {
            this.args = args;
            this.original = original;
        }

        @Override
        public Object get(int idx) {
            return idx == RET ? ret : args[idx];
        }

        @Override
        public void set(int idx, Object value) {
            if (idx == RET) ret = value;
            else args[idx] = value;
        }

        @Override
        public void invoke() throws Throwable {
            ret = original.call(args);
        }
    }

    static Object coerce(Class<?> rt, Object v) {
        if (rt == void.class) return null;
        if (!rt.isPrimitive()) return rt.isInstance(v) ? v : null;
        Class<?> box = BOX.get(rt);
        if (box == null) return rt.isInstance(v) ? v : null;
        if (box.isInstance(v)) return v;
        return zero(rt);
    }

    private static Object zero(Class<?> rt) {
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == byte.class) return (byte) 0;
        if (rt == char.class) return (char) 0;
        if (rt == short.class) return (short) 0;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        return 0d;
    }

    static boolean hook(Method m, Tx tx) {
        return hook(m, LsNative.locEnabled(), tx);
    }

    static boolean hook(Method m, boolean ls, Tx tx) {
        if (!ls || !LsNative.isReady()) return false;
        final Class<?> rt = m.getReturnType();
        return LsHook.hook(m, (args, original) -> {
            final LsFrame f = new LsFrame(args, original);
            tx.run(f, f);
            return coerce(rt, f.ret);
        });
    }

    static boolean hookSys(Method m, Tx tx) {
        try {
            return LegacyFrame.hook(m, tx);
        } catch (Throwable t) {
            return false;
        }
    }

    private Frames() { }
}
