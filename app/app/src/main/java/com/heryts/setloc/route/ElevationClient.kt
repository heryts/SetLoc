package com.heryts.setloc.route

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.heryts.setloc.utils.Http
import com.heryts.setloc.utils.NetNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ElevationClient {
    private const val MAX_SAMPLES = 100

    suspend fun fetch(context: Context, points: List<LatLng>): FloatArray? =
        withContext(Dispatchers.IO) {
            val pts = if (points.size > MAX_SAMPLES) points.subList(0, MAX_SAMPLES) else points
            if (pts.isEmpty()) return@withContext null
            val key = RouteNative.polylineEncode(pts) ?: return@withContext null
            NetNative.elevCacheGet(key)?.let { return@withContext it }
            val res = google(context.applicationContext, pts, key) ?: openMeteo(pts)
            if (res != null) NetNative.elevCachePut(key, res)
            res
        }

    fun sample(points: List<LatLng>, n: Int): List<LatLng> = RouteNative.sample(points, n, MAX_SAMPLES)

    private inline fun <T> attempt(block: () -> T?): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }

    private suspend fun google(context: Context, pts: List<LatLng>, encoded: String): FloatArray? = attempt {
        val key = RouteClient.apiKey(context) ?: return@attempt null
        val headers = HashMap<String, String>(2)
        headers["X-Android-Package"] = context.packageName
        RouteClient.certSha1(context)?.let { headers["X-Android-Cert"] = it }
        val url = NetNative.elevationUrlGoogle(encoded, key) ?: return@attempt null
        val res = Http.call(url, headers = headers, retries = 0)
        if (!res.ok) return@attempt null
        NetNative.elevation(res.text, pts.size, true)
    }

    private suspend fun openMeteo(pts: List<LatLng>): FloatArray? = attempt {
        val url = NetNative.elevationUrlOpenMeteo(pts) ?: return@attempt null
        val res = Http.call(url)
        if (!res.ok) return@attempt null
        NetNative.elevation(res.text, pts.size, false)
    }
}
