package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng

internal object RouteNative {
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

    fun build(
        points: List<LatLng>, speedKmh: Float, varKmh: Float, elev: FloatArray?, manual: Float
    ): List<RouteRunner.Tick>? {
        if (!ready) return null
        val pts = DoubleArray(points.size * 2)
        for (i in points.indices) {
            pts[i * 2] = points[i].latitude
            pts[i * 2 + 1] = points[i].longitude
        }
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
}
