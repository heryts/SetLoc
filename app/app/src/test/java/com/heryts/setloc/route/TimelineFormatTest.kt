package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringWriter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TimelineFormatTest {
    @Before
    fun flatTerrain() {
        RouteRunner.elevations = floatArrayOf(0f, 0f)
    }

    private fun haversine(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * 6371000.0 * asin(sqrt(h))
    }

    @Test
    fun `setiap baris timeline selebar TIMELINE_LINE_WIDTH termasuk nilai ekstrem`() {
        assertEquals(48, RouteRunner.TIMELINE_LINE_WIDTH)
        RouteRunner.elevations = floatArrayOf(-3f, 1234.5f, 8848f)
        val routes = listOf(
            listOf(LatLng(-89.9, -179.5), LatLng(-89.8, -179.4)),
            listOf(LatLng(60.1, 170.2), LatLng(60.2, 170.4), LatLng(60.25, 171.0))
        )
        for (pts in routes) {
            val ticks = RouteRunner.buildTimeline(pts, 40f, -1f)
            val w = StringWriter()
            val chars = RouteRunner.writeTimeline(ticks, w)
            assertEquals(ticks.size.toLong() * RouteRunner.TIMELINE_LINE_WIDTH, chars)
            val lines = w.toString().split("\n").dropLast(1)
            assertEquals(ticks.size, lines.size)
            assertTrue(lines.all { it.length == RouteRunner.TIMELINE_LINE_WIDTH - 1 })
        }
    }

    @Test
    fun `ketinggian diinterpolasi sepanjang rute`() {
        RouteRunner.elevations = floatArrayOf(0f, 100f, 0f)
        val a = LatLng(-7.7, 110.4)
        val b = LatLng(-7.7 + 1000.0 / 111320.0, 110.4)
        val t = RouteRunner.buildTimeline(listOf(a, b), 36f, 0f)
        assertEquals(0f, t.first().alt, 0.01f)
        assertEquals(0f, t.last().alt, 0.01f)
        assertEquals(50f, t.first { it.pct == 25 }.alt, 3f)
        assertEquals(100f, t.first { it.pct == 50 }.alt, 4f)
        assertEquals(50f, t.first { it.pct == 75 }.alt, 3f)
    }

    @Test
    fun `persen progres tidak turun dan berakhir di 100`() {
        val a = LatLng(-7.7, 110.4)
        val t = RouteRunner.buildTimeline(listOf(a, LatLng(-7.69, 110.41), LatLng(-7.68, 110.40)), 30f, -1f)
        for (i in 1 until t.size) assertTrue(t[i].pct >= t[i - 1].pct)
        assertTrue(t.all { it.pct in 0..100 })
        assertEquals(100, t.last().pct)
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
