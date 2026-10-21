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
    fun `kecepatan sangat rendah tidak melampaui kecepatan yang diminta`() {
        val t = RouteRunner.buildTimeline(listOf(origin, offset(origin, 30.0, 0.0)), 0.5f, 0f)
        assertTrue(t.maxOf { it.spd } <= 0.5f / 3.6f + 1e-4f)
        assertEquals(0f, t.last().spd, 0f)
    }

    @Test
    fun `titik awal dan akhir sama menghasilkan dua tick`() {
        assertEquals(2, RouteRunner.buildTimeline(listOf(origin, origin), 5f, -1f).size)
    }

    @Test
    fun `pengereman tidak melebihi batas pada banyak rute`() {
        val a = offset(origin, 400.0, 0.0)
        val b = offset(a, 300.0, 100.0)
        val c = offset(b, 150.0, 200.0)
        val pts = listOf(origin, a, b, c)
        repeat(200) {
            val t = RouteRunner.buildTimeline(pts, 110f, -1f)
            for (i in 1 until t.size) assertTrue(abs(t[i].spd - t[i - 1].spd) / 0.5f <= 2.6f)
        }
    }

    @Test
    fun `polyline padat lurus dengan derau kuantisasi tetap mendekati kecepatan target`() {
        val pts = ArrayList<LatLng>()
        pts.add(origin)
        for (i in 1..1500) {
            val q = offset(origin, 3.0 * i, 35.0)
            pts.add(LatLng(Math.round(q.latitude * 1e5) / 1e5, Math.round(q.longitude * 1e5) / 1e5))
        }
        val t = RouteRunner.buildTimeline(pts, 50f, 0f)
        val mid = t.subList(t.size / 4, 3 * t.size / 4)
        val mean = mid.sumOf { it.spd.toDouble() } / mid.size
        assertTrue(mean >= 0.97 * 50.0 / 3.6)
    }

    @Test
    fun `tikungan siku pada polyline padat tetap memperlambat`() {
        val pts = ArrayList<LatLng>()
        var p = origin
        pts.add(p)
        repeat(100) { p = offset(p, 3.0, 0.0); pts.add(p) }
        val corner = p
        repeat(100) { p = offset(p, 3.0, 90.0); pts.add(p) }
        val t = RouteRunner.buildTimeline(pts, 50f, 0f)
        val atCorner = t.minByOrNull { RouteRunner.meters(LatLng(it.lat, it.lng), corner) }!!
        assertTrue(atCorner.spd < 11f)
    }
}
