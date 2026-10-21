package com.heryts.setloc.favourite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedRouteStoreParseTest {
    private fun route(id: Long, name: String = "r$id", mode: Int = 0, p: String = "[[1.0,2.0],[3.0,4.0]]") =
        "{\"id\":$id,\"n\":\"$name\",\"m\":$mode,\"p\":$p}"

    @Test
    fun `rute valid diurutkan menurun berdasarkan id`() {
        val r = SavedRouteStore.parse("[" + route(1) + "," + route(5, mode = 2) + "," + route(3) + "]")!!
        assertEquals(listOf(5L, 3L, 1L), r.map { it.id })
        assertEquals(2, r[0].mode)
        assertEquals("r5", r[0].name)
        assertEquals(listOf(1.0 to 2.0, 3.0 to 4.0), r[0].points)
    }

    @Test
    fun `array kosong menghasilkan list kosong`() {
        assertEquals(emptyList<SavedRoute>(), SavedRouteStore.parse("[]"))
    }

    @Test
    fun `rute dengan kurang dari dua titik dibuang`() {
        val r = SavedRouteStore.parse("[" + route(1, p = "[]") + "," + route(2, p = "[[1.0,2.0]]") + "," + route(3) + "]")!!
        assertEquals(listOf(3L), r.map { it.id })
    }

    @Test
    fun `titik dengan satu koordinat membuang rute itu saja`() {
        val r = SavedRouteStore.parse("[" + route(1, p = "[[1.0,2.0],[3.0]]") + "," + route(2) + "]")!!
        assertEquals(listOf(2L), r.map { it.id })
    }

    @Test
    fun `titik bukan array atau bukan angka membuang rute itu saja`() {
        val r = SavedRouteStore.parse("[" + route(1, p = "[1,2]") + "," + route(2, p = "[[\"a\",\"b\"],[1.0,2.0]]") + "," + route(3) + "]")!!
        assertEquals(listOf(3L), r.map { it.id })
    }

    @Test
    fun `field wajib hilang membuang rute itu saja`() {
        val r = SavedRouteStore.parse(
            "[{\"n\":\"x\",\"m\":0,\"p\":[[1.0,2.0],[3.0,4.0]]},{\"id\":2,\"m\":0,\"p\":[[1.0,2.0],[3.0,4.0]]}," +
                "{\"id\":3,\"n\":\"x\",\"p\":[[1.0,2.0],[3.0,4.0]]},{\"id\":4,\"n\":\"x\",\"m\":0}," + route(9) + "]"
        )!!
        assertEquals(listOf(9L), r.map { it.id })
    }

    @Test
    fun `elemen bukan objek dilewati`() {
        val r = SavedRouteStore.parse("[1,null,\"x\"," + route(4) + "]")!!
        assertEquals(listOf(4L), r.map { it.id })
    }

    @Test
    fun `json setengah rusak mengembalikan null`() {
        assertNull(SavedRouteStore.parse(""))
        assertNull(SavedRouteStore.parse("garbage"))
        assertNull(SavedRouteStore.parse("{}"))
        assertNull(SavedRouteStore.parse("[{\"id\":1,\"n\":\"x\",\"m\":0,\"p\":[[1.0,2.0]"))
        assertNull(SavedRouteStore.parse("[" + route(1) + ","))
    }
}
