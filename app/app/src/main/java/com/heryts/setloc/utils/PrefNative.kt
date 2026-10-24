package com.heryts.setloc.utils

import com.heryts.setloc.route.RouteNative

internal object PrefNative {
    const val ROUTE_LIVE = 1
    const val ROUTE_ENDED_OFF = 2
    const val STATE_FILE = "setloc_state"

    @JvmStatic
    private external fun nativeSpeedVar(unit: Int, speedKmh: Float, raw: Float): Float

    @JvmStatic
    private external fun nativeStateGet(path: String, key: String): String?

    @JvmStatic
    private external fun nativeStatePatch(path: String, patch: String): Boolean

    @JvmStatic
    private external fun nativeRouteState(path: String, bootNow: Int, nowMs: Long): Int

    @JvmStatic
    private external fun nativeCfgOff(lat: Double, lng: Double, acc: Float): String?

    private inline fun <T> guard(fallback: T, block: () -> T): T {
        if (!RouteNative.ready) return fallback
        return try {
            block()
        } catch (_: UnsatisfiedLinkError) {
            fallback
        }
    }

    fun speedVar(unit: Int, speedKmh: Float, raw: Float): Float = guard(-1f) { nativeSpeedVar(unit, speedKmh, raw) }

    fun stateGet(path: String, key: String): String? = guard(null) { nativeStateGet(path, key) }

    fun statePatch(path: String, patch: String): Boolean = guard(false) { nativeStatePatch(path, patch) }

    fun routeState(path: String, bootNow: Int, nowMs: Long): Int = guard(0) { nativeRouteState(path, bootNow, nowMs) }

    fun cfgOff(lat: Double, lng: Double, acc: Float): String? = guard(null) { nativeCfgOff(lat, lng, acc) }
}
