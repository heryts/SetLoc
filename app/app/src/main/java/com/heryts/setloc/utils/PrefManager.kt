package com.heryts.setloc.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.heryts.setloc.BuildConfig
import com.heryts.setloc.gsApp
import dagger.Reusable
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import rikka.material.app.DayNightDelegate
import java.io.File


@SuppressLint("WorldReadableFiles")

object PrefManager   {

    private const val START = "start"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val HOOKED_SYSTEM = "isHookedSystem"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_settings"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val DISABLE_UPDATE = "disable_update"
    private const val ROUTE_SPEED = "route_speed"
    private const val ROUTE_MODE = "route_mode"
    private const val ROUTE_WAIT = "route_wait_start"


    private val pref: SharedPreferences by lazy {
         try {
             val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
             gsApp.getSharedPreferences(
                 prefsFile,
                 Context.MODE_WORLD_READABLE
             )
         }catch (e:SecurityException){
             val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
             gsApp.getSharedPreferences(
                 prefsFile,
                 Context.MODE_PRIVATE
             )
         }

    }


    val isStarted : Boolean
    get() = pref.getBoolean(START, false)

    val getLat : Double
    get() = pref.getFloat(LATITUDE, 40.7128F).toDouble()

    val getLng : Double
    get() = pref.getFloat(LONGITUDE, -74.0060F).toDouble()

    var isHookSystem : Boolean
    get() = pref.getBoolean(HOOKED_SYSTEM, false)
    set(value) { pref.edit().putBoolean(HOOKED_SYSTEM,value).apply() }

    var isRandomPosition :Boolean
    get() = pref.getBoolean(RANDOM_POSITION, false)
    set(value) { pref.edit().putBoolean(RANDOM_POSITION, value).apply() }

    var accuracy : String?
    get() = pref.getString(ACCURACY_SETTING,"10")
    set(value) { pref.edit().putString(ACCURACY_SETTING,value).apply()}

    var mapType : Int
    get() = pref.getInt(MAP_TYPE,1)
    set(value) { pref.edit().putInt(MAP_TYPE,value).apply()}

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, DayNightDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(DARK_THEME, value).apply()

    var disableUpdate: Boolean
        get() = pref.getBoolean(DISABLE_UPDATE, false)
        set(value) = pref.edit().putBoolean(DISABLE_UPDATE, value).apply()



    /** Mode rute: 0 jalan kaki, 1 sepeda, 2 motor, 3 mobil. */
    var routeMode: Int
        get() = pref.getInt(ROUTE_MODE, 3)
        set(value) { pref.edit().putInt(ROUTE_MODE, value).apply() }

    /** Kecepatan simulasi rute (km/jam). */
    var routeSpeed: Float
        get() = pref.getFloat(ROUTE_SPEED, 50f)
        set(value) { pref.edit().putFloat(ROUTE_SPEED, value).apply() }

    /** Lama diam di titik awal sebelum jalan (menit). */
    var routeWaitStart: Float
        get() = pref.getFloat(ROUTE_WAIT, 0f)
        set(value) { pref.edit().putFloat(ROUTE_WAIT, value).apply() }

    /**
     * Seperti [update] tapi langsung di thread pemanggil (apply() sudah menjaga urutan di memori).
     * Dipakai simulasi rute supaya tulisan terakhir tidak bisa "menyalip" tombol Stop.
     */
    fun updateNow(start: Boolean, la: Double, ln: Double) {
        pref.edit()
            .putFloat(LATITUDE, la.toFloat())
            .putFloat(LONGITUDE, ln.toFloat())
            .putBoolean(START, start)
            .apply()
    }

    fun update(start:Boolean, la: Double, ln: Double) {
        runInBackground {
            val prefEditor = pref.edit()
            prefEditor.putFloat(LATITUDE, la.toFloat())
            prefEditor.putFloat(LONGITUDE, ln.toFloat())
            prefEditor.putBoolean(START, start)
            prefEditor.apply()
        }

    }


    @OptIn(DelicateCoroutinesApi::class)
    private fun runInBackground(method: suspend () -> Unit){
        GlobalScope.launch(Dispatchers.IO) {
            method.invoke()
        }
    }





}