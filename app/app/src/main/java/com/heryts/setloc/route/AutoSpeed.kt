package com.heryts.setloc.route

/** Kecepatan auto per mode (0 jalan kaki, 1 sepeda, 2 motor, 3 mobil), km/jam. */
object AutoSpeed {
    private val DEFAULT = floatArrayOf(5f, 15f, 40f, 50f)
    private val MIN = floatArrayOf(3f, 8f, 15f, 10f)
    private val MAX = floatArrayOf(7f, 25f, 70f, 110f)

    fun kmh(mode: Int, distanceM: Double, durationS: Double?): Float {
        val m = mode.coerceIn(0, DEFAULT.size - 1)
        if (durationS != null && durationS > 30.0 && distanceM > 100.0) {
            val v = (distanceM / durationS * 3.6).toFloat().coerceIn(MIN[m], MAX[m])
            return Math.round(v * 10f) / 10f
        }
        return DEFAULT[m]
    }
}
