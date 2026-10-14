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
    private const val ROUTE_SPEED_VAR = "route_speed_var"
    private const val SPEED_UNIT_MPH = "speed_unit_mph"
    private const val SPEED_VAR_UNIT = "speed_var_unit"
    private const val ROUTE_WAIT_END = "route_wait_end"
    // dibaca bridge.sh dari file pref -> gpsz_cfg (spd / brg / alt)
    private const val SPEED_MS = "speed_ms"
    private const val BEARING = "bearing"
    private const val ALTITUDE = "altitude"
    private const val ALTITUDE_MANUAL = "altitude_manual"
    private const val ALTITUDE_VAR = "altitude_var"          // dibaca bridge.sh -> altv
    private const val ALTITUDE_SERVER = "altitude_from_server"


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

    /** Satuan kecepatan di opsi rute: false = km/h, true = MPH. Nilai tersimpan tetap km/h. */
    var speedUnitMph: Boolean
        get() = pref.getBoolean(SPEED_UNIT_MPH, false)
        set(value) { pref.edit().putBoolean(SPEED_UNIT_MPH, value).apply() }

    /** Nilai variasi kecepatan sesuai satuan [speedVarUnit]. Negatif = otomatis (+-8%), 0 = konstan. */
    var routeSpeedVarRaw: Float
        get() = pref.getFloat(ROUTE_SPEED_VAR, -1f)
        set(value) { pref.edit().putFloat(ROUTE_SPEED_VAR, value).apply() }

    /**
     * Satuan variasi kecepatan: 0 = persen, 1 = km/h, 2 = MPH.
     * Data lama (variasi tersimpan dalam km/h) otomatis dianggap km/h.
     */
    var speedVarUnit: Int
        get() = pref.getInt(SPEED_VAR_UNIT, if (routeSpeedVarRaw >= 0f) 1 else 0)
        set(value) { pref.edit().putInt(SPEED_VAR_UNIT, value).apply() }

    /** Variasi kecepatan +- km/jam yang dipakai RouteRunner. Negatif = otomatis 8%. */
    val routeSpeedVar: Float
        get() {
            val raw = routeSpeedVarRaw
            if (raw < 0f) return -1f
            return when (speedVarUnit) {
                0 -> routeSpeed * raw / 100f
                2 -> raw * 1.609344f
                else -> raw
            }
        }

    /** Lama diam di titik awal sebelum jalan (menit). */
    var routeWaitStart: Float
        get() = pref.getFloat(ROUTE_WAIT, 0f)
        set(value) { pref.edit().putFloat(ROUTE_WAIT, value).apply() }

    /** Lama diam di titik tujuan (menit); 0 = spoof tetap aktif sampai Stop, >0 = otomatis berhenti setelah itu. */
    var routeWaitEnd: Float
        get() = pref.getFloat(ROUTE_WAIT_END, 0f)
        set(value) { pref.edit().putFloat(ROUTE_WAIT_END, value).apply() }

    /** Ketinggian (m) yang sedang dipakai spoof (hasil Elevation API, atau [altitudeManual]). */
    var altitudeM: Float
        get() = pref.getFloat(ALTITUDE, 0f)
        set(value) { pref.edit().putFloat(ALTITUDE, value).apply() }

    /** Variasi altitude +- meter (0 = konstan). Diterapkan modul tiap ~1 detik. */
    var altitudeVariation: Float
        get() = pref.getFloat(ALTITUDE_VAR, 0f)
        set(value) { pref.edit().putFloat(ALTITUDE_VAR, value.coerceAtLeast(0f)).apply() }

    /** true = ambil ketinggian dari server (Elevation API / Open-Meteo); false = pakai [altitudeManual]. */
    var altitudeFromServer: Boolean
        get() = pref.getBoolean(ALTITUDE_SERVER, true)
        set(value) { pref.edit().putBoolean(ALTITUDE_SERVER, value).apply() }

    /** Ketinggian manual (m): nilai tetap bila "dari server" mati, cadangan bila lookup gagal. bila Elevation API gagal / tidak ada koneksi. */
    var altitudeManual: Float
        get() = pref.getFloat(ALTITUDE_MANUAL, 0f)
        set(value) { pref.edit().putFloat(ALTITUDE_MANUAL, value).apply() }

    /**
     * Seperti [update] tapi langsung di thread pemanggil (apply() sudah menjaga urutan di memori).
     * Dipakai simulasi rute supaya tulisan terakhir tidak bisa "menyalip" tombol Stop.
     */
    fun updateNow(
        start: Boolean, la: Double, ln: Double,
        speedMs: Float = 0f, bearing: Float = -1f, altitude: Float? = null
    ) {
        // satu apply() untuk semuanya: bridge membaca posisi + kecepatan + arah + ketinggian yang konsisten
        val e = pref.edit()
            .putFloat(LATITUDE, la.toFloat())
            .putFloat(LONGITUDE, ln.toFloat())
            .putBoolean(START, start)
            .putFloat(SPEED_MS, speedMs.coerceAtLeast(0f))
            .putFloat(BEARING, bearing)          // negatif = tanpa arah
        if (altitude != null) e.putFloat(ALTITUDE, altitude)
        e.apply()
    }

    fun update(start:Boolean, la: Double, ln: Double) {
        runInBackground {
            val prefEditor = pref.edit()
            prefEditor.putFloat(LATITUDE, la.toFloat())
            prefEditor.putFloat(LONGITUDE, ln.toFloat())
            prefEditor.putBoolean(START, start)
            prefEditor.putFloat(SPEED_MS, 0f)    // titik tunggal = diam
            prefEditor.putFloat(BEARING, -1f)
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