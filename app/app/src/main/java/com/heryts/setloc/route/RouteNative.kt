package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng

internal class Plan(val waitMs: Long, val autoStop: Boolean, val endWaitMs: Long, val lineWidth: Int, val holdEndMs: Long)

internal interface RouteSchedule {
    fun plan(waitStartMin: Float, waitEndMin: Float, n: Int, chars: Long): Plan?
    fun tickAt(now: Long, t0: Long, n: Int): Int
    fun sleepMs(now: Long, t0: Long, k: Int): Long
    fun endAt(t0: Long, n: Int, endWaitMs: Long): Long
    fun prefDue(thin: Boolean, counter: Int): Boolean
}

internal object RouteNative : RouteSchedule {
    private const val STRIDE = 6
    private const val CHUNK = 2000

    @Volatile private var state = 0

    private fun note(msg: String) {
        try {
            android.util.Log.w("SetLocRoute", msg)
        } catch (t: Throwable) {
        }
    }

    val ready: Boolean
        get() {
            if (state == 0) {
                synchronized(this) {
                    if (state == 0) {
                        state = try {
                            System.loadLibrary("routecore")
                            note("routecore=ok")
                            1
                        } catch (t: Throwable) {
                            note("routecore=gagal ${t.javaClass.simpleName}: ${t.message}")
                            2
                        }
                    }
                }
            }
            return state == 1
        }

    @JvmStatic
    private external fun nativeBuild(
        pts: DoubleArray, speedKmh: Float, varKmh: Float, elev: FloatArray?, manual: Float, seed: Long
    ): DoubleArray?

    @JvmStatic
    private external fun nativeFormat(flat: DoubleArray, from: Int, count: Int): String?

    @JvmStatic
    private external fun nativePolylineComplete(s: String): Boolean

    @JvmStatic
    private external fun nativePolylineDecode(s: String): DoubleArray?

    @JvmStatic
    private external fun nativePolylineEncode(pts: DoubleArray): String?

    @JvmStatic
    private external fun nativeAutoSpeed(mode: Int, distanceM: Double, durationS: Double): Float

    @JvmStatic
    private external fun nativeRouteLength(pts: DoubleArray): Double

    @JvmStatic
    private external fun nativeTooLong(pts: DoubleArray, speedKmh: Float): Boolean

    @JvmStatic
    private external fun nativeBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float

    @JvmStatic
    private external fun nativeAltitudeAt(elev: FloatArray?, manual: Float, fraction: Double): Float

    @JvmStatic
    private external fun nativeSample(pts: DoubleArray, n: Int, max: Int): DoubleArray?

    @JvmStatic
    private external fun nativePlan(waitStartMin: Float, waitEndMin: Float, n: Int, chars: Long): LongArray?

    @JvmStatic
    private external fun nativeTickAt(now: Long, t0: Long, n: Int): Int

    @JvmStatic
    private external fun nativeSleepMs(now: Long, t0: Long, k: Int): Long

    @JvmStatic
    private external fun nativeEndAt(t0: Long, n: Int, endWaitMs: Long): Long

    @JvmStatic
    private external fun nativePrefDue(thin: Boolean, counter: Int): Boolean

    internal fun flatten(points: List<LatLng>): DoubleArray {
        val pts = DoubleArray(points.size * 2)
        for (i in points.indices) {
            pts[i * 2] = points[i].latitude
            pts[i * 2 + 1] = points[i].longitude
        }
        return pts
    }

    fun routeLength(points: List<LatLng>): Double {
        if (!ready) return 0.0
        return try {
            nativeRouteLength(flatten(points))
        } catch (t: Throwable) {
            0.0
        }
    }

    fun tooLong(points: List<LatLng>, speedKmh: Float): Boolean {
        if (!ready) return false
        return try {
            nativeTooLong(flatten(points), speedKmh)
        } catch (t: Throwable) {
            false
        }
    }

    fun bearing(a: LatLng, b: LatLng): Float {
        if (!ready) return -1f
        return try {
            nativeBearing(a.latitude, a.longitude, b.latitude, b.longitude)
        } catch (t: Throwable) {
            -1f
        }
    }

    fun altitudeAt(elev: FloatArray?, manual: Float, fraction: Double): Float {
        if (!ready) return manual
        return try {
            nativeAltitudeAt(elev, manual, fraction)
        } catch (t: Throwable) {
            manual
        }
    }

    fun sample(points: List<LatLng>, n: Int, max: Int): List<LatLng> {
        if (!ready) return emptyList()
        val res: DoubleArray? = try {
            nativeSample(flatten(points), n, max)
        } catch (t: Throwable) {
            null
        }
        val flat = res ?: return emptyList()
        val out = ArrayList<LatLng>(flat.size / 2)
        for (i in 0 until flat.size / 2) out.add(LatLng(flat[i * 2], flat[i * 2 + 1]))
        return out
    }

    fun autoSpeed(mode: Int, distanceM: Double, durationS: Double?): Float {
        if (!ready) return 0f
        return try {
            nativeAutoSpeed(mode, distanceM, durationS ?: Double.NaN)
        } catch (t: Throwable) {
            0f
        }
    }

    fun polylineComplete(encoded: String): Boolean {
        if (!ready) return false
        return try {
            nativePolylineComplete(encoded)
        } catch (t: Throwable) {
            false
        }
    }

    fun polylineDecode(encoded: String): List<LatLng>? {
        if (!ready) return null
        val res: DoubleArray? = try {
            nativePolylineDecode(encoded)
        } catch (t: Throwable) {
            null
        }
        val flat = res ?: return null
        val out = ArrayList<LatLng>(flat.size / 2)
        for (i in 0 until flat.size / 2) out.add(LatLng(flat[i * 2], flat[i * 2 + 1]))
        return out
    }

    fun polylineEncode(points: List<LatLng>): String? {
        if (!ready) return null
        val pts = flatten(points)
        return try {
            nativePolylineEncode(pts)
        } catch (t: Throwable) {
            null
        }
    }

    fun build(
        points: List<LatLng>, speedKmh: Float, varKmh: Float, elev: FloatArray?, manual: Float
    ): List<RouteRunner.Tick>? {
        if (!ready) return null
        val pts = flatten(points)
        val res: DoubleArray? = try {
            nativeBuild(pts, speedKmh, varKmh, elev, manual, System.nanoTime())
        } catch (t: Throwable) {
            null
        }
        val flat = res ?: return null
        val n = flat.size / STRIDE
        val out = ArrayList<RouteRunner.Tick>(n)
        for (i in 0 until n) {
            val o = i * STRIDE
            out.add(
                RouteRunner.Tick(
                    flat[o], flat[o + 1], flat[o + 2].toFloat(), flat[o + 3].toFloat(),
                    flat[o + 4].toFloat(), flat[o + 5].toInt()
                )
            )
        }
        return out
    }

    fun write(ticks: List<RouteRunner.Tick>, w: java.io.Writer): Long? {
        if (!ready) return null
        val n = ticks.size
        val flat = DoubleArray(n * STRIDE)
        for (i in 0 until n) {
            val t = ticks[i]
            val o = i * STRIDE
            flat[o] = t.lat
            flat[o + 1] = t.lng
            flat[o + 2] = t.spd.toDouble()
            flat[o + 3] = t.brg.toDouble()
            flat[o + 4] = t.alt.toDouble()
            flat[o + 5] = t.pct.toDouble()
        }
        val parts = ArrayList<String>()
        var from = 0
        var chars = 0L
        try {
            while (from < n) {
                val c = minOf(CHUNK, n - from)
                val s = nativeFormat(flat, from, c) ?: return null
                parts.add(s)
                chars += s.length
                from += c
            }
        } catch (t: Throwable) {
            return null
        }
        for (s in parts) w.append(s)
        return chars
    }

    override fun plan(waitStartMin: Float, waitEndMin: Float, n: Int, chars: Long): Plan? {
        if (!ready) return null
        val v = try {
            nativePlan(waitStartMin, waitEndMin, n, chars)
        } catch (t: Throwable) {
            null
        }
        if (v == null || v.size < 5) return null
        return Plan(v[0], v[1] != 0L, v[2], v[3].toInt(), v[4])
    }

    override fun tickAt(now: Long, t0: Long, n: Int): Int {
        if (!ready) return -1
        return try {
            nativeTickAt(now, t0, n)
        } catch (t: Throwable) {
            -1
        }
    }

    override fun sleepMs(now: Long, t0: Long, k: Int): Long {
        if (!ready) return 500L
        return try {
            nativeSleepMs(now, t0, k)
        } catch (t: Throwable) {
            500L
        }
    }

    override fun endAt(t0: Long, n: Int, endWaitMs: Long): Long {
        if (!ready) return t0
        return try {
            nativeEndAt(t0, n, endWaitMs)
        } catch (t: Throwable) {
            t0
        }
    }

    override fun prefDue(thin: Boolean, counter: Int): Boolean {
        if (!ready) return true
        return try {
            nativePrefDue(thin, counter)
        } catch (t: Throwable) {
            true
        }
    }
}
