package com.heryts.setloc.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Favourite(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val address: String?,
    val lat: Double?,
    val lng: Double?
)
