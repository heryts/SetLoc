package com.heryts.setloc.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.heryts.setloc.BuildConfig
import com.heryts.setloc.gsApp
import java.io.File
import java.io.Writer
import java.util.concurrent.Executors
import kotlin.math.abs
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

object PrefManager {
    fun tag(logical: String): String? = CodecNative.tag(logical)

    private fun enc(plain: String): String? = CodecNative.enc(plain)

    fun dec(hex: String?): String? = if (hex == null) null else CodecNative.dec(hex)

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
    private const val ALTITUDE_MANUAL = "altitude_manual"
    private const val ALTITUDE_SERVER = "altitude_from_server"

    private val LEGACY_STATE = listOf(
        "start" to "start",
        "random_position" to "random",
        "accuracy_settings" to "acc",
        "speed_ms" to "spd",
        "bearing" to "brg",
        "altitude" to "alt",
        "altitude_var" to "altv",
        "random_radius" to "rr",
        "route_active" to "route_active",
        "route_play" to "route_play",
        "route_t0" to "route_t0",
        "route_tick" to "route_tick",
        "route_n" to "route_n",
        "route_hold_end" to "route_hold_end",
        "route_bc" to "route_bc",
        "route_tlw" to "route_tlw"
    )

    private val pref: SharedPreferences by lazy {
        gsApp.getSharedPreferences("${BuildConfig.APPLICATION_ID}_prefs", Context.MODE_PRIVATE)
    }

    private val stateFile: String by lazy {
        val f = File(gsApp.filesDir, PrefNative.STATE_FILE)
        if (!f.exists()) migrateLegacy(f.path)
        f.path
    }

    private fun legacyCoord(coarseKey: String, preciseKey: String): Double? {
        val coarse = (pref.all[coarseKey] as? Float)?.toDouble() ?: return null
        val fine = (pref.all[preciseKey] as? String)?.toDoubleOrNull()
        return if (fine != null && abs(fine - coarse) < 1e-4) fine else coarse
    }

    private fun migrateLegacy(path: String) {
        val lines = ArrayList<String>()
        legacyCoord("latitude", "latitude_d")?.let { lines.add("lat:$it") }
        legacyCoord("longitude", "longitude_d")?.let { lines.add("lng:$it") }
        for ((old, new) in LEGACY_STATE) {
            pref.all[old]?.let { lines.add("$new:$it") }
        }
        if (lines.isEmpty()) return
        val moved = PrefNative.statePatch(path, lines.joinToString("\n")) ||
            lines.count { PrefNative.statePatch(path, it) } > 0
        if (!moved) return
        pref.edit {
            remove("latitude").remove("longitude").remove("latitude_d").remove("longitude_d")
            LEGACY_STATE.forEach { remove(it.first) }
        }
    }

    private fun read(key: String): String? = PrefNative.stateGet(stateFile, key)

    private fun write(patch: String): Boolean = PrefNative.statePatch(stateFile, patch)

    private fun flag(key: String, default: Boolean): Boolean = read(key)?.let { it == "1" } ?: default

    private fun number(key: String, default: Double): Double = read(key)?.toDoubleOrNull() ?: default

    private fun bit(value: Boolean) = if (value) 1 else 0

    val isStarted: Boolean
        get() = flag("start", false)

    val getLat: Double
        get() = number("lat", -7.7829)

    val getLng: Double
        get() = number("lng", 110.3671)

    var isRandomPosition: Boolean
        get() = flag("random", true)
        set(value) {
            write("random:${bit(value)}")
        }

    var accuracy: String?
        get() = read("acc") ?: "10"
        set(value) {
            if (value != null) write("acc:$value")
        }

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

    fun routeSpeedVarFor(speedKmh: Float): Float = PrefNative.speedVar(speedVarUnit, speedKmh, routeSpeedVarRaw)

    var routeWaitStart: Float
        get() = pref.getFloat(ROUTE_WAIT, 0f)
        set(value) = pref.edit { putFloat(ROUTE_WAIT, value) }

    var routeWaitEnd: Float
        get() = pref.getFloat(ROUTE_WAIT_END, -1f)
        set(value) = pref.edit { putFloat(ROUTE_WAIT_END, value) }

    var altitudeM: Float
        get() = number("alt", 0.0).toFloat()
        set(value) {
            write("alt:$value")
        }

    var routeActive: Boolean
        get() = flag("route_active", false)
        set(value) {
            write("route_active:${bit(value)}")
        }

    var routePlay: Boolean
        get() = flag("route_play", false)
        set(value) {
            write("route_play:${bit(value)}")
        }

    fun writeRouteTimeline(write: (Writer) -> Unit): Boolean = try {
        val dir = gsApp.filesDir
        val tmp = File(dir, "route_timeline.tmp")
        tmp.bufferedWriter(Charsets.US_ASCII).use(write)
        tmp.renameTo(File(dir, "route_timeline.txt"))
    } catch (_: Exception) {
        false
    }

    private fun bootCount() = try {
        Settings.Global.getInt(gsApp.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    private fun routeState(): Int = PrefNative.routeState(stateFile, bootCount(), SystemClock.elapsedRealtime())

    fun routeLive(): Boolean = routeState() == PrefNative.ROUTE_LIVE

    fun routeEndedOff(): Boolean = routeState() == PrefNative.ROUTE_ENDED_OFF

    fun beginPlayback(t0Centis: Long, tickMs: Long, n: Int, holdEndMs: Long, lineWidth: Int) {
        write(
            "route_t0:$t0Centis\nroute_tick:$tickMs\nroute_n:$n\nroute_hold_end:$holdEndMs\n" +
                "route_bc:${bootCount()}\nroute_tlw:$lineWidth"
        )
    }

    private fun globalFlag(logical: String): String? = try {
        val key = tag(logical)
        if (key == null) null else dec(Settings.Global.getString(gsApp.contentResolver, key))
    } catch (_: Throwable) {
        null
    }

    private fun putGlobalFlag(logical: String, value: Boolean) {
        try {
            val key = tag(logical) ?: return
            val data = enc(if (value) "1" else "0") ?: return
            Settings.Global.putString(gsApp.contentResolver, key, data)
        } catch (_: Throwable) {
        }
    }

    var hideNetwork: Boolean
        get() = globalFlag("net") != "0"
        set(value) = putGlobalFlag("net", value)

    var hideBluetooth: Boolean
        get() = globalFlag("bt") == "1"
        set(value) = putGlobalFlag("bt", value)

    var randomRadius: Float
        get() = number("rr", 8.0).toFloat()
        set(value) {
            write("rr:${value.coerceIn(0f, 100f)}")
        }

    var altitudeVariation: Float
        get() = number("altv", 0.0).toFloat()
        set(value) {
            write("altv:${value.coerceAtLeast(0f)}")
        }

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
        val patch = StringBuilder()
            .append("lat:").append(la).append("\nlng:").append(ln)
            .append("\nstart:").append(bit(start))
            .append("\nspd:").append(speedMs.coerceAtLeast(0f))
            .append("\nbrg:").append(bearing)
            .append("\nroute_active:").append(bit(routeActive))
            .append("\nroute_play:").append(bit(routePlay))
        if (altitude != null) patch.append("\nalt:").append(altitude)
        if (!routePlay) patch.append("\n-route_t0\n-route_n\n-route_tlw")
        write(patch.toString())
        if (!start) pushOff(la, ln)
    }

    private val io = Executors.newSingleThreadExecutor { Thread(it, "pref-io").apply { isDaemon = true } }

    fun update(start: Boolean, la: Double, ln: Double) {
        io.execute { updateNow(start, la, ln) }
    }

    private fun pushOff(la: Double, ln: Double) {
        try {
            val acc = accuracy?.toFloatOrNull() ?: 10f
            val cfg = PrefNative.cfgOff(la, ln, acc) ?: return
            val key = tag("cfg") ?: return
            val data = enc(cfg) ?: return
            Settings.Global.putString(gsApp.contentResolver, key, data)
        } catch (_: Throwable) {
        }
    }
}
