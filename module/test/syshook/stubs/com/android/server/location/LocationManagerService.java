package com.android.server.location;

import android.location.Location;
import android.os.IInterface;

public class LocationManagerService {
    public static Location real;
    public static boolean deny;
    public static boolean enabled = true;
    public static Location injected;
    public static int batch, nav, batching, feeds, unfeeds, measure;

    public Location getLastLocation(String provider, String pkg) {
        if (deny) throw new SecurityException("denied");
        return real;
    }

    public boolean isProviderEnabledForUser(String provider, int user) { return enabled; }

    public void injectLocation(Location l) { injected = l; }

    public void registerGnssStatusCallback(IInterface cb, String pkg, String attr) { feeds++; }

    public void registerGnssNmeaCallback(IInterface cb, String pkg, String attr) { feeds++; }

    public boolean addGnssMeasurementsListener(IInterface cb, String pkg, String attr) { measure++; return false; }

    public void unregisterGnssStatusCallback(IInterface cb) { unfeeds++; }

    public void startGnssBatch(long periodNanos, boolean wake) { batch++; }

    public boolean addGnssNavigationMessageListener(IInterface cb, String pkg) { nav++; return true; }

    public boolean addGnssBatchingCallback(IInterface cb, String pkg) { batching++; return true; }
}
