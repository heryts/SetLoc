package com.heryts.setloc.module

import android.app.Application
import androidx.room.Room
import com.heryts.setloc.room.AppDatabase
import com.heryts.setloc.room.FavouriteDao
import com.heryts.setloc.utils.PrefManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(application: Application): AppDatabase =
        Room.databaseBuilder(application, AppDatabase::class.java, "user_database")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideFavouriteDao(db: AppDatabase): FavouriteDao = db.favouriteDao()

    @Provides
    @Singleton
    fun providePrefManager(): PrefManager = PrefManager
}
