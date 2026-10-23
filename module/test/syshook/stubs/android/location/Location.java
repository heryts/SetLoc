package android.location;
import android.os.Bundle;
public class Location {
  public static final Object CREATOR = new Object() {
    public Location createFromParcel(Object parcel) {
      Location l = new Location("gps");
      l.lat = 1.0; l.lng = 2.0; l.ns = 5_000_000L; l.ms = System.currentTimeMillis();
      return l;
    }
  };
  public String provider;
  public double lat, lng, alt;
  public float acc, spd, brg, vacc, sacc, bacc;
  public boolean hasAlt, hasSpd, hasBrg, hasVacc, hasSacc, hasBacc, mock = true;
  public long ns, ms;
  public Bundle extras;
  public Location(String p) { provider = p; }
  public Location(Location o) { set(o); }
  public void set(Location o) {
    provider = o.provider; lat = o.lat; lng = o.lng; alt = o.alt; acc = o.acc; spd = o.spd; brg = o.brg;
    vacc = o.vacc; sacc = o.sacc; bacc = o.bacc; hasAlt = o.hasAlt; hasSpd = o.hasSpd; hasBrg = o.hasBrg;
    hasVacc = o.hasVacc; hasSacc = o.hasSacc; hasBacc = o.hasBacc; ns = o.ns; ms = o.ms; extras = o.extras;
  }
  public String getProvider() { return provider; }
  public void setProvider(String p) { provider = p; }
  public double getLatitude() { return lat; }
  public double getLongitude() { return lng; }
  public float getAccuracy() { return acc; }
  public boolean hasAccuracy() { return acc > 0f; }
  public double getAltitude() { return alt; }
  public float getSpeed() { return spd; }
  public float getBearing() { return brg; }
  public boolean hasAltitude() { return hasAlt; }
  public boolean hasSpeed() { return hasSpd; }
  public boolean hasBearing() { return hasBrg; }
  public float getVerticalAccuracyMeters() { return vacc; }
  public float getSpeedAccuracyMetersPerSecond() { return sacc; }
  public float getBearingAccuracyDegrees() { return bacc; }
  public boolean hasVerticalAccuracy() { return hasVacc; }
  public boolean hasSpeedAccuracy() { return hasSacc; }
  public boolean hasBearingAccuracy() { return hasBacc; }
  public double getMslAltitudeMeters() { return 0; }
  public float getMslAltitudeAccuracyMeters() { return 0; }
  public boolean hasMslAltitude() { return false; }
  public boolean hasMslAltitudeAccuracy() { return false; }
  public boolean isFromMockProvider() { return mock; }
  public boolean isMock() { return mock; }
  public void setIsFromMockProvider(boolean v) { mock = v; }
  public float distanceTo(Location o) { return (float) Math.hypot(lat - o.lat, lng - o.lng); }
  public float bearingTo(Location o) { return (float) Math.atan2(o.lng - lng, o.lat - lat); }
  public String toString() { return "Location[" + provider + " " + lat + "," + lng + "]"; }
  public void setLatitude(double v) { lat = v; }
  public void setLongitude(double v) { lng = v; }
  public void setAccuracy(float v) { acc = v; }
  public void setAltitude(double v) { alt = v; hasAlt = true; }
  public void setSpeed(float v) { spd = v; hasSpd = true; }
  public void setBearing(float v) { brg = v; hasBrg = true; }
  public void setVerticalAccuracyMeters(float v) { vacc = v; hasVacc = true; }
  public void setSpeedAccuracyMetersPerSecond(float v) { sacc = v; hasSacc = true; }
  public void setBearingAccuracyDegrees(float v) { bacc = v; hasBacc = true; }
  public void removeAltitude() { hasAlt = false; }
  public void removeSpeed() { hasSpd = false; }
  public void removeBearing() { hasBrg = false; }
  public void removeVerticalAccuracy() { hasVacc = false; }
  public void removeSpeedAccuracy() { hasSacc = false; }
  public void removeBearingAccuracy() { hasBacc = false; }
  public Bundle getExtras() { return extras; }
  public void setExtras(Bundle b) { extras = b; }
  public long getElapsedRealtimeNanos() { return ns; }
  public void setElapsedRealtimeNanos(long v) { ns = v; }
  public long getTime() { return ms; }
  public void setTime(long v) { ms = v; }
}
