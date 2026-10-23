package android.location;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
public class LocationManager {
  public static final String GPS_PROVIDER = "gps";
  public static final String NETWORK_PROVIDER = "network";
  public static final String FUSED_PROVIDER = "fused";
  public static boolean providerEnabled = false, locationEnabled = false, throwLastKnown = false;
  public static int addNmeaCalls;
  public Location getLastKnownLocation(String p) {
    if (throwLastKnown) throw new SecurityException("no permission");
    return null;
  }
  public boolean isProviderEnabled(String p) { return providerEnabled; }
  public boolean isLocationEnabled() { return locationEnabled; }
  public List<String> getProviders(boolean enabledOnly) { return new ArrayList<>(Arrays.asList("passive")); }
  public List<String> getAllProviders() { return new ArrayList<>(Arrays.asList("passive")); }
  public boolean addNmeaListener(OnNmeaMessageListener l) { addNmeaCalls++; return false; }
  public boolean addNmeaListener(Executor e, OnNmeaMessageListener l) { addNmeaCalls++; return false; }
  public boolean registerGnssStatusCallback(GnssStatus.Callback c, android.os.Handler h) { return false; }
  public boolean registerGnssStatusCallback(Executor e, GnssStatus.Callback c) { return false; }
  public void unregisterGnssStatusCallback(GnssStatus.Callback c) { }
  public boolean registerGnssMeasurementsCallback(Executor e, GnssMeasurementsEvent.Callback c) { return false; }
  public void removeNmeaListener(OnNmeaMessageListener l) { }
  public boolean getCurrentLocation(String p, Object cancel, Executor e, Consumer<Location> c) { return false; }
}
