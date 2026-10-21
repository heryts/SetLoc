package com.heryts.setloc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseCoordsTest {
    @Test
    fun `koordinat normal dengan dan tanpa spasi`() {
        assertEquals(1.5 to 2.5, parseCoords("1.5, 2.5"))
        assertEquals(-7.7 to 110.4, parseCoords("-7.7,110.4"))
        assertEquals(10.0 to -20.0, parseCoords(" 10 ,  -20 "))
    }

    @Test
    fun `batas tepat diterima`() {
        assertEquals(90.0 to 180.0, parseCoords("90,180"))
        assertEquals(-90.0 to -180.0, parseCoords("-90,-180"))
    }

    @Test
    fun `di luar rentang ditolak`() {
        assertNull(parseCoords("90.1,0"))
        assertNull(parseCoords("-90.1,0"))
        assertNull(parseCoords("0,180.1"))
        assertNull(parseCoords("0,-180.1"))
        assertNull(parseCoords("91,181"))
    }

    @Test
    fun `bukan angka ditolak`() {
        assertNull(parseCoords("abc,def"))
        assertNull(parseCoords("1,def"))
        assertNull(parseCoords("abc,1"))
        assertNull(parseCoords("NaN,1"))
        assertNull(parseCoords("1,Infinity"))
        assertNull(parseCoords(",5"))
        assertNull(parseCoords("5,"))
    }

    @Test
    fun `jumlah bagian salah tidak melempar`() {
        assertNull(parseCoords(""))
        assertNull(parseCoords("1"))
        assertNull(parseCoords("1.5"))
        assertNull(parseCoords("1,2,3"))
        assertNull(parseCoords(","))
    }
}
