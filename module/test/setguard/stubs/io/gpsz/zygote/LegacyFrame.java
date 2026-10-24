package io.gpsz.zygote;

import java.lang.reflect.Method;

final class LegacyFrame {
    static boolean hook(Method m, Frames.Tx tx) {
        return false;
    }

    private LegacyFrame() { }
}
