package com.heryts.setloc.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {
    @Test
    fun `contoh polyline Google didecode benar`() {
        val p = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, p.size)
        assertEquals(38.5, p[0].latitude, 1e-9)
        assertEquals(-120.2, p[0].longitude, 1e-9)
        assertEquals(40.7, p[1].latitude, 1e-9)
        assertEquals(-120.95, p[1].longitude, 1e-9)
        assertEquals(43.252, p[2].latitude, 1e-9)
        assertEquals(-126.453, p[2].longitude, 1e-9)
    }

    @Test
    fun `string kosong menghasilkan list kosong`() {
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test
    fun `string terpotong tidak melempar exception`() {
        assertEquals(1, Polyline.decode("_p~iF~ps|U_").size)
        assertTrue(Polyline.decode("_p~iF").isEmpty())
        assertTrue(Polyline.decode("?").isEmpty())
        assertEquals(1, Polyline.decode("??").size)
        assertEquals(2, Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@".dropLast(1)).size)
    }

    @Test
    fun `isComplete mengenali string lengkap dan terpotong`() {
        val full = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        assertTrue(Polyline.isComplete(full))
        assertTrue(Polyline.isComplete("_p~iF~ps|U"))
        assertFalse(Polyline.isComplete(full.dropLast(1)))
        assertFalse(Polyline.isComplete("_p~iF~ps|U_"))
        assertFalse(Polyline.isComplete("_p~iF"))
        assertFalse(Polyline.isComplete(""))
    }
}
