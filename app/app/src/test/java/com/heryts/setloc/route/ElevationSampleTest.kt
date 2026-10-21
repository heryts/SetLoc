package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ElevationSampleTest {
    @Test
    fun `kurang dari dua titik dikembalikan apa adanya`() {
        val empty = emptyList<LatLng>()
        assertSame(empty, ElevationClient.sample(empty, 10))
        val one = listOf(LatLng(1.0, 2.0))
        assertSame(one, ElevationClient.sample(one, 10))
    }

    @Test
    fun `jumlah sampel dijepit ke 2 sampai 100`() {
        val line = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01))
        assertEquals(2, ElevationClient.sample(line, 1).size)
        assertEquals(2, ElevationClient.sample(line, 0).size)
        assertEquals(2, ElevationClient.sample(line, -5).size)
        assertEquals(100, ElevationClient.sample(line, 1000).size)
        assertEquals(7, ElevationClient.sample(line, 7).size)
    }

    @Test
    fun `titik ujung dipertahankan`() {
        val line = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.005), LatLng(0.002, 0.01))
        val s = ElevationClient.sample(line, 9)
        assertEquals(line.first().latitude, s.first().latitude, 1e-9)
        assertEquals(line.first().longitude, s.first().longitude, 1e-9)
        assertEquals(line.last().latitude, s.last().latitude, 1e-6)
        assertEquals(line.last().longitude, s.last().longitude, 1e-6)
    }

    @Test
    fun `garis lurus disampel merata`() {
        val line = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01))
        val s = ElevationClient.sample(line, 3)
        assertEquals(0.005, s[1].longitude, 1e-6)
        assertEquals(0.0, s[1].latitude, 1e-9)
    }

    @Test
    fun `semua titik identik menghasilkan dua titik`() {
        val p = LatLng(-7.7, 110.4)
        val s = ElevationClient.sample(listOf(p, p, p), 10)
        assertEquals(2, s.size)
        assertEquals(p.latitude, s[0].latitude, 1e-12)
        assertEquals(p.longitude, s[1].longitude, 1e-12)
    }

    @Test
    fun `melewati antimeridian tetap di sekitar 180`() {
        val line = listOf(LatLng(0.0, 179.99), LatLng(0.0, -179.99))
        val s = ElevationClient.sample(line, 3)
        assertTrue(abs(abs(s[1].longitude) - 180.0) < 1e-6)
        assertTrue(s.all { it.longitude in -180.0..180.0 })
    }
}
