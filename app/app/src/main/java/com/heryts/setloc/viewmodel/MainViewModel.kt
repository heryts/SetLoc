package com.heryts.setloc.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heryts.setloc.repository.FavouriteRepository
import com.heryts.setloc.room.Favourite
import com.heryts.setloc.utils.PrefManager
import com.heryts.setloc.utils.ext.onIO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val favouriteRepository: FavouriteRepository,
    private val prefManager: PrefManager
) : ViewModel() {

    // Getter (bukan nilai sekali baca): ViewModel bertahan lewat rotasi, padahal rute/Stop mengubah pref di luar Activity.
    val getLat get() = prefManager.getLat
    val getLng get() = prefManager.getLng
    val isStarted get() = prefManager.isStarted
    val mapType get() = prefManager.mapType

    val allFavList: StateFlow<List<Favourite>> = favouriteRepository.getAllFavourites
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun update(start: Boolean, la: Double, ln: Double) = prefManager.update(start, la, ln)

    fun deleteFavourite(favourite: Favourite) = onIO { favouriteRepository.deleteFavourite(favourite) }

    fun storeFavorite(address: String, lat: Double, lon: Double, onResult: (Boolean) -> Unit) = onIO {
        var slot = 0L
        while (favouriteRepository.getSingleFavourite(slot) != null) slot++
        val id = favouriteRepository.addNewFavourite(Favourite(id = slot, address = address, lat = lat, lng = lon))
        withContext(Dispatchers.Main) { onResult(id != -1L) }
    }
}
