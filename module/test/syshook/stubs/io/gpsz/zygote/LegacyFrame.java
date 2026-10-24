package io.gpsz.zygote;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

final class LegacyFrame {
    static final Map<Method, Frames.Tx> HOOKS = new HashMap<>();
    static Method failOn;
    static boolean broken;

    static boolean hook(Method m, Frames.Tx tx) {
        if (broken) throw new NoClassDefFoundError("com.v7878.vmtools.Hooks");
        if (m.equals(failOn)) return false;
        HOOKS.put(m, tx);
        return true;
    }

    static Object dispatch(Method target, Object self, Object... args) throws Throwable {
        Frames.Tx tx = HOOKS.get(target);
        try {
            if (tx == null) return target.invoke(self, args);
            boolean stat = java.lang.reflect.Modifier.isStatic(target.getModifiers());
            Object[] all = new Object[args.length + (stat ? 0 : 1)];
            if (!stat) all[0] = self;
            System.arraycopy(args, 0, all, stat ? 0 : 1, args.length);
            Object[] ret = new Object[1];
            Frames.Fr fr = new Frames.Fr() {
                public Object get(int idx) {
                    return idx == Frames.RET ? ret[0] : all[idx];
                }

                public void set(int idx, Object value) {
                    if (idx == Frames.RET) ret[0] = value;
                    else all[idx] = value;
                }
            };
            tx.run(() -> {
                try {
                    ret[0] = target.invoke(stat ? null : all[0], java.util.Arrays.copyOfRange(all, stat ? 0 : 1, all.length));
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }, fr);
            return ret[0];
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private LegacyFrame() { }
}
