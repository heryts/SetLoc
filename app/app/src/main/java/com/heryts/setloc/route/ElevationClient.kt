package com.heryts.setloc.route

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object ElevationClient {

    private const val GOOGLE = "https://maps.googleapis.com/maps/api/elevation/json"
    private const val OPEN_METEO = "https://api.open-meteo.com/v1/elevation"
    private const val MAX_SAMPLES = 100

    suspend fun fetch(context: Context, points: List<LatLng>): FloatArray? =
        withContext(Dispatchers.IO) {
            val pts = if (points.size > MAX_SAMPLES) points.subList(0, MAX_SAMPLES) else points
            if (pts.isEmpty()) return@withContext null
            google(context.applicationContext, pts) ?: openMeteo(pts)
        }

    fun sample(points: List<LatLng>, n: Int): List<LatLng> {
        if (points.size < 2) return points
        val cnt = n.coerceIn(2, MAX_SAMPLES)
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + RouteRunner.meters(points[i - 1], points[i])
        val total = cum.last()
        if (total <= 0.0) return listOf(points.first(), points.last())
        val out = ArrayList<LatLng>(cnt)
        var seg = 1
        for (k in 0 until cnt) {
            val d = total * k / (cnt - 1)
            while (seg < cum.size - 1 && cum[seg] < d) seg++
            val len = cum[seg] - cum[seg - 1]
            val f = if (len <= 0.0) 1.0 else ((d - cum[seg - 1]) / len).coerceIn(0.0, 1.0)
            val a = points[seg - 1]
            val b = points[seg]
            out.add(LatLng(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f))
        }
        return out
    }

    private fun google(context: Context, pts: List<LatLng>): FloatArray? = try {
        val key = RouteClient.apiKey(context)
        if (key == null) null else {
            val url = "$GOOGLE?locations=enc:${encodePolyline(pts)}&key=$key"
            val text = get(url) { conn ->
                conn.setRequestProperty("X-Android-Package", context.packageName)
                RouteClient.certSha1(context)?.let { conn.setRequestProperty("X-Android-Cert", it) }
            }
            val json = JSONObject(text)
            val res = json.optJSONArray("results")
            if (json.optString("status") != "OK" || res == null || res.length() != pts.size) null
            else FloatArray(pts.size) { res.getJSONObject(it).getDouble("elevation").toFloat() }
        }
    } catch (_: Exception) {
        null
    }

    private fun openMeteo(pts: List<LatLng>): FloatArray? = try {
        val lat = pts.joinToString(",") { String.format(Locale.US, "%.5f", it.latitude) }
        val lng = pts.joinToString(",") { String.format(Locale.US, "%.5f", it.longitude) }
        val arr = JSONObject(get("$OPEN_METEO?latitude=$lat&longitude=$lng") { }).optJSONArray("elevation")
        if (arr == null || arr.length() != pts.size) null
        else FloatArray(pts.size) { arr.getDouble(it).toFloat() }
    } catch (_: Exception) {
        null
    }

    private fun get(url: String, headers: (HttpURLConnection) -> Unit): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            headers(conn)
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun encodePolyline(points: List<LatLng>): String {
        val sb = StringBuilder()
        var pLat = 0
        var pLng = 0
        for (p in points) {
            val la = Math.round(p.latitude * 1e5).toInt()
            val ln = Math.round(p.longitude * 1e5).toInt()
            encodeValue(la - pLat, sb)
            encodeValue(ln - pLng, sb)
            pLat = la
            pLng = ln
        }
        return sb.toString()
    }

    private fun encodeValue(v: Int, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) {
            sb.append((((x and 0x1f) or 0x20) + 63).toChar())
            x = x ushr 5
        }
        sb.append((x + 63).toChar())
    }
}
