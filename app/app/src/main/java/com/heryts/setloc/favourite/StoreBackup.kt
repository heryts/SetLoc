package com.heryts.setloc.favourite

import android.content.SharedPreferences

private const val KEY = "list"
private const val KEY_BAD = "list_bad"
private const val KEEP_BAD = 3

internal fun stashBad(prefs: SharedPreferences, raw: String?) {
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
