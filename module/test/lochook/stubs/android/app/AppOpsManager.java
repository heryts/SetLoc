package android.app;
public class AppOpsManager {
  public int checkOpNoThrow(int op, int uid, String pkg) { return 0; }
  public int unsafeCheckOp(String op, int uid, String pkg) { return 0; }
}
