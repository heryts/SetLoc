package com.heryts.setloc.utils.ext

import java.util.Locale

fun formatDistance(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000.0)

fun formatDuration(sec: Double): String {
    val s = sec.toLong()
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${(s + 30) / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}
