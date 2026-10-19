package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TimelineTest {
    @Before
    fun flatTerrain() {
        RouteRunner.elevations = floatArrayOf(0f, 0f)
    }

    private fun offset(p: LatLng, meters: Double, bearingDeg: Double) = LatLng(
        p.latitude + meters * cos(Math.toRadians(bearingDeg)) / 111320.0,
        p.longitude + meters * sin(Math.toRadians(bearingDeg)) / (111320.0 * cos(Math.toRadians(p.latitude)))
    )

    private val origin = LatLng(-7.7, 110.4)

    @Test
    fun `mulai dan berhenti dari kecepatan nol`() {
        val t = RouteRunner.buildTimeline(listOf(origin, offset(origin, 1000.0, 0.0)), 50f, -1f)
        assertEquals(0f, t.first().spd, 0f)
        assertEquals(0f, t.last().spd, 0f)
        assertEquals(100, t.last().pct)
    }

    @Test
    fun `percepatan dan pengereman tidak melebihi batas wajar`() {
        val a = offset(origin, 1000.0, 0.0)
        val b = offset(a, 1000.0, 90.0)
        val t = RouteRunner.buildTimeline(listOf(origin, a, b), 50f, -1f)
        for (i in 1 until t.size) assertTrue(abs(t[i].spd - t[i - 1].spd) / 0.5f <= 3.2f)
    }

    @Test
    fun `tikungan tajam memperlambat kendaraan`() {
        val a = offset(origin, 500.0, 0.0)
        val b = offset(a, 500.0, 170.0)
        val t = RouteRunner.buildTimeline(listOf(origin, a, b), 50f, 0f)
        val atCorner = t.minByOrNull { RouteRunner.meters(LatLng(it.lat, it.lng), a) }!!
        assertTrue(atCorner.spd < 6f)
    }

    @Test
    fun `jalan kaki tidak melambat di tikungan dan berakhir di titik tujuan`() {
        val a = offset(origin, 200.0, 0.0)
        val b = offset(a, 200.0, 90.0)
        val pts = listOf(origin, a, b)
        val t = RouteRunner.buildTimeline(pts, 5f, 0f)
        assertTrue(t.maxOf { it.spd } < 1.6f)
        assertTrue(RouteRunner.meters(LatLng(t.last().lat, t.last().lng), b) < 1.0)
    }

    @Test
    fun `titik awal dan akhir sama menghasilkan dua tick`() {
        assertEquals(2, RouteRunner.buildTimeline(listOf(origin, origin), 5f, -1f).size)
    }
}
