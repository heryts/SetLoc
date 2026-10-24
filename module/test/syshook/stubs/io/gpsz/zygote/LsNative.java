package io.gpsz.zygote;

import java.lang.reflect.Method;

final class LsNative {
    static boolean ensureLoaded() {
        return false;
    }

    static boolean locEnabled() {
        return false;
    }

    static boolean isReady() {
        return false;
    }

    static Method nativeHook(Method target, Object hooker, Method callback) {
        return null;
    }

    private LsNative() { }
}
