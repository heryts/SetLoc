package android.os;
public class SystemClock {
  public static long fake = 100_000;
  public static long up = -1;
  public static long elapsedRealtime() { return fake; }
  public static long elapsedRealtimeNanos() { return fake * 1_000_000L; }
  public static long uptimeMillis() { return up < 0 ? fake : up; }
}
