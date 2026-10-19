package com.heryts.setloc.utils

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.heryts.setloc.BuildConfig
import com.heryts.setloc.gsApp
import java.io.File
import java.io.Writer
import java.util.Locale
import java.util.concurrent.Executors
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

object PrefManager {
    private const val START = "start"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_settings"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val ROUTE_TRAFFIC = "route_traffic_aware"
    private const val ROUTE_SPEED = "route_speed"
    private const val ROUTE_MODE = "route_mode"
    private const val ROUTE_WAIT = "route_wait_start"
    private const val ROUTE_SPEED_VAR = "route_speed_var"
    private const val SPEED_UNIT_MPH = "speed_unit_mph"
    private const val SPEED_VAR_UNIT = "speed_var_unit"
    private const val ROUTE_WAIT_END = "route_wait_end"
    private const val SPEED_MS = "speed_ms"
    private const val BEARING = "bearing"
    private const val ALTITUDE = "altitude"
    private const val ALTITUDE_MANUAL = "altitude_manual"
    private const val ALTITUDE_VAR = "altitude_var"
    private const val RANDOM_RADIUS = "random_radius"
    private const val ROUTE_PLAY = "route_play"
    private const val ROUTE_T0 = "route_t0"
    private const val ROUTE_TICK = "route_tick"
    private const val ROUTE_N = "route_n"
    private const val ROUTE_HOLD_END = "route_hold_end"
    private const val ROUTE_BC = "route_bc"
    private const val ROUTE_ACTIVE = "route_active"
    private const val ROUTE_TLW = "route_tlw"
    private const val LATITUDE_D = "latitude_d"
    private const val LONGITUDE_D = "longitude_d"
    private const val ALTITUDE_SERVER = "altitude_from_server"

    private val pref: SharedPreferences by lazy {
        gsApp.getSharedPreferences("${BuildConfig.APPLICATION_ID}_prefs", Context.MODE_PRIVATE)
    }

    val isStarted: Boolean
        get() = pref.getBoolean(START, false)

    val getLat: Double
        get() = precise(LATITUDE_D, pref.getFloat(LATITUDE, 40.7128F).toDouble())

    val getLng: Double
        get() = precise(LONGITUDE_D, pref.getFloat(LONGITUDE, -74.0060F).toDouble())

    private fun precise(key: String, coarse: Double): Double {
        val d = pref.getString(key, null)?.toDoubleOrNull() ?: return coarse
        return if (kotlin.math.abs(d - coarse) < 1e-4) d else coarse
    }

    var isRandomPosition: Boolean
        get() = pref.getBoolean(RANDOM_POSITION, true)
        set(value) = pref.edit { putBoolean(RANDOM_POSITION, value) }

    var accuracy: String?
        get() = pref.getString(ACCURACY_SETTING, "10")
        set(value) = pref.edit { putString(ACCURACY_SETTING, value) }

    var mapType: Int
        get() = pref.getInt(MAP_TYPE, 1)
        set(value) = pref.edit { putInt(MAP_TYPE, value) }

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit { putInt(DARK_THEME, value) }

    var trafficAware: Boolean
        get() = pref.getBoolean(ROUTE_TRAFFIC, true)
        set(value) = pref.edit { putBoolean(ROUTE_TRAFFIC, value) }

    var routeMode: Int
        get() = pref.getInt(ROUTE_MODE, 3)
        set(value) = pref.edit { putInt(ROUTE_MODE, value) }

    var routeSpeed: Float
        get() = pref.getFloat(ROUTE_SPEED, -1f)
        set(value) = pref.edit { putFloat(ROUTE_SPEED, value) }

    var speedUnitMph: Boolean
        get() = pref.getBoolean(SPEED_UNIT_MPH, false)
        set(value) = pref.edit { putBoolean(SPEED_UNIT_MPH, value) }

    var routeSpeedVarRaw: Float
        get() = pref.getFloat(ROUTE_SPEED_VAR, -1f)
        set(value) = pref.edit { putFloat(ROUTE_SPEED_VAR, value) }

    var speedVarUnit: Int
        get() = pref.getInt(SPEED_VAR_UNIT, 0)
        set(value) = pref.edit { putInt(SPEED_VAR_UNIT, value) }

    fun routeSpeedVarFor(speedKmh: Float): Float {
        val raw = routeSpeedVarRaw
        if (raw < 0f) return -1f
        return when (speedVarUnit) {
            0 -> speedKmh * raw / 100f
            2 -> raw * 1.609344f
            else -> raw
        }
    }

    var routeWaitStart: Float
        get() = pref.getFloat(ROUTE_WAIT, 0f)
        set(value) = pref.edit { putFloat(ROUTE_WAIT, value) }

    var routeWaitEnd: Float
        get() = pref.getFloat(ROUTE_WAIT_END, -1f)
        set(value) = pref.edit { putFloat(ROUTE_WAIT_END, value) }

    var altitudeM: Float
        get() = pref.getFloat(ALTITUDE, 0f)
        set(value) = pref.edit { putFloat(ALTITUDE, value) }

    var routeActive: Boolean
        get() = pref.getBoolean(ROUTE_ACTIVE, false)
        set(value) = pref.edit { putBoolean(ROUTE_ACTIVE, value) }

    var routePlay: Boolean
        get() = pref.getBoolean(ROUTE_PLAY, false)
        set(value) = pref.edit { putBoolean(ROUTE_PLAY, value) }

    fun writeRouteTimeline(write: (Writer) -> Unit): Boolean = try {
        val dir = gsApp.filesDir
        val tmp = File(dir, "route_timeline.tmp")
        tmp.bufferedWriter(Charsets.US_ASCII).use(write)
        tmp.renameTo(File(dir, "route_timeline.txt"))
    } catch (e: Exception) {
        false
    }

    fun beginPlayback(t0Centis: Long, tickMs: Long, n: Int, holdEndMs: Long, lineWidth: Int) {
        val bc = try {
            Settings.Global.getInt(gsApp.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            -1
        }
        pref.edit {
            putLong(ROUTE_T0, t0Centis)
            putLong(ROUTE_TICK, tickMs)
            putInt(ROUTE_N, n)
            putLong(ROUTE_HOLD_END, holdEndMs)
            putInt(ROUTE_BC, bc)
            putInt(ROUTE_TLW, lineWidth)
        }
    }

    var randomRadius: Float
        get() = pref.getFloat(RANDOM_RADIUS, 8f)
        set(value) = pref.edit { putFloat(RANDOM_RADIUS, value.coerceIn(0f, 100f)) }

    var altitudeVariation: Float
        get() = pref.getFloat(ALTITUDE_VAR, 0f)
        set(value) = pref.edit { putFloat(ALTITUDE_VAR, value.coerceAtLeast(0f)) }

    var altitudeFromServer: Boolean
        get() = pref.getBoolean(ALTITUDE_SERVER, true)
        set(value) = pref.edit { putBoolean(ALTITUDE_SERVER, value) }

    var altitudeManual: Float
        get() = pref.getFloat(ALTITUDE_MANUAL, 0f)
        set(value) = pref.edit { putFloat(ALTITUDE_MANUAL, value) }

    fun updateNow(
        start: Boolean, la: Double, ln: Double,
        speedMs: Float = 0f, bearing: Float = -1f, altitude: Float? = null,
        routeActive: Boolean = false, routePlay: Boolean = false
    ) {
        val e = pref.edit()
            .putFloat(LATITUDE, la.toFloat())
            .putFloat(LONGITUDE, ln.toFloat())
            .putString(LATITUDE_D, "%.7f".format(Locale.US, la))
            .putString(LONGITUDE_D, "%.7f".format(Locale.US, ln))
            .putBoolean(START, start)
            .putFloat(SPEED_MS, speedMs.coerceAtLeast(0f))
            .putFloat(BEARING, bearing)
            .putBoolean(ROUTE_ACTIVE, routeActive)
            .putBoolean(ROUTE_PLAY, routePlay)
        if (altitude != null) e.putFloat(ALTITUDE, altitude)
        save(e, start, la, ln)
    }

    private val io = Executors.newSingleThreadExecutor { Thread(it, "pref-io").apply { isDaemon = true } }

    fun update(start: Boolean, la: Double, ln: Double) {
        io.execute { updateNow(start, la, ln) }
    }

    private fun save(e: SharedPreferences.Editor, start: Boolean, la: Double, ln: Double) {
        if (start) {
            e.apply()
        } else {
            e.commit()
            pushOff(la, ln)
        }
    }

    private fun pushOff(la: Double, ln: Double) {
        try {
            val acc = accuracy?.toFloatOrNull() ?: 10f
            val cfg = "enabled=0;lat=%.6f;lng=%.6f;acc=%s;random=0".format(Locale.US, la, ln, acc)
            Settings.Global.putString(gsApp.contentResolver, "gpsz_cfg", cfg)
        } catch (_: Throwable) {
        }
    }
}
