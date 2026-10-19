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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object RouteRunner {
    data class State(
        val running: Boolean = false,
        val finished: Boolean = false,
        val position: LatLng? = null,
        val progressPercent: Int = 0,
        val spoofOff: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value.running

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var job: Job? = null
    private var active = false

    private const val TICK_MS = 500L
    private const val PREF_EVERY = 10
    const val TIMELINE_LINE_WIDTH = 48
    private const val SPEED_VARIATION = 0.08
    private const val SPEED_PULL = 0.1
    private const val SPEED_STEP = 0.22
    private const val MIN_SPEED_MS = 0.3
    private const val ACCEL_WALK = 1.0
    private const val ACCEL_VEHICLE = 1.8
    private const val BRAKE_FACTOR = 1.4
    private const val WALK_MAX_MS = 3.0
    private const val CORNER_MIN_SEG_M = 2.0
    private const val CORNER_FLOOR_MS = 1.5

    @Volatile var elevations: FloatArray? = null

    private var lastBearing = -1f
    private var prefTicks = 0
    private var thinWrites = true

    fun start(
        points: List<LatLng>, speedKmh: Float, waitAtStartMin: Float, waitAtEndMin: Float, speedVarKmh: Float
    ) {
        if (points.size < 2) return
        synchronized(lock) {
            job?.cancel()
            active = true
            prefTicks = 0
            lastBearing = bearing(points[0], points[1])
            PrefManager.updateNow(
                true, points.first().latitude, points.first().longitude,
                0f, lastBearing, altitudeAt(0.0), routeActive = true
            )
            _state.value = State(running = true, position = points.first())
            job = scope.launch {
                run(
                    points, speedKmh.coerceAtLeast(0.5f), waitAtStartMin.coerceAtLeast(0f),
                    waitAtEndMin, speedVarKmh
                )
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            active = false
            job?.cancel()
            job = null
            _state.value = State()
        }
    }

    internal class Tick(val lat: Double, val lng: Double, val spd: Float, val brg: Float, val alt: Float, val pct: Int)

    private fun cornerCaps(points: List<LatLng>, cum: DoubleArray, speedMs: Double, brake: Double): DoubleArray {
        val n = points.size
        val cap = DoubleArray(n) { Double.MAX_VALUE }
        cap[n - 1] = 0.0
        for (i in 1 until n - 1) {
            if (cum[i] - cum[i - 1] < CORNER_MIN_SEG_M || cum[i + 1] - cum[i] < CORNER_MIN_SEG_M) continue
            var turn = abs(bearing(points[i], points[i + 1]) - bearing(points[i - 1], points[i])).toDouble()
            if (turn > 180.0) turn = 360.0 - turn
            cap[i] = max(min(speedMs, CORNER_FLOOR_MS), speedMs * sqrt(1.0 - turn / 180.0))
        }
        for (i in n - 2 downTo 0) {
            val reach = sqrt(cap[i + 1] * cap[i + 1] + 2.0 * brake * (cum[i + 1] - cum[i]))
            if (reach < cap[i]) cap[i] = reach
        }
        return cap
    }

    internal fun buildTimeline(points: List<LatLng>, speedKmh: Float, speedVarKmh: Float): List<Tick> {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + meters(points[i - 1], points[i])
        val total = cum.last()
        val speedMs = speedKmh / 3.6
        val varMs = if (speedVarKmh < 0f) speedMs * SPEED_VARIATION else speedVarKmh / 3.6
        val accel = if (speedMs < WALK_MAX_MS) ACCEL_WALK else ACCEL_VEHICLE
        val brake = accel * BRAKE_FACTOR
        val caps = cornerCaps(points, cum, speedMs, brake)
        val dt = TICK_MS / 1000.0
        val rnd = java.util.Random()
        val out = ArrayList<Tick>()
        var brg = bearing(points[0], points[1])
        out.add(Tick(points[0].latitude, points[0].longitude, 0f, brg, altitudeAt(0.0), 0))
        var travelled = 0.0
        var seg = 1
        var dev = 0.0
        var cur = 0.0
        while (travelled < total) {
            dev = dev * (1.0 - SPEED_PULL) + rnd.nextGaussian() * varMs * SPEED_STEP
            dev = dev.coerceIn(-varMs, varMs)
            val ahead = max(0.0, cum[seg] - travelled - cur * dt)
            val ceiling = sqrt(caps[seg] * caps[seg] + 2.0 * brake * ahead)
            val prev = cur
            cur = (speedMs + dev).coerceAtMost(prev + accel * dt).coerceAtMost(ceiling)
                .coerceAtLeast(MIN_SPEED_MS).coerceAtLeast(prev - 2.0 * brake * dt)
            val before = travelled
            travelled = min(total, travelled + cur * dt)
            val shown = ((travelled - before) / dt).toFloat()
            while (seg < cum.size - 1 && cum[seg] < travelled) seg++
            val segLen = cum[seg] - cum[seg - 1]
            val f = if (segLen <= 0.0) 1.0 else ((travelled - cum[seg - 1]) / segLen).coerceIn(0.0, 1.0)
            val a = points[seg - 1]
            val b = points[seg]
            if (segLen > 0.5) brg = bearing(a, b)
            val frac = if (total > 0) travelled / total else 1.0
            out.add(
                Tick(
                    a.latitude + (b.latitude - a.latitude) * f,
                    a.longitude + (b.longitude - a.longitude) * f,
                    shown, brg, altitudeAt(frac), (frac * 100).toInt()
                )
            )
        }
        val end = points.last()
        out.add(Tick(end.latitude, end.longitude, 0f, brg, altitudeAt(1.0), 100))
        return out
    }

    private fun writeTimeline(ticks: List<Tick>, w: java.io.Writer): Long {
        val sb = StringBuilder(TIMELINE_LINE_WIDTH + 8)
        val fmt = java.util.Formatter(sb, java.util.Locale.US)
        var chars = 0L
        for (t in ticks) {
            sb.setLength(0)
            fmt.format("%11.6f %11.6f %7.3f %6.2f %8.2f\n", t.lat, t.lng, t.spd, t.brg, t.alt)
            chars += sb.length
            w.append(sb)
        }
        return chars
    }

    private suspend fun run(
        points: List<LatLng>, speedKmh: Float, waitAtStartMin: Float, waitAtEndMin: Float, speedVarKmh: Float
    ) {
        val ticks = buildTimeline(points, speedKmh, speedVarKmh)
        val n = ticks.size
        val waitMs = (waitAtStartMin * 60_000f).toLong()
        val autoStop = waitAtEndMin < 0f
        val endWaitMs = if (autoStop) 0L else (waitAtEndMin * 60_000f).toLong()
        val t0 = SystemClock.elapsedRealtime() + waitMs

        var chars = 0L
        val written = PrefManager.writeRouteTimeline { chars = writeTimeline(ticks, it) }
        synchronized(lock) { thinWrites = written }
        val lineWidth = if (chars == n.toLong() * TIMELINE_LINE_WIDTH) TIMELINE_LINE_WIDTH else 0
        if (written) {
            val first = ticks[0]
            synchronized(lock) {
                if (!active) return
                PrefManager.beginPlayback(t0 / 10, TICK_MS, n, if (autoStop) 1L else endWaitMs, lineWidth)
                PrefManager.updateNow(
                    true, first.lat, first.lng, 0f, lastBearing, first.alt, routeActive = true, routePlay = true
                )
            }
        }

        while (currentCoroutineContext().isActive) {
            val rem = t0 - SystemClock.elapsedRealtime()
            if (rem <= 0) break
            delay(rem)
        }

        var shown = -1
        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val k = ((now - t0) / TICK_MS).toInt().coerceAtLeast(0)
            if (k >= n - 1) break
            if (k != shown) {
                shown = k
                val t = ticks[k]
                if (!write(LatLng(t.lat, t.lng), t.pct, t.spd, t.brg, t.alt)) return
            }
            val nextAt = t0 + (k + 1) * TICK_MS
            delay((nextAt - SystemClock.elapsedRealtime()).coerceIn(20L, TICK_MS))
        }
        if (!currentCoroutineContext().isActive) return

        val last = ticks[n - 1]
        if (!arrive(LatLng(last.lat, last.lng), last.alt)) return

        if (autoStop) {
            finish(points.last(), spoofOff = true)
        } else if (endWaitMs > 0) {
            val endAt = t0 + (n - 1) * TICK_MS + endWaitMs
            while (currentCoroutineContext().isActive) {
                val rem = endAt - SystemClock.elapsedRealtime()
                if (rem <= 0) break
                delay(rem)
            }
            finish(points.last(), spoofOff = true)
        } else {
            finish(points.last(), spoofOff = false)
        }
    }

    private fun write(p: LatLng, pct: Int, speedMs: Float, brg: Float, alt: Float): Boolean = synchronized(lock) {
        if (!active) return false
        if (brg >= 0f) lastBearing = brg
        if (!thinWrites || prefTicks++ % PREF_EVERY == 0) {
            PrefManager.updateNow(true, p.latitude, p.longitude, speedMs, lastBearing, alt, routeActive = true, routePlay = true)
        }
        _state.value = State(running = true, position = p, progressPercent = pct)
        true
    }

    private fun arrive(end: LatLng, alt: Float): Boolean = synchronized(lock) {
        if (!active) return false
        PrefManager.updateNow(true, end.latitude, end.longitude, 0f, lastBearing, alt, routePlay = true)
        true
    }

    private fun altitudeAt(fraction: Double): Float {
        val e = elevations
        if (e == null || e.isEmpty()) return PrefManager.altitudeManual
        if (e.size == 1) return e[0]
        val x = fraction.coerceIn(0.0, 1.0) * (e.size - 1)
        val i = x.toInt().coerceAtMost(e.size - 2)
        val f = (x - i).toFloat()
        return e[i] + (e[i + 1] - e[i]) * f
    }

    private fun bearing(a: LatLng, b: LatLng): Float {
        val la1 = Math.toRadians(a.latitude)
        val la2 = Math.toRadians(b.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        val deg = Math.toDegrees(atan2(y, x))
        return (((deg % 360.0) + 360.0) % 360.0).toFloat()
    }

    private fun finish(end: LatLng, spoofOff: Boolean) = synchronized(lock) {
        if (active) {
            active = false
            job = null
            PrefManager.updateNow(!spoofOff, end.latitude, end.longitude, 0f, lastBearing, altitudeAt(1.0))
            _state.value = State(
                running = false, finished = true, position = end,
                progressPercent = 100, spoofOff = spoofOff
            )
        }
    }

    private const val EARTH_R = 6371000.0
    private const val NEAR_RAD = 0.01

    fun meters(a: LatLng, b: LatLng): Double {
        val r = EARTH_R
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        if (abs(dLat) < NEAR_RAD && abs(dLng) < NEAR_RAD) {
            val x = dLng * cos(Math.toRadians((a.latitude + b.latitude) * 0.5))
            return r * sqrt(dLat * dLat + x * x)
        }
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
