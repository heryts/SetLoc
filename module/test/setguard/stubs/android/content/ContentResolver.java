package android.content;
import android.net.Uri; import android.os.Bundle; import android.database.*;
public class ContentResolver {
  public static final String QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection", QUERY_ARG_SQL_SORT_ORDER = "android:query-arg-sql-sort-order",
    QUERY_ARG_SQL_GROUP_BY = "android:query-arg-sql-group-by", QUERY_ARG_SQL_HAVING = "android:query-arg-sql-having",
    QUERY_ARG_SQL_SELECTION_ARGS = "android:query-arg-sql-selection-args", QUERY_ARG_SORT_COLUMNS = "android:query-arg-sort-columns";
  public static Cursor next; public static Bundle nextCall; public static int registered;
  public Cursor query(Uri u, String[] proj, Bundle args, Object cancel) { return next; }
  public Bundle call(Uri u, String method, String arg, Bundle extras) { return nextCall; }
  public void registerContentObserver(Uri u, boolean d, ContentObserver o) { registered++; }
}
