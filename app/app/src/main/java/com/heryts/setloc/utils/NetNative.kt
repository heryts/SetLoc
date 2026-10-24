package com.heryts.setloc.utils

import com.google.android.gms.maps.model.LatLng
import com.heryts.setloc.route.RouteNative
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

internal object NetNative {
    const val PHOTON = 0
    const val NOMINATIM = 1

    class RouteBody(val found: Boolean, val durationS: Double, val encoded: String)

    class Place(val label: String, val lat: Double, val lon: Double)

    sealed class Query {
        class Text(val text: String) : Query()
        class Found(val places: List<Place>) : Query()
    }

    const val NO_SOURCE = -1

    @JvmStatic
    private external fun nativeRoute(raw: String): ByteArray?

    @JvmStatic
    private external fun nativeErrorMessage(raw: String): String?

    @JvmStatic
    private external fun nativeElevation(raw: String, n: Int, google: Boolean): FloatArray?

    @JvmStatic
    private external fun nativePlaces(kind: Int, raw: String, fallback: String): ByteArray?

    @JvmStatic
    private external fun nativeRouteBody(stops: DoubleArray, mode: Int, traffic: Boolean): String?

    @JvmStatic
    private external fun nativeElevationUrlGoogle(encoded: String, key: String): String?

    @JvmStatic
    private external fun nativeElevationUrlOpenMeteo(pts: DoubleArray): String?

    @JvmStatic
    private external fun nativeElevCacheGet(key: String): FloatArray?

    @JvmStatic
    private external fun nativeElevCachePut(key: String, values: FloatArray)

    @JvmStatic
    private external fun nativeQuery(input: String): ByteArray?

    @JvmStatic
    private external fun nativeSearchUrl(kind: Int, text: String): String?

    @JvmStatic
    private external fun nativeIsCoords(text: String): Boolean

    @JvmStatic
    private external fun nativeNextSource(kind: Int): Int

    @JvmStatic
    private external fun nativeFormatDistance(m: Double): String?

    @JvmStatic
    private external fun nativeFormatDuration(sec: Double): String?

    @JvmStatic
    private external fun nativeAddress(line: String?, lat: Double, lng: Double): String?

    @Volatile internal var routeFn: (String) -> ByteArray? = { guard { nativeRoute(it) } }
    @Volatile internal var errorFn: (String) -> String? = { guard { nativeErrorMessage(it) } }

    private fun <T> guard(block: () -> T?): T? {
        if (!RouteNative.ready) throw IOException("routecore")
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            throw IOException("routecore")
        }
    }

    private fun DataInputStream.text(): String {
        val b = ByteArray(readInt())
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    fun route(raw: String): RouteBody? {
        val blob = routeFn(raw) ?: return null
        return DataInputStream(ByteArrayInputStream(blob)).use { r ->
            if (r.readUnsignedByte() == 0) RouteBody(false, 0.0, "")
            else RouteBody(true, r.readDouble(), r.text())
        }
    }

    fun errorMessage(raw: String): String = try {
        errorFn(raw).orEmpty()
    } catch (_: IOException) {
        ""
    }

    fun elevation(raw: String, n: Int, google: Boolean): FloatArray? = guard { nativeElevation(raw, n, google) }

    fun places(kind: Int, raw: String, fallback: String): List<Place>? {
        val blob = guard { nativePlaces(kind, raw, fallback) } ?: return null
        return DataInputStream(ByteArrayInputStream(blob)).use { it.places() }
    }

    private fun DataInputStream.places(): List<Place> = List(readInt()) {
        val label = text()
        val lat = readDouble()
        val lon = readDouble()
        Place(label, lat, lon)
    }

    fun query(input: String): Query {
        val blob = guard { nativeQuery(input) } ?: throw IOException("routecore")
        return DataInputStream(ByteArrayInputStream(blob)).use { r ->
            if (r.readUnsignedByte() == 0) Query.Text(r.text()) else Query.Found(r.places())
        }
    }

    fun searchUrl(kind: Int, text: String): String = guard { nativeSearchUrl(kind, text) } ?: throw IOException("routecore")

    fun isCoords(text: String): Boolean = try {
        guard { nativeIsCoords(text) } == true
    } catch (_: IOException) {
        false
    }

    fun nextSource(kind: Int): Int = guard { nativeNextSource(kind) } ?: NO_SOURCE

    fun routeBody(stops: List<LatLng>, mode: Int, traffic: Boolean): String? =
        guard { nativeRouteBody(RouteNative.flatten(stops), mode, traffic) }

    fun elevationUrlGoogle(encoded: String, key: String): String? = guard { nativeElevationUrlGoogle(encoded, key) }

    fun elevationUrlOpenMeteo(points: List<LatLng>): String? = guard { nativeElevationUrlOpenMeteo(RouteNative.flatten(points)) }

    fun elevCacheGet(key: String): FloatArray? = try {
        guard { nativeElevCacheGet(key) }
    } catch (_: IOException) {
        null
    }

    fun elevCachePut(key: String, values: FloatArray) {
        try {
            guard { nativeElevCachePut(key, values) }
        } catch (_: IOException) {
        }
    }

    private fun safeText(block: () -> String?): String = try {
        guard(block).orEmpty()
    } catch (_: IOException) {
        ""
    }

    fun formatDistance(m: Double): String = safeText { nativeFormatDistance(m) }

    fun formatDuration(sec: Double): String = safeText { nativeFormatDuration(sec) }

    fun address(line: String?, lat: Double, lng: Double): String = safeText { nativeAddress(line, lat, lng) }
}
