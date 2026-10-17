package com.heryts.setloc.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertToRoomDatabase(favourite: Favourite): Long

    @Delete
    suspend fun deleteSingleFavourite(favourite: Favourite)

    @Query("SELECT * FROM favourite ORDER BY id DESC")
    fun getAllFavourites(): Flow<List<Favourite>>

    @Query("SELECT * FROM favourite WHERE id = :id")
    fun getSingleFavourite(id: Long): Favourite?
}
