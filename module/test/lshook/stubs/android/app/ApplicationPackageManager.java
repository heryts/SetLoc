package android.app;

import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

public class ApplicationPackageManager {
    private static PackageInfo info(String name) {
        PackageInfo p = new PackageInfo();
        p.packageName = name;
        return p;
    }

    public PackageInfo getPackageInfo(String name, int flags) throws PackageManager.NameNotFoundException {
        return info(name);
    }

    public String getInstallerPackageName(String name) {
        return "store";
    }

    public List<PackageInfo> getInstalledPackages(int flags) {
        List<PackageInfo> out = new ArrayList<>();
        out.add(info("a"));
        out.add(info("com.heryts.setloc"));
        out.add(info("b"));
        return out;
    }

    public int checkPermission(String permission, String pkg) {
        return 0;
    }

    public int checkSignatures(String a, String b) {
        return 0;
    }

    public String[] getPackagesForUid(int uid) {
        return new String[]{"a", "com.heryts.setloc", "b"};
    }

    public String getNameForUid(int uid) {
        return "com.heryts.setloc";
    }

    public String[] getNamesForUids(int[] uids) {
        return new String[]{"com.heryts.setloc", "a"};
    }

    public ResolveInfo resolveActivity(Object intent, int flags) {
        ResolveInfo r = new ResolveInfo();
        r.activityInfo = new ActivityInfo();
        r.activityInfo.packageName = "com.heryts.setloc";
        return r;
    }
}
