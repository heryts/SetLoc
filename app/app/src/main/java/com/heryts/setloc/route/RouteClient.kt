package com.heryts.setloc.route

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.maps.model.LatLng
import com.heryts.setloc.R
import com.heryts.setloc.utils.Http
import com.heryts.setloc.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

object RouteClient {
    class RouteException(val resId: Int, vararg val args: Any) : Exception() {
        fun text(context: Context): String = context.getString(resId, *args)
    }

    class Result(val points: List<LatLng>, val durationS: Double)

    private val MODES = arrayOf("WALK", "BICYCLE", "DRIVE", "DRIVE")

    private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val FIELD_MASK = "routes.duration,routes.polyline.encodedPolyline"

    const val MAX_POINTS = 27

    suspend fun fetch(context: Context, stops: List<LatLng>, mode: Int): Result =
        withContext(Dispatchers.IO) {
            if (stops.size < 2) throw RouteException(R.string.route_err_min_points)
            request(context.applicationContext, stops, MODES[mode.coerceIn(0, MODES.size - 1)])
        }

    private suspend fun request(context: Context, stops: List<LatLng>, travelMode: String): Result {
        val key = apiKey(context) ?: throw RouteException(R.string.route_err_no_key)

        val body = JSONObject()
            .put("origin", waypoint(stops.first()))
            .put("destination", waypoint(stops.last()))
            .put("travelMode", travelMode)
            .put("polylineEncoding", "ENCODED_POLYLINE")
        if (travelMode == "DRIVE" && PrefManager.trafficAware) body.put("routingPreference", "TRAFFIC_AWARE")
        if (stops.size > 2) {
            val mid = JSONArray()
            for (i in 1 until stops.size - 1) mid.put(waypoint(stops[i]))
            body.put("intermediates", mid)
        }

        val headers = HashMap<String, String>(6)
        headers["Content-Type"] = "application/json"
        headers["X-Goog-Api-Key"] = key
        headers["X-Goog-FieldMask"] = FIELD_MASK
        headers["X-Android-Package"] = context.packageName
        certSha1(context)?.let { headers["X-Android-Cert"] = it }

        val res = try {
            Http.call(ENDPOINT, body.toString().toByteArray(Charsets.UTF_8), headers, 10_000, 15_000)
        } catch (_: IOException) {
            throw RouteException(R.string.route_err_connect)
        }
        if (!res.ok) throw errorOf(res.code, res.text)

        return try {
            parse(res.text)
        } catch (e: RouteException) {
            throw e
        } catch (e: Exception) {
            throw RouteException(R.string.route_err_process, e.message.orEmpty())
        }
    }

    private fun parse(text: String): Result {
        val route = JSONObject(text).optJSONArray("routes")?.optJSONObject(0)
            ?: throw RouteException(R.string.route_err_not_found)
        val encoded = route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
        if (encoded.isEmpty()) throw RouteException(R.string.route_err_not_found)
        val points = decodePolyline(encoded)
        if (points.size < 2) throw RouteException(R.string.route_err_not_found)
        val seconds = route.optString("duration").removeSuffix("s").toDoubleOrNull() ?: 0.0
        return Result(points, seconds)
    }

    private fun waypoint(p: LatLng) = JSONObject().put(
        "location", JSONObject().put(
            "latLng", JSONObject().put("latitude", p.latitude).put("longitude", p.longitude)
        )
    )

    private fun errorOf(code: Int, text: String): RouteException {
        val msg = try {
            JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
        return if (msg.isNotEmpty()) RouteException(R.string.route_err_api, code, msg)
        else RouteException(R.string.route_err_http, code)
    }

    private var apiKeyCache: String? = null
    private var certCache: String? = null
    private var certLoaded = false

    @Synchronized
    internal fun apiKey(context: Context): String? {
        apiKeyCache?.let { return it }
        val k = try {
            @Suppress("DEPRECATION")
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("com.google.android.geo.API_KEY")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        apiKeyCache = k
        return k
    }

    @Synchronized
    @Suppress("DEPRECATION")
    internal fun certSha1(context: Context): String? {
        if (certLoaded) return certCache
        val c = try {
            val pm = context.packageManager
            val sig = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
            sig?.let {
                MessageDigest.getInstance("SHA-1").digest(it.toByteArray())
                    .joinToString("") { b -> "%02X".format(b) }
            }
        } catch (_: Exception) {
            null
        }
        certCache = c
        certLoaded = true
        return c
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val out = ArrayList<LatLng>(encoded.length / 4)
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
