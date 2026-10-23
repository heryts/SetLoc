package android.database;
import java.util.*;
public class MatrixCursor implements Cursor {
  private final String[] cols; public final List<Object[]> rows = new ArrayList<>(); private int pos = -1; public boolean closed;
  public MatrixCursor(String[] cols) { this.cols = cols; }
  public void addRow(Object[] r) { rows.add(r); }
  public String[] getColumnNames() { return cols; }
  public int getColumnIndex(String n) { return Arrays.asList(cols).indexOf(n); }
  public int getColumnCount() { return cols.length; }
  public boolean moveToPosition(int p) { pos = p; return p >= 0 && p < rows.size(); }
  public boolean moveToNext() { return moveToPosition(pos + 1); }
  public String getString(int i) { Object o = rows.get(pos)[i]; return o == null ? null : o.toString(); }
  public long getLong(int i) { return ((Number) rows.get(pos)[i]).longValue(); }
  public double getDouble(int i) { return ((Number) rows.get(pos)[i]).doubleValue(); }
  public byte[] getBlob(int i) { return (byte[]) rows.get(pos)[i]; }
  public int getType(int i) { Object o = rows.get(pos)[i]; return o == null ? 0 : o instanceof Long || o instanceof Integer ? 1 : o instanceof Number ? 2 : o instanceof byte[] ? 4 : 3; }
  public void close() { closed = true; }
}
