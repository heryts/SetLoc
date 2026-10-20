package io.gpsz.zygote;

import android.content.pm.PackageManager;

import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.vmtools.Hooks;
import com.v7878.zygisk.ZygoteLoader;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class PackageGuard {

    private static final String OWN_PACKAGE = "com.heryts.setloc";
    private static final int RET = EmulatedStackFrame.RETURN_VALUE_IDX;

    static void install() {
        try {
            if (OWN_PACKAGE.equals(ZygoteLoader.getPackageName())) return;
        } catch (Throwable ignored) { }

        try {
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            for (Method m : apm.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isNative(mod)) continue;
                String n = m.getName();
                Class<?>[] ps = m.getParameterTypes();
                Class<?> ret = m.getReturnType();

                if ((n.equals("getPackageInfo") || n.equals("getApplicationInfo"))
                        && ps.length >= 1 && ps[0] == String.class) {
                    hookThrow(m, n);
                    continue;
                }

                if ((n.equals("getInstalledPackages") || n.equals("getInstalledApplications"))
                        && List.class.isAssignableFrom(ret)) {
                    hookFilter(m, n);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static void hookThrow(Method m, String methodName) {
        try {
            Hooks.hook(m, Hooks.EntryPointType.DIRECT, (original, frame) -> {
                try {
                    Object arg = frame.accessor().getReference(1);
                    if (OWN_PACKAGE.equals(arg)) {
                        ZygoteEntry.<RuntimeException>sneaky(
                                new PackageManager.NameNotFoundException(OWN_PACKAGE));
                        return;
                    }
                } catch (ClassCastException | ArrayIndexOutOfBoundsException ignored) { }
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
            }, Hooks.EntryPointType.DIRECT);
        } catch (Throwable ignored) { }
    }

    private static void hookFilter(Method m, String methodName) {
        try {
            Hooks.hook(m, Hooks.EntryPointType.DIRECT, (original, frame) -> {
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
                try {
                    Object r = frame.accessor().getReference(RET);
                    if (!(r instanceof List)) return;
                    List<?> src = (List<?>) r;
                    List<Object> dst = new ArrayList<>(src.size());
                    boolean found = false;
                    for (Object item : src) {
                        if (item == null) { dst.add(null); continue; }
                        try {
                            String pkg = (String) item.getClass()
                                    .getField("packageName").get(item);
                            if (OWN_PACKAGE.equals(pkg)) {
                                found = true;
                                continue;
                            }
                        } catch (Throwable ignored) { }
                        dst.add(item);
                    }
                    if (found) frame.accessor().setValue(RET, dst);
                } catch (Throwable ignored) { }
            }, Hooks.EntryPointType.DIRECT);
        } catch (Throwable ignored) { }
    }

    private PackageGuard() { }
}
