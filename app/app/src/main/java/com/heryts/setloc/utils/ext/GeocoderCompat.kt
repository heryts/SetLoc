package com.heryts.setloc.utils.ext

import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private fun CancellableContinuation<List<Address>?>.listener() = object : Geocoder.GeocodeListener {
    override fun onGeocode(addresses: MutableList<Address>) = resume(addresses)
    override fun onError(errorMessage: String?) = resumeWithException(java.io.IOException(errorMessage))
}

suspend fun Geocoder.fromLocation(lat: Double, lng: Double, max: Int): List<Address>? =
    suspendCancellableCoroutine { getFromLocation(lat, lng, max, it.listener()) }
