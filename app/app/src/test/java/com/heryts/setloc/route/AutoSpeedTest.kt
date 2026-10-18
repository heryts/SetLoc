package com.heryts.setloc.route

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSpeedTest {
    @Test
    fun `tanpa durasi memakai kecepatan default per mode`() {
        assertEquals(5f, AutoSpeed.kmh(0, 1000.0, null), 0f)
        assertEquals(15f, AutoSpeed.kmh(1, 1000.0, null), 0f)
        assertEquals(40f, AutoSpeed.kmh(2, 1000.0, null), 0f)
        assertEquals(50f, AutoSpeed.kmh(3, 1000.0, null), 0f)
    }

    @Test
    fun `mode di luar rentang dijepit`() {
        assertEquals(5f, AutoSpeed.kmh(-1, 1000.0, null), 0f)
        assertEquals(50f, AutoSpeed.kmh(9, 1000.0, null), 0f)
    }

    @Test
    fun `durasi atau jarak terlalu kecil memakai default`() {
        assertEquals(50f, AutoSpeed.kmh(3, 5000.0, 30.0), 0f)
        assertEquals(50f, AutoSpeed.kmh(3, 100.0, 600.0), 0f)
    }

    @Test
    fun `kecepatan dihitung dari jarak dan durasi`() {
        assertEquals(60f, AutoSpeed.kmh(3, 10000.0, 600.0), 0f)
        assertEquals(22.2f, AutoSpeed.kmh(3, 1234.0, 200.0), 0f)
    }

    @Test
    fun `hasil dijepit ke batas mode`() {
        assertEquals(7f, AutoSpeed.kmh(0, 5000.0, 100.0), 0f)
        assertEquals(10f, AutoSpeed.kmh(3, 200.0, 100.0), 0f)
        assertEquals(110f, AutoSpeed.kmh(3, 50000.0, 600.0), 0f)
    }
}
