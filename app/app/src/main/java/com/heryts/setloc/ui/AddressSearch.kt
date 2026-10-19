package com.heryts.setloc.ui

import android.content.Context
import android.location.Geocoder
import com.heryts.setloc.R
import com.heryts.setloc.utils.ext.fromName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
        val parts = input.split(",")
        val la = parts[0].trim().toDoubleOrNull()
        val lo = parts[1].trim().toDoubleOrNull()
        return if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
            SearchProgress.Complete(la, lo)
        } else notFound
    }

    val locale = Locale.getDefault()
    val key = "${locale.toLanguageTag()}|${input.lowercase(locale)}"
    synchronized(geoCache) { geoCache[key] }?.let { return it }

    return try {
        if (!Geocoder.isPresent()) {
            notFound
        } else {
            val first = Geocoder(context, locale).fromName(input, 1)?.firstOrNull()
            if (first != null) {
                SearchProgress.Complete(first.latitude, first.longitude).also {
                    synchronized(geoCache) { geoCache[key] = it }
                }
            } else notFound
        }
    } catch (_: IOException) {
        SearchProgress.Fail(context.getString(R.string.no_internet))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        notFound
    }
}

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double, val lon: Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
