package io.gpsz.zygote;
final class ZygoteEntry {
  static final String OWN_PACKAGE = "com.heryts.setloc";
  static boolean isSelf() { return false; }
  @SuppressWarnings("unchecked")
  static <T extends Throwable> void sneaky(Throwable t) throws T { throw (T) t; }
  private ZygoteEntry() { }
}
