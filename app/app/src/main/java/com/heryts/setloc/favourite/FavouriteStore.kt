package com.heryts.setloc.favourite

import android.content.Context
import com.heryts.setloc.gsApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

object FavouriteStore {
    private const val FILE = "setloc_fav"
    private const val KEY = "list"
    private const val KEY_BAD = "list_bad"
    private const val KEY_NEXT = "next_id"
    private const val KEEP_BAD = 3

    private val lock = Any()
    private val state = MutableStateFlow<List<Favourite>>(emptyList())
    private var loaded = false
    private var nextId = 1L

    val all: StateFlow<List<Favourite>> get() = state

    private val sp get() = gsApp.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load() {
        synchronized(lock) {
            if (loaded) return
            val raw = sp.getString(KEY, null)
            val parsed = if (raw != null) parse(raw) else emptyList()
            if (parsed == null) stashBad(raw)
            val list = parsed ?: emptyList()
            nextId = maxOf((list.maxOfOrNull { it.id } ?: 0L) + 1, sp.getLong(KEY_NEXT, 1L))
            state.value = list
            loaded = true
        }
    }

    fun add(address: String, lat: Double, lng: Double): Boolean = synchronized(lock) {
        load()
        val list = listOf(Favourite(nextId, address, lat, lng)) + state.value
        val ok = save(list, nextId + 1)
        if (ok) {
            nextId++
            state.value = list
        }
        ok
    }

    fun remove(favourite: Favourite) {
        synchronized(lock) {
            load()
            val list = state.value.filterNot { it.id == favourite.id }
            if (save(list, nextId)) state.value = list
        }
    }

    private fun stashBad(raw: String?) {
        val prefs = sp
        val legacy = prefs.getString(KEY_BAD, null)
        val legacyKey = "${KEY_BAD}_0"
        val keys = prefs.all.keys.filter { it.startsWith("${KEY_BAD}_") }.toMutableSet()
        if (legacy != null) keys.add(legacyKey)
        val drop = keys.sorted().dropLast(KEEP_BAD - 1).toSet()
        val e = prefs.edit()
        if (legacy != null && legacyKey !in drop) e.putString(legacyKey, legacy)
        drop.forEach { e.remove(it) }
        e.putString("${KEY_BAD}_${System.currentTimeMillis()}", raw).remove(KEY_BAD).remove(KEY).commit()
    }

    private fun save(list: List<Favourite>, next: Long): Boolean {
        val arr = JSONArray()
        for (f in list) {
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("a", f.address ?: JSONObject.NULL)
                    .put("la", f.lat ?: JSONObject.NULL)
                    .put("ln", f.lng ?: JSONObject.NULL)
            )
        }
        return sp.edit().putString(KEY, arr.toString()).putLong(KEY_NEXT, next).commit()
    }

    internal fun parse(raw: String): List<Favourite>? = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull {
            try {
                val o = arr.getJSONObject(it)
                Favourite(
                    o.getLong("id"),
                    if (o.isNull("a")) null else o.getString("a"),
                    if (o.isNull("la")) null else o.getDouble("la"),
                    if (o.isNull("ln")) null else o.getDouble("ln")
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.id }
    } catch (_: Exception) {
        null
    }
}
