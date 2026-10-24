package io.gpsz.zygote;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

final class LsHook {
    interface Call {
        Object call(Object[] args) throws Throwable;
    }

    interface Handler {
        Object handle(Object[] args, Call original) throws Throwable;
    }

    private static final long BACKUP_WAIT_NS = 2_000_000_000L;

    public static final class Hooker {
        private final Handler handler;
        private final boolean isStatic;
        private volatile Method backup;
        private final Call self = this::invoke;

        Hooker(Handler handler, boolean isStatic) {
            this.handler = handler;
            this.isStatic = isStatic;
        }

        public Object callback(Object[] args) throws Throwable {
            return handler.handle(args, self);
        }

        private Object invoke(Object[] args) throws Throwable {
            Method m = backup;
            if (m == null) {
                long deadline = System.nanoTime() + BACKUP_WAIT_NS;
                while ((m = backup) == null) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("backup belum terisi");
                    Thread.onSpinWait();
                }
            }
            try {
                if (isStatic) return m.invoke(null, args);
                return m.invoke(args[0], Arrays.copyOfRange(args, 1, args.length));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    static boolean hook(Method target, Handler handler) {
        if (!LsNative.isReady()) return false;
        try {
            Hooker hooker = new Hooker(handler, Modifier.isStatic(target.getModifiers()));
            Method callback = Hooker.class.getDeclaredMethod("callback", Object[].class);
            Method backup = LsNative.nativeHook(target, hooker, callback);
            if (backup == null) return false;
            hooker.backup = backup;
            try {
                backup.setAccessible(true);
            } catch (Throwable ignored) { }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private LsHook() { }
}
