package io.gpsz.zygote;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.VersionedPackage;

import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.vmtools.HookTransformer;
import com.v7878.vmtools.Hooks;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class PackageGuard {
    private static final int RET = EmulatedStackFrame.RETURN_VALUE_IDX;
    private static final String OWN = ZygoteEntry.OWN_PACKAGE;
    private static final Set<String> MISSING = Set.of(
            "getPackageInfo", "getPackageInfoAsUser",
            "getApplicationInfo", "getApplicationInfoAsUser",
            "getPackageUid", "getPackageUidAsUser",
            "getPackageGids", "getInstallSourceInfo",
            "getApplicationIcon", "getApplicationBanner", "getApplicationLogo",
            "getActivityInfo", "getServiceInfo", "getReceiverInfo", "getProviderInfo",
            "getResourcesForApplication");
    private static final Set<String> UNKNOWN = Set.of("getInstallerPackageName", "getApplicationEnabledSetting", "getComponentEnabledSetting");
    private static final Set<String> LISTS = Set.of(
            "getInstalledPackages", "getInstalledApplications",
            "getInstalledPackagesAsUser", "getInstalledApplicationsAsUser",
            "getPackagesHoldingPermissions",
            "queryIntentActivities", "queryIntentActivitiesAsUser", "queryIntentActivityOptions",
            "queryIntentServices", "queryIntentServicesAsUser",
            "queryBroadcastReceivers", "queryBroadcastReceiversAsUser",
            "queryIntentContentProviders", "queryIntentContentProvidersAsUser",
            "queryContentProviders",
            "getSharedLibraries");

    private static final String[] FILE_BOOLS = {"exists", "isDirectory", "isFile", "canRead"};
    private static final String[] FILE_LONGS = {"lastModified", "length"};
    private static final int SIGNATURE_UNKNOWN_PACKAGE = -4;
    private static final int PERMISSION_DENIED = -1;

    static void install() {
        if (ZygoteEntry.isSelf()) return;
        if (PackageGuardLs.install()) return;
        hookFiles();
        try {
            for (Method m : Class.forName("android.app.ApplicationPackageManager").getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isNative(mod)) continue;
                String n = m.getName();
                Class<?>[] ps = m.getParameterTypes();
                Class<?> first = ps.length > 0 ? ps[0] : null;
                if (MISSING.contains(n) && (first == String.class || first == ComponentName.class
                        || first == VersionedPackage.class || first == ApplicationInfo.class)) {
                    hookGuard(m, first == String.class || first == ComponentName.class || first == VersionedPackage.class);
                } else if (UNKNOWN.contains(n) && (first == String.class || first == ComponentName.class)) {
                    hookGuard(m, false);
                } else if (LISTS.contains(n) && List.class.isAssignableFrom(m.getReturnType())) {
                    hookFilter(m);
                } else if (n.equals("checkSignatures") && ps.length == 2 && ps[0] == String.class && ps[1] == String.class) {
                    hookCheck(m, SIGNATURE_UNKNOWN_PACKAGE);
                } else if (n.equals("checkPermission") && ps.length == 2 && ps[0] == String.class && ps[1] == String.class) {
                    hookCheck(m, PERMISSION_DENIED);
                } else if (n.equals("getPackagesForUid") && m.getReturnType() == String[].class) {
                    hookArray(m);
                } else if (n.equals("getNameForUid") && m.getReturnType() == String.class) {
                    hookNameForUid(m);
                } else if (n.equals("getNamesForUids") && m.getReturnType() == String[].class) {
                    hookNamesForUids(m);
                } else if ((n.equals("resolveActivity") || n.equals("resolveService")
                        || n.equals("resolveContentProvider")) && ResolveInfo.class.isAssignableFrom(m.getReturnType())) {
                    hookResolve(m);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static void hook(Method m, HookTransformer t) {
        try {
            Hooks.hook(m, Hooks.EntryPointType.DIRECT, t, Hooks.EntryPointType.DIRECT);
        } catch (Throwable ignored) { }
    }

    private static void hookCheck(Method m, final int result) {
        hook(m, (original, frame) -> {
            try {
                if (OWN.equals(frame.accessor().getReference(1)) || OWN.equals(frame.accessor().getReference(2))) {
                    frame.accessor().setValue(RET, result);
                    return;
                }
            } catch (Throwable ignored) { }
            try {
                Transformers.invokeExactNoChecks(original, frame);
            } catch (Throwable t) {
                ZygoteEntry.<RuntimeException>sneaky(t);
            }
        });
    }

    private static void hookFiles() {
        for (String n : FILE_BOOLS) hookFile(n, Boolean.FALSE);
        for (String n : FILE_LONGS) hookFile(n, Long.valueOf(0L));
    }

    private static void hookFile(String name, final Object hiddenValue) {
        try {
            Method m = File.class.getDeclaredMethod(name);
            hook(m, (original, frame) -> {
                try {
                    Object self = frame.accessor().getReference(0);
                    if (self instanceof File && ((File) self).getPath().contains(OWN)) {
                        frame.accessor().setValue(RET, hiddenValue);
                        return;
                    }
                } catch (Throwable ignored) { }
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
            });
        } catch (Throwable ignored) { }
    }

    private static String pkgOf(Object o) {
        if (o instanceof String) return (String) o;
        if (o instanceof ComponentName) return ((ComponentName) o).getPackageName();
        if (o instanceof VersionedPackage) return ((VersionedPackage) o).getPackageName();
        if (o instanceof ApplicationInfo) return ((ApplicationInfo) o).packageName;
        return null;
    }

    private static void hookGuard(Method m, final boolean checked) {
        hook(m, (original, frame) -> {
            try {
                Object arg = frame.accessor().getReference(1);
                if (OWN.equals(pkgOf(arg))) {
                    ZygoteEntry.<RuntimeException>sneaky(checked
                            ? new PackageManager.NameNotFoundException(String.valueOf(arg))
                            : new IllegalArgumentException(arg instanceof ComponentName
                                    ? "Unknown component: " + arg : "Unknown package: " + OWN));
                    return;
                }
            } catch (ClassCastException | IndexOutOfBoundsException ignored) { }
            try {
                Transformers.invokeExactNoChecks(original, frame);
            } catch (Throwable t) {
                ZygoteEntry.<RuntimeException>sneaky(t);
            }
        });
    }

    private static void hookResolve(Method m) {
        hook(m, (original, frame) -> {
            try {
                Transformers.invokeExactNoChecks(original, frame);
            } catch (Throwable t) {
                ZygoteEntry.<RuntimeException>sneaky(t);
            }
            try {
                Object r = frame.accessor().getReference(RET);
                if (r != null && owns(r)) frame.accessor().setValue(RET, null);
            } catch (Throwable ignored) { }
        });
    }

    private static void hookFilter(Method m) {
        hook(m, (original, frame) -> {
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
                    if (!owns(item)) dst.add(item);
                }
                if (dst.size() != src.size()) frame.accessor().setValue(RET, dst);
            } catch (Throwable ignored) { }
        });
    }

    private static void hookArray(Method m) {
        hook(m, (original, frame) -> {
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
                for (String s : src) {
                    if (!OWN.equals(s)) dst.add(s);
                }
                if (dst.size() != src.length) frame.accessor().setValue(RET, dst.toArray(new String[0]));
            } catch (Throwable ignored) { }
        });
    }

    private static void hookNamesForUids(Method m) {
        hook(m, (original, frame) -> {
            try {
                Transformers.invokeExactNoChecks(original, frame);
            } catch (Throwable t) {
                ZygoteEntry.<RuntimeException>sneaky(t);
            }
            try {
                Object r = frame.accessor().getReference(RET);
                if (!(r instanceof String[])) return;
                String[] src = (String[]) r;
                boolean changed = false;
                String[] dst = new String[src.length];
                for (int i = 0; i < src.length; i++) {
                    if (OWN.equals(src[i])) { dst[i] = null; changed = true; }
                    else dst[i] = src[i];
                }
                if (changed) frame.accessor().setValue(RET, dst);
            } catch (Throwable ignored) { }
        });
    }

    private static void hookNameForUid(Method m) {
        hook(m, (original, frame) -> {
            try {
                Transformers.invokeExactNoChecks(original, frame);
            } catch (Throwable t) {
                ZygoteEntry.<RuntimeException>sneaky(t);
            }
            try {
                if (OWN.equals(frame.accessor().getReference(RET))) frame.accessor().setValue(RET, null);
            } catch (Throwable ignored) { }
        });
    }

    private static boolean owns(Object item) {
        if (item instanceof ResolveInfo) {
            ResolveInfo r = (ResolveInfo) item;
            return owns(r.activityInfo) || owns(r.serviceInfo) || owns(r.providerInfo);
        }
        if (item instanceof PackageItemInfo) return OWN.equals(((PackageItemInfo) item).packageName);
        if (item instanceof PackageInfo) return OWN.equals(((PackageInfo) item).packageName);
        if (item instanceof ApplicationInfo) return OWN.equals(((ApplicationInfo) item).packageName);
        return false;
    }

    private PackageGuard() { }
}