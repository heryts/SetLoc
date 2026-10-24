package com.heryts.setloc.ui

import com.heryts.setloc.utils.Http
import com.heryts.setloc.utils.NetNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class PlaceCandidate(val label: String, val lat: Double, val lon: Double)

private val GEO_HEADERS = mapOf("User-Agent" to "SetLoc-Android", "Accept" to "application/json")

private suspend fun fetchPlaces(kind: Int, text: String): List<PlaceCandidate> {
    val res = Http.call(NetNative.searchUrl(kind, text), headers = GEO_HEADERS, connectMs = 6_000, readMs = 8_000, retries = 0)
    if (!res.ok) throw IOException("HTTP ${res.code}")
    val places = NetNative.places(kind, res.text, text) ?: throw IOException("JSON")
    return places.map { PlaceCandidate(it.label, it.lat, it.lon) }
}

private suspend fun searchText(text: String): List<PlaceCandidate> {
    var kind = NetNative.nextSource(NetNative.NO_SOURCE)
    while (true) {
        val next = NetNative.nextSource(kind)
        val found = if (next == NetNative.NO_SOURCE) {
            fetchPlaces(kind, text)
        } else {
            try {
                fetchPlaces(kind, text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
        if (found.isNotEmpty() || next == NetNative.NO_SOURCE) return found
        kind = next
    }
}

internal suspend fun searchPlaces(input: String): List<PlaceCandidate> =
    withContext(Dispatchers.IO) {
        when (val q = NetNative.query(input)) {
            is NetNative.Query.Found -> q.places.map { PlaceCandidate(it.label, it.lat, it.lon) }
            is NetNative.Query.Text -> searchText(q.text)
        }
    }
