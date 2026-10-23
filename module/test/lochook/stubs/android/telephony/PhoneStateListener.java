package android.telephony;
import java.util.List;
public class PhoneStateListener {
  public static class IPhoneStateListenerStub {
    public static List<CellInfo> lastCells;
    public static int locCalls;
    public void onCellInfoChanged(List<CellInfo> l) { lastCells = l; }
    public void onCellLocationChanged(Object loc) { locCalls++; }
  }
}
