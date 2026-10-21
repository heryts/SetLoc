package com.heryts.setloc.utils.ext

import java.util.Locale

fun formatDistance(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000.0)

fun formatDuration(sec: Double): String {
    val s = sec.toLong()
    if (s < 60) return "$s s"
    val m = (s + 30) / 60
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}
