package io.gpsz.zygote;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

final class LsNative {
    static boolean ready = true;
    static final Map<Method, Object[]> HOOKS = new HashMap<>();

    static boolean ensureLoaded() {
        return ready;
    }

    static boolean loc = true;

    static boolean set = true;

    static boolean setEnabled() {
        return set;
    }

    static boolean locEnabled() {
        return loc;
    }

    static boolean isReady() {
        return ready;
    }

    static Method failOn;

    static Method nativeHook(Method target, Object hooker, Method callback) {
        if (target.equals(failOn)) return null;
        callback.setAccessible(true);
        HOOKS.put(target, new Object[]{hooker, callback});
        return target;
    }

    static Object dispatch(Method target, Object self, Object... args) throws Throwable {
        Object[] hook = HOOKS.get(target);
        try {
            if (hook == null) return target.invoke(self, args);
            boolean stat = java.lang.reflect.Modifier.isStatic(target.getModifiers());
            Object[] all = new Object[args.length + (stat ? 0 : 1)];
            if (!stat) all[0] = self;
            System.arraycopy(args, 0, all, stat ? 0 : 1, args.length);
            return ((Method) hook[1]).invoke(hook[0], (Object) all);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private LsNative() { }
}
