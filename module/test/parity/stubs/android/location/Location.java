package android.location;
import android.os.Bundle;
public class Location {
  public String provider;
  public double lat, lng, alt;
  public float acc, spd, brg, vacc, sacc, bacc;
  public boolean hasAlt, hasSpd, hasBrg, hasVacc, hasSacc, hasBacc;
  public long ns, ms;
  public Bundle extras;
  public Location(String p) { provider = p; }
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
  public void setElapsedRealtimeNanos(long v) { ns = v; }
  public void setTime(long v) { ms = v; }
}
