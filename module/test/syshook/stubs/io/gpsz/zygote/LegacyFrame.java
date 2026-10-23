package io.gpsz.zygote;
import java.lang.reflect.Method;
final class LegacyFrame {
  static final java.util.List<Method> HOOKED = new java.util.ArrayList<>();
  static boolean hook(Method m, Frames.Tx tx) {
    HOOKED.add(m);
    return true;
  }
  private LegacyFrame() { }
}
