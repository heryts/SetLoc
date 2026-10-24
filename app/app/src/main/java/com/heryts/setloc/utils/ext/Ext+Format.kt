package com.heryts.setloc.utils.ext

import com.heryts.setloc.utils.NetNative

fun formatDistance(m: Double): String = NetNative.formatDistance(m)

fun formatDuration(sec: Double): String = NetNative.formatDuration(sec)
