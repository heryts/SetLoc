package com.heryts.setloc.utils.ext

import android.content.Context
import android.location.Geocoder
import com.google.android.gms.maps.model.LatLng
import com.heryts.setloc.utils.NetNative
import kotlinx.coroutines.CancellationException
import java.util.Locale

suspend fun LatLng.address(context: Context): String {
    val a = try {
        Geocoder(context, Locale.getDefault()).fromLocation(latitude, longitude, 1)?.firstOrNull()?.getAddressLine(0)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }
    return NetNative.address(a, latitude, longitude)
}
