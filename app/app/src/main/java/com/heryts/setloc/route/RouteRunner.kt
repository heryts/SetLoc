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
    @Volatile internal var schedule: RouteSchedule = RouteNative

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
    const val TIMELINE_LINE_WIDTH = 48

    @Volatile var elevations: FloatArray? = null

    private var lastBearing = -1f
    private var prefTicks = 0
    private var thinWrites = true

    fun start(
        points: List<LatLng>, speedKmh: Float, waitAtStartMin: Float, waitAtEndMin: Float, speedVarKmh: Float
    ) {
        if (points.size < 2 || RouteNative.tooLong(points, speedKmh)) return
        synchronized(lock) {
            job?.cancel()
            val g = ++gen
            active = true
            prefTicks = 0
            lastBearing = RouteNative.bearing(points[0], points[1])
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

        var chars = 0L
        val written = synchronized(fileLock) {
            if (g != gen) return
            sink.writeRouteTimeline { chars = writer(ticks, it) ?: -1L }
        }
        if (chars < 0L) {
            abort(g)
            return
        }
        val plan = schedule.plan(waitAtStartMin, waitAtEndMin, n, chars)
        if (plan == null) {
            abort(g)
            return
        }
        val t0 = clock() + plan.waitMs
        synchronized(lock) {
            if (g != gen) return
            thinWrites = written
        }
        if (written) {
            val first = ticks[0]
            synchronized(lock) {
                if (!active || g != gen) return
                sink.beginPlayback(t0 / 10, TICK_MS, n, plan.holdEndMs, plan.lineWidth)
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
            val k = schedule.tickAt(clock(), t0, n)
            if (k < 0) break
            if (k != shown) {
                shown = k
                val t = ticks[k]
                if (!write(g, LatLng(t.lat, t.lng), t.pct, t.spd, t.brg, t.alt)) return
            }
            delay(schedule.sleepMs(clock(), t0, k))
        }
        if (!currentCoroutineContext().isActive) return

        val last = ticks[n - 1]
        if (!arrive(g, LatLng(last.lat, last.lng), last.alt)) return

        if (plan.autoStop) {
            finish(g, points.last(), spoofOff = true)
        } else if (plan.endWaitMs > 0) {
            val endAt = schedule.endAt(t0, n, plan.endWaitMs)
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
        val due = schedule.prefDue(thinWrites, prefTicks)
        if (thinWrites) prefTicks++
        if (due) {
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

    private fun altitudeAt(fraction: Double): Float = RouteNative.altitudeAt(elevations, sink.altitudeManual, fraction)

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
}
