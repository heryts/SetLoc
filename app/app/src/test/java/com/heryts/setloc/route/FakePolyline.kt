package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng

internal object FakePolyline {
    fun isComplete(encoded: String): Boolean {
        if (encoded.isEmpty()) return false
        var values = 0
        for (c in encoded) if (c.code - 63 < 0x20) values++
        return values % 2 == 0 && encoded.last().code - 63 < 0x20
    }

    fun decode(encoded: String): List<LatLng> {
        val out = ArrayList<LatLng>(encoded.length / 4)
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            if (b >= 0x20) break
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            if (index >= encoded.length) break
            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            if (b >= 0x20) break
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(LatLng(lat / 1e5, lng / 1e5))
        }
        return out
    }

    fun install(): () -> Unit {
        val oc = Polyline.completeFn
        val od = Polyline.decodeFn
        Polyline.completeFn = { isComplete(it) }
        Polyline.decodeFn = { decode(it) }
        return {
            Polyline.completeFn = oc
            Polyline.decodeFn = od
        }
    }
}
