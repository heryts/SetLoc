package io.gpsz.zygote;
import java.lang.reflect.Method;
/** Pengganti uji: mencatat metode yang jatuh ke jalur lama. */
final class LegacyFrame {
  static final java.util.List<Method> HOOKED = new java.util.ArrayList<>();
  static boolean fail;
  static boolean hook(Method m, Frames.Tx tx) {
    if (fail) { Frames.legacyBroken = true; return false; }
    HOOKED.add(m);
    return true;
  }
  private LegacyFrame() { }
}
