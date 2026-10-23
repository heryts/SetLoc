package android.os;
import java.io.Serializable;
import java.util.*;
public class Bundle {
  public final Map<String,Object> m = new LinkedHashMap<>();
  public void put(String k, Object v) { m.put(k, v); }
  public Set<String> keySet() { return new LinkedHashSet<>(m.keySet()); }
  public void remove(String k) { m.remove(k); }
  public boolean isEmpty() { return m.isEmpty(); }
  public String getString(String k) { Object v = m.get(k); return v instanceof String ? (String) v : null; }
  public String[] getStringArray(String k) { Object v = m.get(k); return v instanceof String[] ? (String[]) v : null; }
  public Bundle getBundle(String k) { Object v = m.get(k); return v instanceof Bundle ? (Bundle) v : null; }
  public <T extends Serializable> T getSerializable(String k, Class<T> c) { Object v = m.get(k); return c.isInstance(v) ? c.cast(v) : null; }
  public void clear() { m.clear(); }
  public void putInt(String k, int v) { m.put(k, v); }
}
