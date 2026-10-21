package io.gpsz.zygote;

import android.content.pm.PackageManager;

import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.vmtools.Hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class PackageGuard {
    private static final int RET = EmulatedStackFrame.RETURN_VALUE_IDX;
    private static final Set<String> BY_NAME = Set.of(
            "getPackageInfo", "getPackageInfoAsUser",
            "getApplicationInfo", "getApplicationInfoAsUser",
            "getPackageUid", "getPackageUidAsUser");
    private static final Set<String> LISTS = Set.of("getInstalledPackages", "getInstalledApplications",
            "getInstalledPackagesAsUser", "getInstalledApplicationsAsUser");

    static void install() {
        if (ZygoteEntry.isSelf()) return;
        try {
            for (Method m : Class.forName("android.app.ApplicationPackageManager").getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isNative(mod)) continue;
                String n = m.getName();
                Class<?>[] ps = m.getParameterTypes();
                if (BY_NAME.contains(n) && ps.length >= 1 && ps[0] == String.class) {
                    hookThrow(m);
                } else if (LISTS.contains(n) && List.class.isAssignableFrom(m.getReturnType())) {
                    hookFilter(m);
                } else if (n.equals("getPackagesForUid") && m.getReturnType() == String[].class) {
                    hookArray(m);
                } else if (n.equals("getNameForUid") && m.getReturnType() == String.class) {
                    hookNameForUid(m);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static void hookThrow(Method m) {
        try {
            Hooks.hook(m, Hooks.EntryPointType.DIRECT, (original, frame) -> {
                try {
                    if (ZygoteEntry.OWN_PACKAGE.equals(frame.accessor().getReference(1))) {
                        ZygoteEntry.<RuntimeException>sneaky(
                                new PackageManager.NameNotFoundException(ZygoteEntry.OWN_PACKAGE));
                        return;
                    }
                } catch (ClassCastException | IndexOutOfBoundsException ignored) { }
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
            }, Hooks.EntryPointType.DIRECT);
        } catch (Throwable ignored) { }
    }

    private static void hookFilter(Method m) {
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
                    for (Object item : src) {
                        if (!isOwn(item)) dst.add(item);
                    }
                    if (dst.size() != src.size()) frame.accessor().setValue(RET, dst);
                } catch (Throwable ignored) { }
            }, Hooks.EntryPointType.DIRECT);
        } catch (Throwable ignored) { }
    }

    private static void hookArray(Method m) {
        try {
            Hooks.hook(m, Hooks.EntryPointType.DIRECT, (original, frame) -> {
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
                try {
                    Object r = frame.accessor().getReference(RET);
                    if (!(r instanceof String[])) return;
                    String[] src = (String[]) r;
                    List<String> dst = new ArrayList<>(src.length);
                    boolean found = false;
                    for (String s : src) {
                        if (ZygoteEntry.OWN_PACKAGE.equals(s)) { found = true; continue; }
                        dst.add(s);
                    }
                    if (found) frame.accessor().setValue(RET, dst.toArray(new String[0]));
                } catch (Throwable ignored) { }
            }, Hooks.EntryPointType.DIRECT);
        } catch (Throwable ignored) { }
    }

    private static void hookNameForUid(Method m) {
        try {
            Hooks.hook(m, Hooks.EntryPointType.DIRECT, (original, frame) -> {
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
                try {
                    Object r = frame.accessor().getReference(RET);
                    if (ZygoteEntry.OWN_PACKAGE.equals(r)) {
                        frame.accessor().setValue(RET, null);
                    }
                } catch (Throwable ignored) { }
            }, Hooks.EntryPointType.DIRECT);
        } catch (Throwable ignored) { }
    }

    private static boolean isOwn(Object item) {
        if (item == null) return false;
        try {
            return ZygoteEntry.OWN_PACKAGE.equals(item.getClass().getField("packageName").get(item));
        } catch (Throwable ignored) { }
        try {
            Field f = item.getClass().getDeclaredField("packageName");
            f.setAccessible(true);
            return ZygoteEntry.OWN_PACKAGE.equals(f.get(item));
        } catch (Throwable t) {
            return false;
        }
    }

    private PackageGuard() { }
}