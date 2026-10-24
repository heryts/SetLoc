package com.heryts.setloc.history

import android.content.Context
import com.heryts.setloc.favourite.StoreNative
import com.heryts.setloc.gsApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HistoryItem(val label: String, val lat: Double, val lon: Double)

object SearchHistoryStore {
    private const val FILE = "setloc_history"
    private const val KEY = "list"
    private const val MAX = 30

    private val lock = Any()
    private val state = MutableStateFlow<List<HistoryItem>>(emptyList())
    private var loaded = false

    val all: StateFlow<List<HistoryItem>> get() = state

    private val sp get() = gsApp.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load() {
        synchronized(lock) {
            if (loaded) return
            if (!StoreNative.ready) return
            val raw = sp.getString(KEY, null)
            val items = if (raw != null) StoreNative.history(raw) else emptyList()
            if (!StoreNative.ready) return
            state.value = items ?: emptyList()
            loaded = true
        }
    }

    fun add(label: String, lat: Double, lon: Double) {
        synchronized(lock) {
            load()
            val item = HistoryItem(label, lat, lon)
            val list = (listOf(item) + state.value.filterNot { it.label == label && it.lat == lat && it.lon == lon })
                .take(MAX)
            state.value = list
            save(list)
        }
    }

    fun remove(item: HistoryItem) {
        synchronized(lock) {
            load()
            val list = state.value.filterNot { it == item }
            state.value = list
            save(list)
        }
    }

    fun clear() {
        synchronized(lock) {
            load()
            state.value = emptyList()
            save(emptyList())
        }
    }

    private fun save(list: List<HistoryItem>) {
        val json = StoreNative.encodeHistory(list) ?: return
        sp.edit().putString(KEY, json).apply()
    }
}
