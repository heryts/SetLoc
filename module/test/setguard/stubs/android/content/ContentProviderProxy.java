package android.content;
import android.os.Bundle;
public class ContentProviderProxy { public Bundle call(String authority, String method, String arg, Bundle extras) { return android.content.ContentResolver.nextCall; } }
