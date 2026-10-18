package com.heryts.setloc.utils.ext

fun fmtNum(f: Float): String = if (f % 1f == 0f) f.toInt().toString() else f.toString()
