package com.heryts.setloc.utils.ext

import android.content.Context
import android.location.Geocoder
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale

fun LatLng.getAddress(context: Context): Flow<String> = flow {
    val sb = StringBuilder()
    try {
        val addresses = Geocoder(context, Locale.getDefault()).fromLocation(latitude, longitude, 1)
        if (!addresses.isNullOrEmpty()) {
            val address = addresses[0].getAddressLine(0) ?: ""
            val strs = address.split(",".toRegex()).toTypedArray()
            if (strs.size > 1) {
                sb.append(strs[0])
                val index = address.indexOf(",") + 2
                if (index > 1 && address.length > index) {
                    sb.append("\n").append(address.substring(index))
                }
            } else {
                sb.append(address)
            }
        }
    } catch (_: Exception) {
    }
    if (sb.isEmpty()) sb.append("%.5f, %.5f".format(Locale.US, latitude, longitude))
    emit(sb.toString())
}.flowOn(Dispatchers.IO)
