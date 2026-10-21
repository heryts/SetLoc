package com.heryts.setloc.favourite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FavouriteStoreParseTest {
    @Test
    fun `daftar valid diurutkan menurun berdasarkan id`() {
        val r = FavouriteStore.parse(
            "[{\"id\":1,\"a\":\"A\",\"la\":1.5,\"ln\":2.5},{\"id\":3,\"a\":\"C\",\"la\":3.0,\"ln\":4.0},{\"id\":2,\"a\":\"B\",\"la\":5.0,\"ln\":6.0}]"
        )!!
        assertEquals(listOf(3L, 2L, 1L), r.map { it.id })
        assertEquals("A", r.last().address)
        assertEquals(1.5, r.last().lat!!, 1e-9)
        assertEquals(2.5, r.last().lng!!, 1e-9)
    }

    @Test
    fun `array kosong menghasilkan list kosong`() {
        assertEquals(emptyList<Favourite>(), FavouriteStore.parse("[]"))
    }

    @Test
    fun `field null dan hilang menjadi null`() {
        val r = FavouriteStore.parse("[{\"id\":7,\"a\":null,\"la\":null,\"ln\":null},{\"id\":8}]")!!
        assertEquals(2, r.size)
        r.forEach {
            assertNull(it.address)
            assertNull(it.lat)
            assertNull(it.lng)
        }
    }

    @Test
    fun `elemen rusak dilewati dan sisanya tetap terbaca`() {
        val r = FavouriteStore.parse(
            "[1,\"x\",null,{\"la\":1.0},{\"id\":\"abc\"},{\"id\":2,\"a\":\"ok\",\"la\":1.0,\"ln\":2.0}]"
        )!!
        assertEquals(1, r.size)
        assertEquals(2L, r[0].id)
        assertEquals("ok", r[0].address)
    }

    @Test
    fun `koordinat bukan angka melewati elemen`() {
        val r = FavouriteStore.parse("[{\"id\":1,\"a\":\"x\",\"la\":\"oops\",\"ln\":2.0},{\"id\":2,\"a\":\"y\",\"la\":1.0,\"ln\":2.0}]")!!
        assertEquals(listOf(2L), r.map { it.id })
    }

    @Test
    fun `json setengah rusak mengembalikan null`() {
        assertNull(FavouriteStore.parse(""))
        assertNull(FavouriteStore.parse("garbage"))
        assertNull(FavouriteStore.parse("{}"))
        assertNull(FavouriteStore.parse("[{\"id\":1,\"a\":\"x\""))
        assertNull(FavouriteStore.parse("[{\"id\":}"))
        assertNull(FavouriteStore.parse("[{\"id\":1,\"a\":\"x\",\"la\":1.0,\"ln\":2.0},"))
    }

    @Test
    fun `list valid tidak null`() {
        assertNotNull(FavouriteStore.parse("[{\"id\":1,\"a\":\"x\",\"la\":1.0,\"ln\":2.0}]"))
    }
}
