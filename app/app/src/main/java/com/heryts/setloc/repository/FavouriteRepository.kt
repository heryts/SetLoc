package com.heryts.setloc.repository

import com.heryts.setloc.room.Favourite
import com.heryts.setloc.room.FavouriteDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class FavouriteRepository @Inject constructor(private val favouriteDao: FavouriteDao) {
    val getAllFavourites: Flow<List<Favourite>> get() = favouriteDao.getAllFavourites()

    suspend fun addNewFavourite(favourite: Favourite): Long = favouriteDao.insertToRoomDatabase(favourite)

    suspend fun deleteFavourite(favourite: Favourite) = favouriteDao.deleteSingleFavourite(favourite)

    fun getSingleFavourite(id: Long): Favourite? = favouriteDao.getSingleFavourite(id)
}
