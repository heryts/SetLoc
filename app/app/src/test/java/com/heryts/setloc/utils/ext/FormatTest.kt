package com.heryts.setloc.utils.ext

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun `durasi dibulatkan ke menit tanpa melompat ke 60 min`() {
        assertEquals("45 s", formatDuration(45.0))
        assertEquals("1 min", formatDuration(60.0))
        assertEquals("59 min", formatDuration(3569.0))
        assertEquals("1 h 0 min", formatDuration(3570.0))
        assertEquals("1 h 0 min", formatDuration(3599.0))
        assertEquals("1 h 0 min", formatDuration(3600.0))
        assertEquals("1 h 1 min", formatDuration(3660.0))
        assertEquals("1 h 11 min", formatDuration(4250.0))
        assertEquals("2 h 0 min", formatDuration(7170.0))
        assertEquals("2 h 30 min", formatDuration(9000.0))
    }

    @Test
    fun `jarak meter dan kilometer`() {
        assertEquals("999 m", formatDistance(999.7))
        assertEquals("1.0 km", formatDistance(1000.0))
        assertEquals("12.3 km", formatDistance(12345.0))
    }
}
