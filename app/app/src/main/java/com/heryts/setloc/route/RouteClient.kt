package com.heryts.setloc.route

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.maps.model.LatLng
import com.heryts.setloc.R
import com.heryts.setloc.utils.Http
import com.heryts.setloc.utils.NetNative
import com.heryts.setloc.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

object RouteClient {
    class RouteException(val resId: Int, vararg val args: Any) : Exception() {
        fun text(context: Context): String = context.getString(resId, *args)
    }

    class Result(val points: List<LatLng>, val durationS: Double)

    private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val FIELD_MASK = "routes.duration,routes.polyline.encodedPolyline"

    const val MAX_POINTS = 27

    suspend fun fetch(context: Context, stops: List<LatLng>, mode: Int): Result =
        withContext(Dispatchers.IO) {
            if (stops.size < 2) throw RouteException(R.string.route_err_min_points)
            request(context.applicationContext, stops, mode)
        }

    private suspend fun request(context: Context, stops: List<LatLng>, mode: Int): Result {
        val key = apiKey(context) ?: throw RouteException(R.string.route_err_no_key)

        val body = try {
            NetNative.routeBody(stops, mode, PrefManager.trafficAware)
        } catch (e: IOException) {
            throw RouteException(R.string.route_err_process, e.message.orEmpty())
        } ?: throw RouteException(R.string.route_err_process, "coordinates")

        val headers = HashMap<String, String>(6)
        headers["Content-Type"] = "application/json"
        headers["X-Goog-Api-Key"] = key
        headers["X-Goog-FieldMask"] = FIELD_MASK
        headers["X-Android-Package"] = context.packageName
        certSha1(context)?.let { headers["X-Android-Cert"] = it }

        val res = try {
            Http.call(ENDPOINT, body.toByteArray(Charsets.UTF_8), headers, 10_000, 15_000)
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

    internal class MalformedResponse : Exception("JSON")

    internal fun parse(text: String): Result {
        val body = NetNative.route(text) ?: throw MalformedResponse()
        if (!body.found || !Polyline.isComplete(body.encoded)) throw RouteException(R.string.route_err_not_found)
        val points = Polyline.decode(body.encoded)
        if (points.size < 2) throw RouteException(R.string.route_err_not_found)
        return Result(points, body.durationS)
    }

    private fun errorOf(code: Int, text: String): RouteException {
        val msg = NetNative.errorMessage(text)
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
                ?.takeIf { it.isNotBlank() && it != "DEFAULT_API_KEY" }
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
}
