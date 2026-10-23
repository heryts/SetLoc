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

    static boolean isReady() {
        return ready;
    }

    static Method nativeHook(Method target, Object hooker, Method callback) {
        callback.setAccessible(true);
        HOOKS.put(target, new Object[]{hooker, callback});
        return target;
    }

    static Object dispatch(Method target, Object self, Object... args) throws Throwable {
        Object[] hook = HOOKS.get(target);
        try {
            if (hook == null) return target.invoke(self, args);
            Object[] all = new Object[args.length + 1];
            all[0] = self;
            System.arraycopy(args, 0, all, 1, args.length);
            return ((Method) hook[1]).invoke(hook[0], (Object) all);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private LsNative() { }
}
