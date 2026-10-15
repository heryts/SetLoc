package com.heryts.setloc

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.heryts.setloc.utils.PrefManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import rikka.material.app.DayNightDelegate
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
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)
        // Bahasa default = Indonesia, diterapkan SEKALI saat pertama jalan (tanpa mengikuti bahasa sistem).
        // Setelah itu pilihan pengguna (Pengaturan > Aplikasi > SetLoc > Bahasa) tidak ditimpa lagi.
        try {
            val sp = getSharedPreferences("setloc_lang", MODE_PRIVATE)
            if (!sp.getBoolean("default_id_done", false)) {
                sp.edit().putBoolean("default_id_done", true).apply()
                if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("id"))
                }
            }
        } catch (_: Throwable) { }

    }


}