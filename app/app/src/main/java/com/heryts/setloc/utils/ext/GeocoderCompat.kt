package com.heryts.setloc.utils.ext

import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RequiresApi(33)
private object Api33 {
    private fun CancellableContinuation<List<Address>?>.listener() = object : Geocoder.GeocodeListener {
        override fun onGeocode(addresses: MutableList<Address>) = resume(addresses)
        override fun onError(errorMessage: String?) = resumeWithException(java.io.IOException(errorMessage))
    }

    suspend fun fromLocation(g: Geocoder, lat: Double, lng: Double, max: Int): List<Address>? =
        suspendCancellableCoroutine { g.getFromLocation(lat, lng, max, it.listener()) }
}

suspend fun Geocoder.fromLocation(lat: Double, lng: Double, max: Int): List<Address>? =
    if (Build.VERSION.SDK_INT >= 33) {
        Api33.fromLocation(this, lat, lng, max)
    } else {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            getFromLocation(lat, lng, max)
        }
    }
