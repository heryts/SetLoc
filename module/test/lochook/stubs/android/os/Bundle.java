package android.os;
public class Bundle {
  public final java.util.Map<String,Integer> m = new java.util.HashMap<>();
  public void clear() { m.clear(); }
  public void putInt(String k, int v) { m.put(k, v); }
  public int getInt(String k, int d) { Integer v = m.get(k); return v == null ? d : v; }
}
