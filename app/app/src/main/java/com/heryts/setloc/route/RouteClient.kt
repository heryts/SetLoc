package com.heryts.setloc.route

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Ambil rute jalan dari Google Routes API (pengganti Directions API legacy).
 * Pakai API key yang sama dengan Maps SDK (meta-data com.google.android.geo.API_KEY),
 * jadi di Google Cloud Console "Routes API" harus diaktifkan & diizinkan di key tsb.
 */
object RouteClient {

    class RouteException(message: String) : Exception(message)

    class Result(val points: List<LatLng>, val distanceM: Double)

    /** Mode perjalanan: indeks sama dengan PrefManager.routeMode. */
    private val MODES = arrayOf("WALK", "BICYCLE", "TWO_WHEELER", "DRIVE")

    private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"

    /** Routes API: maksimal 25 titik perantara => 27 titik total (awal + perantara + tujuan). */
    const val MAX_POINTS = 27

    /** [stops] = titik awal, titik-titik perantara (urut), titik tujuan. Minimal 2. */
    suspend fun fetch(context: Context, stops: List<LatLng>, mode: Int): Result =
        withContext(Dispatchers.IO) {
            if (stops.size < 2) throw RouteException("Minimal dua titik")
            val appCtx = context.applicationContext
            val wanted = mode.coerceIn(0, MODES.size - 1)
            try {
                request(appCtx, stops, MODES[wanted])
            } catch (e: RouteException) {
                // TWO_WHEELER tidak didukung di semua wilayah -> coba lagi sebagai mobil
                if (MODES[wanted] == "TWO_WHEELER") request(appCtx, stops, "DRIVE") else throw e
            }
        }

    private fun request(context: Context, stops: List<LatLng>, travelMode: String): Result {
        val key = apiKey(context) ?: throw RouteException("API key Google Maps tidak ditemukan")

        val body = JSONObject()
            .put("origin", waypoint(stops.first()))
            .put("destination", waypoint(stops.last()))
            .put("travelMode", travelMode)
            .put("polylineEncoding", "ENCODED_POLYLINE")
        if (stops.size > 2) {
            val mid = JSONArray()
            for (i in 1 until stops.size - 1) mid.put(waypoint(stops[i]))
            body.put("intermediates", mid)
        }
        val bodyText = body.toString()

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Goog-Api-Key", key)
            conn.setRequestProperty(
                "X-Goog-FieldMask",
                "routes.distanceMeters,routes.polyline.encodedPolyline"
            )
            // Supaya key yang dibatasi "Android apps" tetap diterima
            conn.setRequestProperty("X-Android-Package", context.packageName)
            certSha1(context)?.let { conn.setRequestProperty("X-Android-Cert", it) }

            conn.outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) throw RouteException(errorMessage(code, text))

            val routes = JSONObject(text).optJSONArray("routes")
            val route = routes?.optJSONObject(0) ?: throw RouteException("Rute tidak ditemukan")
            val encoded = route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
            if (encoded.isEmpty()) throw RouteException("Rute tidak ditemukan")
            val points = decodePolyline(encoded)
            if (points.size < 2) throw RouteException("Rute tidak ditemukan")
            return Result(points, route.optDouble("distanceMeters", 0.0))
        } catch (e: RouteException) {
            throw e
        } catch (e: java.io.IOException) {
            throw RouteException("Gagal terhubung ke server rute")
        } catch (e: Exception) {
            throw RouteException("Gagal memproses rute: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun waypoint(p: LatLng) = JSONObject().put(
        "location", JSONObject().put(
            "latLng", JSONObject().put("latitude", p.latitude).put("longitude", p.longitude)
        )
    )

    private fun errorMessage(code: Int, text: String): String {
        val msg = try {
            JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
        return if (msg.isNotEmpty()) "Rute gagal ($code): $msg" else "Rute gagal (HTTP $code)"
    }

    internal fun apiKey(context: Context): String? = try {
        @Suppress("DEPRECATION")
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.google.android.geo.API_KEY")
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** SHA-1 sertifikat penanda tangan APK, hex huruf besar tanpa pemisah. */
    @Suppress("DEPRECATION")
    internal fun certSha1(context: Context): String? = try {
        val pm = context.packageManager
        val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo.apkContentsSigners.firstOrNull()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures.firstOrNull()
        }
        sig?.let {
            MessageDigest.getInstance("SHA-1").digest(it.toByteArray())
                .joinToString("") { b -> "%02X".format(b) }
        }
    } catch (_: Exception) {
        null
    }

    /** Decoder Google encoded polyline (presisi 1e5). */
    fun decodePolyline(encoded: String): List<LatLng> {
        val out = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            out.add(LatLng(lat / 1e5, lng / 1e5))
        }
        return out
    }
}
