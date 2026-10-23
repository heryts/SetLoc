package io.gpsz.zygote;

import android.app.ApplicationPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public final class Check {
    private static int fail;

    private static void expect(String what, boolean cond) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) fail++;
    }

    private static Method find(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        throw new IllegalStateException(name);
    }

    private static Object call(Object self, String name, Object... args) throws Throwable {
        return LsNative.dispatch(find(self.getClass(), name), self, args);
    }

    private static Object fileCall(File f, String name) throws Throwable {
        return LsNative.dispatch(File.class.getDeclaredMethod(name), f);
    }

    public static void main(String[] args) throws Throwable {
        LsNative.ready = false;
        expect("install returns false without engine", !PackageGuardLs.install());
        expect("nothing hooked without engine", LsNative.HOOKS.isEmpty());

        LsNative.ready = true;
        expect("install returns true with engine", PackageGuardLs.install());
        expect("methods hooked", LsNative.HOOKS.size() >= 14);

        ApplicationPackageManager pm = new ApplicationPackageManager();
        String own = ZygoteEntry.OWN_PACKAGE;

        boolean thrown = false;
        try {
            call(pm, "getPackageInfo", own, 0);
        } catch (PackageManager.NameNotFoundException e) {
            thrown = true;
        }
        expect("getPackageInfo(own) throws NameNotFoundException", thrown);
        expect("getPackageInfo(other) passes through", ((PackageInfo) call(pm, "getPackageInfo", "a", 0)).packageName.equals("a"));

        thrown = false;
        try {
            call(pm, "getInstallerPackageName", own);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        expect("getInstallerPackageName(own) throws IllegalArgumentException", thrown);
        expect("getInstallerPackageName(other) passes through", "store".equals(call(pm, "getInstallerPackageName", "a")));

        List<?> list = (List<?>) call(pm, "getInstalledPackages", 0);
        expect("getInstalledPackages drops own", list.size() == 2
                && !((PackageInfo) list.get(0)).packageName.equals(own) && !((PackageInfo) list.get(1)).packageName.equals(own));

        expect("checkPermission(own) denied", Integer.valueOf(-1).equals(call(pm, "checkPermission", "x", own)));
        expect("checkPermission(other) passes through", Integer.valueOf(0).equals(call(pm, "checkPermission", "x", "a")));
        expect("checkSignatures(own) unknown", Integer.valueOf(-4).equals(call(pm, "checkSignatures", own, "a")));

        expect("getPackagesForUid drops own", Arrays.equals((String[]) call(pm, "getPackagesForUid", 1), new String[]{"a", "b"}));
        expect("getNameForUid hides own", call(pm, "getNameForUid", 1) == null);
        expect("getNamesForUids nulls own", Arrays.equals((String[]) call(pm, "getNamesForUids", (Object) new int[]{1, 2}), new String[]{null, "a"}));
        expect("resolveActivity hides own", (ResolveInfo) call(pm, "resolveActivity", new Object(), 0) == null);

        File ownDir = Files.createTempDirectory(own).toFile();
        File otherDir = Files.createTempDirectory("other").toFile();
        expect("native File.exists sees own dir", ownDir.exists());
        expect("File.exists hides own path", Boolean.FALSE.equals(fileCall(ownDir, "exists")));
        expect("File.isDirectory hides own path", Boolean.FALSE.equals(fileCall(ownDir, "isDirectory")));
        expect("File.lastModified hides own path", Long.valueOf(0L).equals(fileCall(ownDir, "lastModified")));
        expect("File.exists passes through other path", Boolean.TRUE.equals(fileCall(otherDir, "exists")));
        ownDir.delete();
        otherDir.delete();

        System.out.println(fail == 0 ? "semua lulus" : fail + " gagal");
        System.exit(fail == 0 ? 0 : 1);
    }

    private Check() { }
}
