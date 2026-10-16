package com.heryts.setloc.utils.ext

import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Geocoder tanpa API deprecated: listener di API 33+, versi sync (di-suppress) di bawahnya. */
suspend fun Geocoder.fromLocation(lat: Double, lng: Double, max: Int): List<Address>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { c ->
            getFromLocation(lat, lng, max, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) = c.resume(addresses)
                override fun onError(errorMessage: String?) =
                    c.resumeWithException(java.io.IOException(errorMessage))
            })
        }
    } else {
        @Suppress("DEPRECATION") getFromLocation(lat, lng, max)
    }

suspend fun Geocoder.fromName(name: String, max: Int): List<Address>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { c ->
            getFromLocationName(name, max, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) = c.resume(addresses)
                override fun onError(errorMessage: String?) =
                    c.resumeWithException(java.io.IOException(errorMessage))
            })
        }
    } else {
        @Suppress("DEPRECATION") getFromLocationName(name, max)
    }
