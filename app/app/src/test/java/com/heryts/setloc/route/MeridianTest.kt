package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class MeridianTest {
    @Before
    fun setUp() {
        RouteRunner.elevations = floatArrayOf(0f, 0f)
    }

    @Test
    fun `interpolasi bujur melewati 180 lewat jalur terpendek`() {
        assertEquals(180.0, abs(RouteRunner.lerpLng(179.9, -179.9, 0.5)), 1e-9)
        assertEquals(-179.95, RouteRunner.lerpLng(-179.9, 179.9, 0.25), 1e-9)
        assertEquals(110.45, RouteRunner.lerpLng(110.4, 110.5, 0.5), 1e-9)
    }

    @Test
    fun `rute lintas 180 tidak melompat ke bujur 0`() {
        val pts = listOf(LatLng(-17.0, 179.995), LatLng(-17.0, -179.995))
        val t = RouteRunner.buildTimeline(pts, 40f, 0f)
        assertTrue(t.size > 2)
        assertTrue(t.all { abs(it.lng) > 179.9 })
        for (i in 1 until t.size) {
            val d = RouteRunner.meters(LatLng(t[i - 1].lat, t[i - 1].lng), LatLng(t[i].lat, t[i].lng))
            assertTrue(d < 20.0)
        }
    }
}
