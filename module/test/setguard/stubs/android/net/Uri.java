package android.net;
public class Uri {
  private final String authority, last;
  public Uri() { this(null, null); }
  public Uri(String authority, String last) { this.authority = authority; this.last = last; }
  public static Uri settings(String key) { return new Uri("settings", key); }
  public String getAuthority() { return authority; }
  public String getLastPathSegment() { return last; }
}
