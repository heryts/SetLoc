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
import kotlin.math.sin
import kotlin.math.sqrt

internal interface RouteSink {
    val altitudeManual: Float
    fun updateNow(
        start: Boolean, la: Double, ln: Double,
        speedMs: Float = 0f, bearing: Float = -1f, altitude: Float? = null,
        routeActive: Boolean = false, routePlay: Boolean = false
    )
    fun writeRouteTimeline(write: (java.io.Writer) -> Unit): Boolean
    fun beginPlayback(t0Centis: Long, tickMs: Long, n: Int, holdEndMs: Long, lineWidth: Int)
}

private object PrefSink : RouteSink {
    override val altitudeManual: Float get() = PrefManager.altitudeManual
    override fun updateNow(
        start: Boolean, la: Double, ln: Double, speedMs: Float, bearing: Float, altitude: Float?,
        routeActive: Boolean, routePlay: Boolean
    ) = PrefManager.updateNow(start, la, ln, speedMs, bearing, altitude, routeActive, routePlay)
    override fun writeRouteTimeline(write: (java.io.Writer) -> Unit): Boolean = PrefManager.writeRouteTimeline(write)
    override fun beginPlayback(t0Centis: Long, tickMs: Long, n: Int, holdEndMs: Long, lineWidth: Int) =
        PrefManager.beginPlayback(t0Centis, tickMs, n, holdEndMs, lineWidth)
}

object RouteRunner {
    @Volatile internal var sink: RouteSink = PrefSink
    @Volatile internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

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
    private val fileLock = Any()
    @Volatile private var gen = 0

    private const val TICK_MS = 500L
    private const val PREF_EVERY = 10
    const val TIMELINE_LINE_WIDTH = 48
    const val MAX_TICKS = 250_000

    @Volatile var elevations: FloatArray? = null

    private var lastBearing = -1f
    private var prefTicks = 0
    private var thinWrites = true

    fun start(
        points: List<LatLng>, speedKmh: Float, waitAtStartMin: Float, waitAtEndMin: Float, speedVarKmh: Float
    ) {
        if (points.size < 2 || tooLong(points, speedKmh)) return
        synchronized(lock) {
            job?.cancel()
            val g = ++gen
            active = true
            prefTicks = 0
            lastBearing = bearing(points[0], points[1])
            sink.updateNow(
                true, points.first().latitude, points.first().longitude,
                0f, lastBearing, altitudeAt(0.0), routeActive = true
            )
            _state.value = State(running = true, position = points.first())
            job = scope.launch {
                run(
                    g, points, speedKmh.coerceAtLeast(0.5f), waitAtStartMin.coerceAtLeast(0f),
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

    @Volatile internal var builder: (List<LatLng>, Float, Float) -> List<Tick>? = { points, speedKmh, speedVarKmh ->
        val e = elevations
        val manual = if (e == null || e.isEmpty()) sink.altitudeManual else 0f
        RouteNative.build(points, speedKmh, speedVarKmh, e, manual)
    }

    @Volatile internal var writer: (List<Tick>, java.io.Writer) -> Long? = { ticks, w -> RouteNative.write(ticks, w) }

    internal fun buildTimeline(points: List<LatLng>, speedKmh: Float, speedVarKmh: Float): List<Tick>? =
        builder(points, speedKmh, speedVarKmh)

    private suspend fun run(
        g: Int, points: List<LatLng>, speedKmh: Float, waitAtStartMin: Float, waitAtEndMin: Float, speedVarKmh: Float
    ) {
        val ticks = buildTimeline(points, speedKmh, speedVarKmh)
        if (g != gen) return
        if (ticks == null) {
            abort(g)
            return
        }
        val n = ticks.size
        val waitMs = (waitAtStartMin * 60_000f).toLong()
        val autoStop = waitAtEndMin < 0f
        val endWaitMs = if (autoStop) 0L else (waitAtEndMin * 60_000f).toLong()

        var chars = 0L
        val written = synchronized(fileLock) {
            if (g != gen) return
            sink.writeRouteTimeline { chars = writer(ticks, it) ?: -1L }
        }
        if (chars < 0L) {
            abort(g)
            return
        }
        val t0 = clock() + waitMs
        synchronized(lock) {
            if (g != gen) return
            thinWrites = written
        }
        val lineWidth = if (chars == n.toLong() * TIMELINE_LINE_WIDTH) TIMELINE_LINE_WIDTH else 0
        if (written) {
            val first = ticks[0]
            synchronized(lock) {
                if (!active || g != gen) return
                sink.beginPlayback(t0 / 10, TICK_MS, n, if (autoStop) 1L else endWaitMs, lineWidth)
                sink.updateNow(
                    true, first.lat, first.lng, 0f, lastBearing, first.alt, routeActive = true, routePlay = true
                )
            }
        }

        while (currentCoroutineContext().isActive) {
            val rem = t0 - clock()
            if (rem <= 0) break
            delay(rem)
        }

        var shown = -1
        while (currentCoroutineContext().isActive) {
            val now = clock()
            val k = ((now - t0) / TICK_MS).toInt().coerceAtLeast(0)
            if (k >= n - 1) break
            if (k != shown) {
                shown = k
                val t = ticks[k]
                if (!write(g, LatLng(t.lat, t.lng), t.pct, t.spd, t.brg, t.alt)) return
            }
            val nextAt = t0 + (k + 1) * TICK_MS
            delay((nextAt - clock()).coerceIn(20L, TICK_MS))
        }
        if (!currentCoroutineContext().isActive) return

        val last = ticks[n - 1]
        if (!arrive(g, LatLng(last.lat, last.lng), last.alt)) return

        if (autoStop) {
            finish(g, points.last(), spoofOff = true)
        } else if (endWaitMs > 0) {
            val endAt = t0 + (n - 1) * TICK_MS + endWaitMs
            while (currentCoroutineContext().isActive) {
                val rem = endAt - clock()
                if (rem <= 0) break
                delay(rem)
            }
            finish(g, points.last(), spoofOff = true)
        } else {
            finish(g, points.last(), spoofOff = false)
        }
    }

    private fun abort(g: Int) = synchronized(lock) {
        if (active && g == gen) {
            active = false
            job = null
            _state.value = State()
        }
    }

    private fun write(g: Int, p: LatLng, pct: Int, speedMs: Float, brg: Float, alt: Float): Boolean = synchronized(lock) {
        if (!active || g != gen) return false
        if (brg >= 0f) lastBearing = brg
        if (!thinWrites || prefTicks++ % PREF_EVERY == 0) {
            sink.updateNow(true, p.latitude, p.longitude, speedMs, lastBearing, alt, routeActive = true, routePlay = true)
        }
        _state.value = State(running = true, position = p, progressPercent = pct)
        true
    }

    private fun arrive(g: Int, end: LatLng, alt: Float): Boolean = synchronized(lock) {
        if (!active || g != gen) return false
        sink.updateNow(true, end.latitude, end.longitude, 0f, lastBearing, alt, routePlay = true)
        true
    }

    private fun altitudeAt(fraction: Double): Float {
        val e = elevations
        if (e == null || e.isEmpty()) return sink.altitudeManual
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

    private fun finish(g: Int, end: LatLng, spoofOff: Boolean) = synchronized(lock) {
        if (active && g == gen) {
            active = false
            job = null
            sink.updateNow(!spoofOff, end.latitude, end.longitude, 0f, lastBearing, altitudeAt(1.0))
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

    fun lerpLng(a: Double, b: Double, f: Double): Double {
        var d = b - a
        if (d > 180.0) d -= 360.0 else if (d < -180.0) d += 360.0
        var x = a + d * f
        if (x > 180.0) x -= 360.0 else if (x < -180.0) x += 360.0
        return x
    }

    fun tooLong(points: List<LatLng>, speedKmh: Float): Boolean {
        val v = max(0.5f, speedKmh) / 3.6
        return routeLength(points) / v / (TICK_MS / 1000.0) * 1.3 > MAX_TICKS
    }

    fun routeLength(points: List<LatLng>): Double {
        var t = 0.0
        for (i in 1 until points.size) t += meters(points[i - 1], points[i])
        return t
    }
}
