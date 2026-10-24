package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MetersTest {
    private fun haversine(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * 6371000.0 * asin(sqrt(h))
    }

    @Test
    fun `jarak akurat di cabang dekat dan jauh`() {
        assertEquals(111195.0, RouteRunner.meters(LatLng(0.0, 0.0), LatLng(1.0, 0.0)), 300.0)
        val a = LatLng(45.0, 7.0)
        for (dLng in doubleArrayOf(0.0005, 0.2, 0.55, 0.58, 0.6, 3.0)) {
            val b = LatLng(45.0, 7.0 + dLng)
            val ref = haversine(a, b)
            assertEquals(ref, RouteRunner.meters(a, b), ref * 0.001 + 0.05)
        }
        val n = LatLng(45.0 + 100.0 / 111195.0, 7.0)
        assertEquals(100.0, RouteRunner.meters(a, n), 0.5)
    }
}
