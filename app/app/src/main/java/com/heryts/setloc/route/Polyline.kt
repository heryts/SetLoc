package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng

internal object Polyline {
    @Volatile internal var completeFn: (String) -> Boolean = { RouteNative.polylineComplete(it) }
    @Volatile internal var decodeFn: (String) -> List<LatLng> = { RouteNative.polylineDecode(it) ?: emptyList() }

    fun isComplete(encoded: String): Boolean = completeFn(encoded)

    fun decode(encoded: String): List<LatLng> = decodeFn(encoded)
}
