package io.gpsz.zygote;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.VersionedPackage;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

final class PackageGuardLs {
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

    static boolean install() {
        if (ZygoteEntry.isSelf()) return false;
        if (!LsNative.isReady()) return false;
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
                    hookGuard(m, first != ApplicationInfo.class);
                } else if (UNKNOWN.contains(n) && (first == String.class || first == ComponentName.class)) {
                    hookGuard(m, false);
                } else if (LISTS.contains(n) && List.class.isAssignableFrom(m.getReturnType())) {
                    after(m, PackageGuardLs::dropOwnItems);
                } else if (n.equals("checkSignatures") && ps.length == 2 && ps[0] == String.class && ps[1] == String.class) {
                    hookCheck(m, SIGNATURE_UNKNOWN_PACKAGE);
                } else if (n.equals("checkPermission") && ps.length == 2 && ps[0] == String.class && ps[1] == String.class) {
                    hookCheck(m, PERMISSION_DENIED);
                } else if (n.equals("getPackagesForUid") && m.getReturnType() == String[].class) {
                    after(m, PackageGuardLs::dropOwnNames);
                } else if (n.equals("getNameForUid") && m.getReturnType() == String.class) {
                    after(m, r -> OWN.equals(r) ? null : r);
                } else if (n.equals("getNamesForUids") && m.getReturnType() == String[].class) {
                    after(m, PackageGuardLs::nullOwnNames);
                } else if ((n.equals("resolveActivity") || n.equals("resolveService")
                        || n.equals("resolveContentProvider")) && ResolveInfo.class.isAssignableFrom(m.getReturnType())) {
                    after(m, r -> r != null && owns(r) ? null : r);
                }
            }
        } catch (Throwable ignored) { }
        return true;
    }

    private static void after(Method m, UnaryOperator<Object> transform) {
        LsHook.hook(m, (args, original) -> {
            Object r = original.call(args);
            try {
                return transform.apply(r);
            } catch (Throwable ignored) {
                return r;
            }
        });
    }

    private static void hookCheck(Method m, final int result) {
        final Integer hidden = result;
        LsHook.hook(m, (args, original) -> {
            if (OWN.equals(args[1]) || OWN.equals(args[2])) return hidden;
            return original.call(args);
        });
    }

    private static void hookFiles() {
        for (String n : FILE_BOOLS) hookFile(n, Boolean.FALSE);
        for (String n : FILE_LONGS) hookFile(n, Long.valueOf(0L));
    }

    private static void hookFile(String name, final Object hiddenValue) {
        try {
            Method m = File.class.getDeclaredMethod(name);
            LsHook.hook(m, (args, original) -> {
                Object self = args[0];
                if (self instanceof File && ((File) self).getPath().contains(OWN)) return hiddenValue;
                return original.call(args);
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
        LsHook.hook(m, (args, original) -> {
            Object arg = args[1];
            if (OWN.equals(pkgOf(arg))) {
                throw checked
                        ? new PackageManager.NameNotFoundException(String.valueOf(arg))
                        : new IllegalArgumentException(arg instanceof ComponentName
                                ? "Unknown component: " + arg : "Unknown package: " + OWN);
            }
            return original.call(args);
        });
    }

    private static Object dropOwnItems(Object r) {
        if (!(r instanceof List)) return r;
        List<?> src = (List<?>) r;
        List<Object> dst = new ArrayList<>(src.size());
        for (Object item : src) {
            if (!owns(item)) dst.add(item);
        }
        return dst.size() != src.size() ? dst : r;
    }

    private static Object dropOwnNames(Object r) {
        if (!(r instanceof String[])) return r;
        String[] src = (String[]) r;
        List<String> dst = new ArrayList<>(src.length);
        for (String s : src) {
            if (!OWN.equals(s)) dst.add(s);
        }
        return dst.size() != src.length ? dst.toArray(new String[0]) : r;
    }

    private static Object nullOwnNames(Object r) {
        if (!(r instanceof String[])) return r;
        String[] src = (String[]) r;
        boolean changed = false;
        String[] dst = new String[src.length];
        for (int i = 0; i < src.length; i++) {
            if (OWN.equals(src[i])) {
                dst[i] = null;
                changed = true;
            } else {
                dst[i] = src[i];
            }
        }
        return changed ? dst : r;
    }

    private static boolean owns(Object item) {
        if (item instanceof ResolveInfo) {
            ResolveInfo r = (ResolveInfo) item;
            return owns(r.activityInfo) || owns(r.serviceInfo) || owns(r.providerInfo);
        }
        if (item instanceof PackageItemInfo) return OWN.equals(((PackageItemInfo) item).packageName);
        if (item instanceof PackageInfo) return OWN.equals(((PackageInfo) item).packageName);
        return false;
    }

    private PackageGuardLs() { }
}
