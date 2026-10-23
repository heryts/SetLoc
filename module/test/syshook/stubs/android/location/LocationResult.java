package android.location;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
public final class LocationResult {
  private final List<Location> list;
  private LocationResult(List<Location> l) { list = new ArrayList<>(l); }
  public static LocationResult wrap(List<Location> l) { return new LocationResult(l); }
  public int size() { return list.size(); }
  public Location get(int i) { return list.get(i); }
  public LocationResult map(Function<Location, Location> f) {
    List<Location> o = new ArrayList<>();
    for (Location l : list) o.add(f.apply(l));
    return new LocationResult(o);
  }
}
