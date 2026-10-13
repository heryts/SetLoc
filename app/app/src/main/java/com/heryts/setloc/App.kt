package com.heryts.setloc

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.heryts.setloc.utils.PrefManager
import com.kieronquinn.monetcompat.core.MonetCompat
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import timber.log.Timber

lateinit var gsApp: App


@HiltAndroidApp
class App : Application() {
    val globalScope = CoroutineScope(Dispatchers.Default)

    companion object {
        fun commonInit() {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        gsApp = this
        commonInit()
        MonetCompat.enablePaletteCompat()
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)

    }


}