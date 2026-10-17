package com.heryts.setloc.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Favourite::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favouriteDao(): FavouriteDao
}
