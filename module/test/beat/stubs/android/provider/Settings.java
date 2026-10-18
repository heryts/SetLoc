package android.provider;
public class Settings {
  public static class Global {
    public static final String BOOT_COUNT="boot_count";
    public static final java.util.Map<String,String> store=new java.util.HashMap<>();
    public static android.net.Uri getUriFor(String k){return new android.net.Uri();}
    public static String getString(android.content.ContentResolver r,String k){return store.get(k);}
    public static int getInt(android.content.ContentResolver r,String k,int d){String v=store.get(k);if(v==null)return d;try{return Integer.parseInt(v);}catch(Exception e){return d;}}
    public static boolean putString(android.content.ContentResolver r,String k,String v){store.put(k,v);return true;}
  }
}
