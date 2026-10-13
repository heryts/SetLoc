package com.heryts.setloc.route

import android.os.SystemClock
import com.google.android.gms.maps.model.LatLng
import com.heryts.setloc.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Menjalankan "perjalanan" menyusuri rute: tiap ~1 detik menulis posisi berikutnya ke pref app.
 * Daemon bridge.sh menyalin pref -> gpsz_cfg, lalu hook membacanya. Modul Zygisk tidak berubah.
 */
object RouteRunner {

    data class State(
        val running: Boolean = false,
        val finished: Boolean = false,
        val position: LatLng? = null,
        val progressPercent: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value.running

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var job: Job? = null
    private var active = false   // dijaga lock: hanya bila true, tick boleh menulis ke pref

    private const val TICK_MS = 1000L

    fun start(points: List<LatLng>, speedKmh: Float, waitAtStartMin: Float) {
        if (points.size < 2) return
        synchronized(lock) {
            job?.cancel()
            active = true
            // titik awal ditulis sinkron: pref langsung start=true di posisi awal
            PrefManager.updateNow(true, points.first().latitude, points.first().longitude)
            _state.value = State(running = true, position = points.first())
            job = scope.launch { run(points, speedKmh.coerceAtLeast(0.5f), waitAtStartMin.coerceAtLeast(0f)) }
        }
    }

    /** Hentikan perjalanan. Tidak mengubah status Start/Stop di pref; pemanggil yang mengatur. */
    fun stop() {
        synchronized(lock) {
            active = false
            job?.cancel()
            job = null
            _state.value = State()
        }
    }

    private suspend fun run(points: List<LatLng>, speedKmh: Float, waitAtStartMin: Float) {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + meters(points[i - 1], points[i])
        val total = cum.last()
        val speedMs = speedKmh / 3.6

        // diam dulu di titik awal
        var waitMs = (waitAtStartMin * 60_000f).toLong()
        while (waitMs > 0 && currentCoroutineContext().isActive) {
            val step = min(TICK_MS, waitMs)
            delay(step)
            waitMs -= step
        }

        var travelled = 0.0
        var seg = 1
        var last = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive && travelled < total) {
            delay(TICK_MS)
            val now = SystemClock.elapsedRealtime()
            travelled = min(total, travelled + speedMs * (now - last) / 1000.0)
            last = now

            while (seg < cum.size - 1 && cum[seg] < travelled) seg++
            val segLen = cum[seg] - cum[seg - 1]
            val f = if (segLen <= 0.0) 1.0 else ((travelled - cum[seg - 1]) / segLen).coerceIn(0.0, 1.0)
            val a = points[seg - 1]
            val b = points[seg]
            val pos = LatLng(
                a.latitude + (b.latitude - a.latitude) * f,
                a.longitude + (b.longitude - a.longitude) * f
            )
            val pct = if (total > 0) (travelled / total * 100).toInt() else 100
            if (!write(pos, pct)) return
        }
        finish(points.last())
    }

    private fun write(p: LatLng, pct: Int): Boolean = synchronized(lock) {
        if (!active) return false
        PrefManager.updateNow(true, p.latitude, p.longitude)
        _state.value = State(running = true, position = p, progressPercent = pct)
        true
    }

    private fun finish(end: LatLng) = synchronized(lock) {
        if (active) {
            active = false
            job = null
            PrefManager.updateNow(true, end.latitude, end.longitude)
            // posisi tetap di tujuan dan spoof tetap aktif sampai user menekan Stop
            _state.value = State(running = false, finished = true, position = end, progressPercent = 100)
        }
    }

    fun meters(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    fun routeLength(points: List<LatLng>): Double {
        var t = 0.0
        for (i in 1 until points.size) t += meters(points[i - 1], points[i])
        return t
    }
}
