package android.provider;
import android.content.ContentResolver;
public class Settings {
  public static class NameValueTable { public static final String VALUE = "value"; }
  public static class NameValueCache {
    public static java.util.Map<String,String> prefix = new java.util.HashMap<>();
    public String getStringForUser(ContentResolver r, String name, int user) { return "orig:" + name; }
    public java.util.Map<String,String> getStringsForPrefix(ContentResolver r, String p, java.util.List<String> names) { return new java.util.HashMap<>(prefix); }
  }
  public static class Secure {
    public static int getInt(ContentResolver r, String n) { return 0; }
    public static String getString(ContentResolver r, String n) { return "orig"; }
  }
  public static class Global {
    public static final String BOOT_COUNT="boot_count";
    public static final java.util.Map<String,String> store=new java.util.HashMap<>();
    public static android.net.Uri getUriFor(String k){return new android.net.Uri();}
    public static String getString(ContentResolver r,String k){return store.get(k);}
    public static int getInt(ContentResolver r,String k,int d){return d;}
    public static boolean putString(ContentResolver r,String k,String v){store.put(k,v);return true;}
  }
}
