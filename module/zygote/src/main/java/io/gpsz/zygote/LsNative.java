package io.gpsz.zygote;

import java.lang.reflect.Method;

final class LsNative {
    private static volatile boolean ready;

    static synchronized boolean ensureLoaded() {
        if (ready) return true;
        try {
            System.loadLibrary("gpsz_hook");
            ready = nativeReady();
        } catch (Throwable ignored) {
            ready = false;
        }
        return ready;
    }

    static boolean isReady() {
        return ready;
    }

    private static volatile boolean loc = true;

    static boolean locEnabled() {
        return loc;
    }

    static void setLocEnabled(boolean v) {
        loc = v;
    }

    private static volatile boolean set = true;

    static boolean setEnabled() {
        return set;
    }

    static void setSetEnabled(boolean v) {
        set = v;
    }

    private static volatile boolean sys;

    static boolean sysEnabled() {
        return sys;
    }

    static void setSysEnabled(boolean v) {
        sys = v;
    }

    private static native boolean nativeReady();

    static native Method nativeHook(Method target, Object hooker, Method callback);

    private LsNative() { }
}
