package com.heryts.setloc.viewmodel

import androidx.lifecycle.ViewModel
import com.heryts.setloc.favourite.Favourite
import com.heryts.setloc.favourite.FavouriteStore
import com.heryts.setloc.favourite.SavedRoute
import com.heryts.setloc.favourite.SavedRouteStore
import com.heryts.setloc.utils.PrefManager
import com.heryts.setloc.utils.ext.onIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {
    val getLat get() = PrefManager.getLat
    val getLng get() = PrefManager.getLng
    val isStarted get() = PrefManager.isStarted
    val mapType get() = PrefManager.mapType

    init {
        onIO {
            FavouriteStore.load()
            SavedRouteStore.load()
        }
    }

    fun update(start: Boolean, la: Double, ln: Double) = PrefManager.update(start, la, ln)

    fun deleteFavourite(favourite: Favourite) = onIO { FavouriteStore.remove(favourite) }

    fun deleteSavedRoute(route: SavedRoute) = onIO { SavedRouteStore.remove(route) }

    fun storeRoute(name: String, mode: Int, points: List<Pair<Double, Double>>, onResult: (Boolean) -> Unit) = onIO {
        val ok = SavedRouteStore.add(name, mode, points)
        withContext(Dispatchers.Main) { onResult(ok) }
    }

    fun storeFavorite(address: String, lat: Double, lon: Double, onResult: (Boolean) -> Unit) = onIO {
        val ok = FavouriteStore.add(address, lat, lon)
        withContext(Dispatchers.Main) { onResult(ok) }
    }
}
