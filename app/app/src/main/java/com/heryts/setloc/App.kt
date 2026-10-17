package com.heryts.setloc

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.heryts.setloc.utils.PrefManager
import dagger.hilt.android.HiltAndroidApp

lateinit var gsApp: App

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        gsApp = this
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)
        val sp = getSharedPreferences("setloc_lang", MODE_PRIVATE)
        if (!sp.getBoolean("default_id_done", false)) {
            sp.edit().putBoolean("default_id_done", true).apply()
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("id"))
            }
        }
    }
}
