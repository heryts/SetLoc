package android.database;
import java.util.Collection;
import android.net.Uri;
public abstract class ContentObserver {
  public static int dispatched; public static Object lastUris;
  public ContentObserver(android.os.Handler h) {}
  public void onChange(boolean selfChange) {}
  public void dispatchChange(boolean self, Uri u) { dispatched++; lastUris = u; }
  public void dispatchChange(boolean self, Collection<Uri> us, int flags) { dispatched++; lastUris = us; }
}
