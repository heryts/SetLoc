package android.telephony;
import java.util.*;
import java.util.concurrent.Executor;
public class TelephonyManager {
  public interface CellInfoCallback { void onCellInfo(List<CellInfo> l); }
  public static int updateCalls;
  public List<CellInfo> getAllCellInfo() { return new ArrayList<>(Arrays.asList(new CellInfo())); }
  public Object getCellLocation() { return "cell"; }
  public void requestCellInfoUpdate(Executor e, CellInfoCallback c) { updateCalls++; }
}
