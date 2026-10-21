package com.heryts.setloc.favourite

import android.content.Context
import com.heryts.setloc.gsApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class SavedRoute(
    val id: Long,
    val name: String,
    val mode: Int,
    val points: List<Pair<Double, Double>>
)

object SavedRouteStore {
    private const val FILE = "setloc_saved_routes"
    private const val KEY = "list"
    private const val KEY_BAD = "list_bad"
    private const val KEY_NEXT = "next_id"
    private const val KEEP_BAD = 3

    private val lock = Any()
    private val state = MutableStateFlow<List<SavedRoute>>(emptyList())
    private var loaded = false
    private var nextId = 1L

    val all: StateFlow<List<SavedRoute>> get() = state

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

    fun add(name: String, mode: Int, points: List<Pair<Double, Double>>): Boolean = synchronized(lock) {
        load()
        val list = listOf(SavedRoute(nextId, name, mode, points)) + state.value
        val ok = save(list, nextId + 1)
        if (ok) {
            nextId++
            state.value = list
        }
        ok
    }

    fun remove(route: SavedRoute) {
        synchronized(lock) {
            load()
            val list = state.value.filterNot { it.id == route.id }
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

    private fun save(list: List<SavedRoute>, next: Long): Boolean {
        val arr = JSONArray()
        for (r in list) {
            val pts = JSONArray()
            r.points.forEach { pts.put(JSONArray().put(it.first).put(it.second)) }
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("n", r.name)
                    .put("m", r.mode)
                    .put("p", pts)
            )
        }
        return sp.edit().putString(KEY, arr.toString()).putLong(KEY_NEXT, next).commit()
    }

    internal fun parse(raw: String): List<SavedRoute>? = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull {
            try {
                val o = arr.getJSONObject(it)
                val p = o.getJSONArray("p")
                val pts = (0 until p.length()).map { i ->
                    val a = p.getJSONArray(i)
                    a.getDouble(0) to a.getDouble(1)
                }
                if (pts.size < 2) null else SavedRoute(o.getLong("id"), o.getString("n"), o.getInt("m"), pts)
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.id }
    } catch (_: Exception) {
        null
    }
}
