package com.heryts.setloc.route

import com.heryts.setloc.R
import com.heryts.setloc.utils.NetNative
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class RouteClientParseTest {
    private var restore: () -> Unit = {}
    private val oldRoute = NetNative.routeFn

    @Before
    fun fakeNative() {
        restore = FakePolyline.install()
    }

    @After
    fun restoreNative() {
        restore()
        NetNative.routeFn = oldRoute
    }

    private val poly = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
    private val single = "_p~iF~ps|U"

    private fun found(duration: Double, encoded: String): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use {
            it.writeByte(1)
            it.writeDouble(duration)
            val b = encoded.toByteArray(Charsets.UTF_8)
            it.writeInt(b.size)
            it.write(b)
        }
        return bos.toByteArray()
    }

    private fun parse(blob: ByteArray?): RouteClient.Result {
        NetNative.routeFn = { blob }
        return RouteClient.parse("x")
    }

    private fun notFound(blob: ByteArray?) {
        val e = assertThrows(RouteClient.RouteException::class.java) { parse(blob) }
        assertEquals(R.string.route_err_not_found, e.resId)
    }

    @Test
    fun `respons valid diparse`() {
        val r = parse(found(120.0, poly))
        assertEquals(3, r.points.size)
        assertEquals(120.0, r.durationS, 1e-9)
        assertEquals(38.5, r.points[0].latitude, 1e-9)
    }

    @Test
    fun `durasi pecahan dan nol dipertahankan`() {
        assertEquals(123.5, parse(found(123.5, poly)).durationS, 1e-9)
        assertEquals(0.0, parse(found(0.0, poly)).durationS, 1e-9)
    }

    @Test
    fun `rute tidak ada dianggap tidak ditemukan`() {
        notFound(byteArrayOf(0))
    }

    @Test
    fun `polyline kosong atau terpotong dianggap tidak ditemukan`() {
        notFound(found(10.0, ""))
        notFound(found(10.0, "_p~iF~ps|U_"))
        notFound(found(10.0, "_p~iF"))
    }

    @Test
    fun `polyline satu titik dianggap tidak ditemukan`() {
        notFound(found(10.0, single))
    }

    @Test
    fun `json rusak melempar MalformedResponse`() {
        assertThrows(RouteClient.MalformedResponse::class.java) { parse(null) }
    }
}
