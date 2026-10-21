package com.heryts.setloc.route

import com.heryts.setloc.R
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouteClientParseTest {
    private val poly = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
    private val single = "_p~iF~ps|U"

    private fun body(duration: String?, encoded: String?): String {
        val d = if (duration == null) "" else "\"duration\":\"$duration\","
        val p = if (encoded == null) "" else "\"polyline\":{\"encodedPolyline\":\"$encoded\"}"
        return "{\"routes\":[{$d$p}]}"
    }

    private fun notFound(text: String) {
        val e = assertThrows(RouteClient.RouteException::class.java) { RouteClient.parse(text) }
        assertEquals(R.string.route_err_not_found, e.resId)
    }

    @Test
    fun `respons valid diparse`() {
        val r = RouteClient.parse(body("120s", poly))
        assertEquals(3, r.points.size)
        assertEquals(120.0, r.durationS, 1e-9)
        assertEquals(38.5, r.points[0].latitude, 1e-9)
    }

    @Test
    fun `durasi pecahan diparse`() {
        assertEquals(123.5, RouteClient.parse(body("123.5s", poly)).durationS, 1e-9)
    }

    @Test
    fun `durasi rusak atau hilang menjadi nol`() {
        assertEquals(0.0, RouteClient.parse(body("abc", poly)).durationS, 1e-9)
        assertEquals(0.0, RouteClient.parse(body(null, poly)).durationS, 1e-9)
        assertEquals(0.0, RouteClient.parse(body("", poly)).durationS, 1e-9)
    }

    @Test
    fun `tanpa routes dianggap rute tidak ditemukan`() {
        notFound("{}")
        notFound("{\"routes\":[]}")
        notFound("{\"routes\":null}")
        notFound("{\"routes\":[null]}")
    }

    @Test
    fun `polyline hilang kosong atau terpotong dianggap tidak ditemukan`() {
        notFound(body("10s", null))
        notFound(body("10s", ""))
        notFound(body("10s", "_p~iF~ps|U_"))
        notFound(body("10s", "_p~iF"))
    }

    @Test
    fun `polyline satu titik dianggap tidak ditemukan`() {
        notFound(body("10s", single))
    }

    @Test
    fun `json rusak melempar JSONException`() {
        assertThrows(JSONException::class.java) { RouteClient.parse("") }
        assertThrows(JSONException::class.java) { RouteClient.parse("[]") }
        assertThrows(JSONException::class.java) { RouteClient.parse("{\"routes\":[{") }
        assertThrows(JSONException::class.java) { RouteClient.parse("<html>502</html>") }
    }
}
