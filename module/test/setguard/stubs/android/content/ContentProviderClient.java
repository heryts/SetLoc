package android.content;
import android.net.Uri; import android.os.Bundle; import android.database.Cursor;
public class ContentProviderClient {
  public Cursor query(Uri u, String[] p, Bundle a, Object c) { return android.content.ContentResolver.next; }
  public Bundle call(String method, String arg, Bundle extras) { return android.content.ContentResolver.nextCall; }
}
