package com.heryts.setloc.favourite

data class Favourite(
    val id: Long = 0,
    val address: String?,
    val lat: Double?,
    val lng: Double?
)
