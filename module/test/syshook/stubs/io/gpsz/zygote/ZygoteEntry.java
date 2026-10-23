package io.gpsz.zygote;
final class ZygoteEntry {
  @SuppressWarnings("unchecked")
  static <T extends Throwable> void sneaky(Throwable t) throws T { throw (T) t; }
  private ZygoteEntry() { }
}
