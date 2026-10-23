package com.android.server.location.provider;

import android.location.LocationResult;
import java.util.ArrayList;
import java.util.List;

public class LocationProviderManager {
    public static class Emergency {
        public boolean on;
        public boolean isInEmergency(long extra) { return on; }
    }

    public static final List<LocationResult> MAIN = new ArrayList<>();
    private final String name;
    private final Emergency mEmergencyHelper = new Emergency();

    public LocationProviderManager(String name) { this.name = name; }

    public String getName() { return name; }

    public Emergency emergency() { return mEmergencyHelper; }

    public void onReportLocation(LocationResult result) {
        if ("main".equals(Thread.currentThread().getName())) MAIN.add(result);
    }
}
