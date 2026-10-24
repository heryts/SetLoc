package com.heryts.setloc.favourite

import com.heryts.setloc.history.HistoryItem
import com.heryts.setloc.route.RouteNative
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object StoreNative {
    private const val KIND_FAV = 0
    private const val KIND_ROUTE = 1
    private const val KIND_HISTORY = 2
    private const val HAS_ADDRESS = 1
    private const val HAS_LAT = 2
    private const val HAS_LNG = 4

    @Volatile private var failed = false

    val ready: Boolean
        get() = !failed && RouteNative.ready

    @JvmStatic
    private external fun nativeParse(kind: Int, raw: String): ByteArray?

    @JvmStatic
    private external fun nativeEncode(kind: Int, blob: ByteArray): String?

    private fun <T> guard(block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        failed = true
        null
    }

    private fun parse(kind: Int, raw: String): ByteArray? =
        if (ready) guard { nativeParse(kind, raw) } else null

    private fun encode(kind: Int, write: (DataOutputStream) -> Unit): String? {
        if (!ready) return null
        return guard {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use(write)
            nativeEncode(kind, bos.toByteArray())
        }
    }

    private fun DataOutputStream.text(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
    }

    private fun DataInputStream.text(): String {
        val b = ByteArray(readInt())
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    fun favourites(raw: String): List<Favourite>? {
        val blob = parse(KIND_FAV, raw) ?: return null
        return guard {
            DataInputStream(ByteArrayInputStream(blob)).use { r ->
                List(r.readInt()) {
                    val id = r.readLong()
                    val flags = r.readUnsignedByte()
                    val address = if ((flags and HAS_ADDRESS) != 0) r.text() else null
                    val lat = if ((flags and HAS_LAT) != 0) r.readDouble() else null
                    val lng = if ((flags and HAS_LNG) != 0) r.readDouble() else null
                    Favourite(id, address, lat, lng)
                }
            }
        }
    }

    fun encodeFavourites(list: List<Favourite>): String? = encode(KIND_FAV) { w ->
        w.writeInt(list.size)
        for (f in list) {
            w.writeLong(f.id)
            var flags = 0
            if (f.address != null) flags = flags or HAS_ADDRESS
            if (f.lat != null) flags = flags or HAS_LAT
            if (f.lng != null) flags = flags or HAS_LNG
            w.writeByte(flags)
            f.address?.let { w.text(it) }
            f.lat?.let { w.writeDouble(it) }
            f.lng?.let { w.writeDouble(it) }
        }
    }

    fun routes(raw: String): List<SavedRoute>? {
        val blob = parse(KIND_ROUTE, raw) ?: return null
        return guard {
            DataInputStream(ByteArrayInputStream(blob)).use { r ->
                List(r.readInt()) {
                    val id = r.readLong()
                    val name = r.text()
                    val mode = r.readInt()
                    val pts = List(r.readInt()) {
                        val la = r.readDouble()
                        val ln = r.readDouble()
                        la to ln
                    }
                    SavedRoute(id, name, mode, pts)
                }
            }
        }
    }

    fun encodeRoutes(list: List<SavedRoute>): String? = encode(KIND_ROUTE) { w ->
        w.writeInt(list.size)
        for (r in list) {
            w.writeLong(r.id)
            w.text(r.name)
            w.writeInt(r.mode)
            w.writeInt(r.points.size)
            for (p in r.points) {
                w.writeDouble(p.first)
                w.writeDouble(p.second)
            }
        }
    }

    fun history(raw: String): List<HistoryItem>? {
        val blob = parse(KIND_HISTORY, raw) ?: return null
        return guard {
            DataInputStream(ByteArrayInputStream(blob)).use { r ->
                List(r.readInt()) {
                    val label = r.text()
                    val lat = r.readDouble()
                    val lon = r.readDouble()
                    HistoryItem(label, lat, lon)
                }
            }
        }
    }

    fun encodeHistory(list: List<HistoryItem>): String? = encode(KIND_HISTORY) { w ->
        w.writeInt(list.size)
        for (h in list) {
            w.text(h.label)
            w.writeDouble(h.lat)
            w.writeDouble(h.lon)
        }
    }
}
