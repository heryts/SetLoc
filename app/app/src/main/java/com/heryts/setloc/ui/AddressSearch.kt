package com.heryts.setloc.ui

import android.content.Context
import com.heryts.setloc.R
import com.heryts.setloc.utils.Http
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

private val COORDS = Regex("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?")

private const val GEO_CACHE_SIZE = 16

private val geoCache = object : LinkedHashMap<String, SearchProgress.Complete>(GEO_CACHE_SIZE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchProgress.Complete>?) =
        size > GEO_CACHE_SIZE
}

internal fun searchAddress(context: Context, address: String): Flow<SearchProgress> = flow {
    emit(SearchProgress.Progress)
    emit(resolveAddress(context, address.trim()))
}.flowOn(Dispatchers.IO)

private suspend fun resolveAddress(context: Context, input: String): SearchProgress {
    val notFound = SearchProgress.Fail(context.getString(R.string.address_not_found))

    if (COORDS.matches(input)) {
        return parseCoords(input)?.let { SearchProgress.Complete(it.first, it.second) } ?: notFound
    }

    val locale = Locale.getDefault()
    val key = "${locale.toLanguageTag()}|${input.lowercase(locale)}"
    synchronized(geoCache) { geoCache[key] }?.let { return it }

    return try {
        val first = searchPlaces(context, input).firstOrNull()
        if (first != null) {
            SearchProgress.Complete(first.lat, first.lon).also {
                synchronized(geoCache) { geoCache[key] = it }
            }
        } else notFound
    } catch (_: IOException) {
        SearchProgress.Fail(context.getString(R.string.no_internet))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        notFound
    }
}

data class PlaceCandidate(val label: String, val lat: Double, val lon: Double)

private const val PHOTON_URL = "https://photon.komoot.io/api/?limit=5&q="
private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q="
private val GEO_HEADERS = mapOf("User-Agent" to "SetLoc-Android", "Accept" to "application/json")

private suspend fun fetchJson(url: String): String {
    val res = Http.call(url, headers = GEO_HEADERS, connectMs = 6_000, readMs = 8_000, retries = 0)
    if (!res.ok) throw IOException("HTTP ${res.code}")
    return res.text
}

private fun fmtCoords(lat: Double, lon: Double) = String.format(Locale.US, "%.5f, %.5f", lat, lon)

private fun parseCoords(text: String): Pair<Double, Double>? {
    val parts = text.split(",")
    val la = parts[0].trim().toDoubleOrNull()
    val lo = parts[1].trim().toDoubleOrNull()
    return if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) la to lo else null
}

private suspend fun photonSearch(text: String): List<PlaceCandidate> {
    val features = JSONObject(fetchJson(PHOTON_URL + URLEncoder.encode(text, "UTF-8"))).optJSONArray("features")
        ?: return emptyList()
    val out = ArrayList<PlaceCandidate>()
    for (i in 0 until features.length()) {
        val f = features.optJSONObject(i) ?: continue
        val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
        val props = f.optJSONObject("properties")
        val name = props?.optString("name").orEmpty().ifBlank { text }
        val sub = listOf(
            props?.optString("city").orEmpty().ifBlank { props?.optString("county").orEmpty() },
            props?.optString("state").orEmpty(),
            props?.optString("country").orEmpty()
        ).filter { it.isNotBlank() && it != name }.distinct()
        val la = coords.optDouble(1)
        val lo = coords.optDouble(0)
        if (la.isNaN() || lo.isNaN() || la !in -90.0..90.0 || lo !in -180.0..180.0) continue
        out += PlaceCandidate((listOf(name) + sub).joinToString(", "), la, lo)
    }
    return out
}

private suspend fun nominatimSearch(text: String): List<PlaceCandidate> {
    val arr = JSONArray(fetchJson(NOMINATIM_URL + URLEncoder.encode(text, "UTF-8")))
    val out = ArrayList<PlaceCandidate>()
    for (i in 0 until arr.length()) {
        val r = arr.optJSONObject(i) ?: continue
        val lat = r.optString("lat").toDoubleOrNull() ?: continue
        val lon = r.optString("lon").toDoubleOrNull() ?: continue
        out += PlaceCandidate(r.optString("display_name").ifBlank { fmtCoords(lat, lon) }, lat, lon)
    }
    return out
}

internal suspend fun searchPlaces(context: Context, input: String): List<PlaceCandidate> =
    withContext(Dispatchers.IO) {
        val text = input.trim()
        if (COORDS.matches(text)) {
            return@withContext parseCoords(text)
                ?.let { listOf(PlaceCandidate(fmtCoords(it.first, it.second), it.first, it.second)) }
                ?: emptyList()
        }
        val primary = try {
            photonSearch(text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val result = primary.ifEmpty { nominatimSearch(text) }
        result.distinctBy { it.label }
    }

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double, val lon: Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
