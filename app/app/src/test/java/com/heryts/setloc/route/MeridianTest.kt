package com.heryts.setloc.route

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class MeridianTest {
    @Test
    fun `interpolasi bujur melewati 180 lewat jalur terpendek`() {
        assertEquals(180.0, abs(RouteRunner.lerpLng(179.9, -179.9, 0.5)), 1e-9)
        assertEquals(-179.95, RouteRunner.lerpLng(-179.9, 179.9, 0.25), 1e-9)
        assertEquals(110.45, RouteRunner.lerpLng(110.4, 110.5, 0.5), 1e-9)
    }
}
