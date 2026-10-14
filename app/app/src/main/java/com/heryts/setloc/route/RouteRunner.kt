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
        val progressPercent: Int = 0,
        /** true bila perjalanan selesai dan spoof otomatis dimatikan (setelah diam di tujuan). */
        val spoofOff: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value.running

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var job: Job? = null
    private var active = false   // dijaga lock: hanya bila true, tick boleh menulis ke pref

    private const val TICK_MS = 500L   // 2x per detik: posisi rute lebih halus & delay ke app lain lebih kecil
    /** Variasi kecepatan +-8% (seperti GPS asli), dihaluskan supaya tidak melompat. */
    private const val SPEED_VARIATION = 0.08
    private const val SPEED_PULL = 0.1    // tarikan balik ke kecepatan rata-rata per tick (naik = lebih cepat kembali)
    private const val SPEED_STEP = 0.22   // besar langkah acak per tick relatif terhadap variasi (naik = lebih gelisah)

    /**
     * Ketinggian (m) berjarak sama di sepanjang rute, diisi MapActivity dari [ElevationClient].
     * Boleh diisi saat perjalanan sudah berjalan; null = pakai [PrefManager.altitudeManual].
     */
    @Volatile var elevations: FloatArray? = null

    private var lastBearing = -1f   // dijaga lock

    fun start(
        points: List<LatLng>, speedKmh: Float, waitAtStartMin: Float, waitAtEndMin: Float = 0f,
        speedVarKmh: Float = -1f
    ) {
        if (points.size < 2) return
        synchronized(lock) {
            job?.cancel()
            active = true
            // titik awal ditulis sinkron: pref langsung start=true di posisi awal (diam, menghadap arah rute)
            lastBearing = bearing(points[0], points[1])
            PrefManager.updateNow(
                true, points.first().latitude, points.first().longitude,
                0f, lastBearing, altitudeAt(0.0)
            )
            _state.value = State(running = true, position = points.first())
            job = scope.launch {
                run(
                    points, speedKmh.coerceAtLeast(0.5f), waitAtStartMin.coerceAtLeast(0f),
                    waitAtEndMin.coerceAtLeast(0f), speedVarKmh
                )
            }
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

    private suspend fun run(
        points: List<LatLng>, speedKmh: Float, waitAtStartMin: Float, waitAtEndMin: Float, speedVarKmh: Float
    ) {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + meters(points[i - 1], points[i])
        val total = cum.last()
        val speedMs = speedKmh / 3.6
        // variasi +- (m/s): negatif = otomatis 8% dari kecepatan
        val varMs = if (speedVarKmh < 0f) speedMs * SPEED_VARIATION else speedVarKmh / 3.6

        // diam dulu di titik awal
        var waitMs = (waitAtStartMin * 60_000f).toLong()
        while (waitMs > 0 && currentCoroutineContext().isActive) {
            val step = min(TICK_MS, waitMs)
            delay(step)
            waitMs -= step
        }

        var travelled = 0.0
        var seg = 1
        var cur = 0.0   // kecepatan sesaat (m/s)
        // variasi kecepatan: random walk yang ditarik pelan ke rata-rata (berubah bertahap, bukan loncat-loncat)
        val rnd = java.util.Random()
        var dev = 0.0   // selisih dari speedMs (m/s)
        var last = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive && travelled < total) {
            delay(TICK_MS)
            val now = SystemClock.elapsedRealtime()
            dev = dev * (1.0 - SPEED_PULL) + rnd.nextGaussian() * varMs * SPEED_STEP
            dev = dev.coerceIn(-varMs, varMs)
            cur = (speedMs + dev).coerceAtLeast(0.3)
            travelled = min(total, travelled + cur * (now - last) / 1000.0)
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
            val brg = if (segLen > 0.5) bearing(a, b) else -2f   // -2 = pertahankan arah terakhir
            val frac = if (total > 0) travelled / total else 1.0
            if (!write(pos, pct, cur.toFloat(), brg, altitudeAt(frac))) return
        }
        if (!currentCoroutineContext().isActive) return

        // sudah sampai: kecepatan kembali 0 (arah tetap), baik sedang diam di tujuan maupun menunggu Stop
        if (!arrive(points.last(), altitudeAt(1.0))) return

        // diam di tujuan; lalu spoof otomatis mati. 0 = tetap aktif sampai user menekan Stop.
        var endWaitMs = (waitAtEndMin * 60_000f).toLong()
        if (endWaitMs > 0) {
            while (endWaitMs > 0 && currentCoroutineContext().isActive) {
                val step = min(TICK_MS, endWaitMs)
                delay(step)
                endWaitMs -= step
            }
            finish(points.last(), spoofOff = true)
        } else {
            finish(points.last(), spoofOff = false)
        }
    }

    private fun write(p: LatLng, pct: Int, speedMs: Float, brg: Float, alt: Float?): Boolean = synchronized(lock) {
        if (!active) return false
        if (brg >= 0f) lastBearing = brg
        PrefManager.updateNow(true, p.latitude, p.longitude, speedMs, lastBearing, alt)
        _state.value = State(running = true, position = p, progressPercent = pct)
        true
    }

    private fun arrive(end: LatLng, alt: Float?): Boolean = synchronized(lock) {
        if (!active) return false
        PrefManager.updateNow(true, end.latitude, end.longitude, 0f, lastBearing, alt)
        true
    }

    /** Ketinggian di posisi [fraction] (0..1) sepanjang rute; elevasi server, atau cadangan manual. */
    private fun altitudeAt(fraction: Double): Float {
        val e = elevations
        if (e == null || e.isEmpty()) return PrefManager.altitudeManual
        if (e.size == 1) return e[0]
        val x = fraction.coerceIn(0.0, 1.0) * (e.size - 1)
        val i = x.toInt().coerceAtMost(e.size - 2)
        val f = (x - i).toFloat()
        return e[i] + (e[i + 1] - e[i]) * f
    }

    /** Arah (derajat 0..360, utara = 0, searah jarum jam) dari a ke b. */
    fun bearing(a: LatLng, b: LatLng): Float {
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
            // spoofOff=false: posisi tetap di tujuan dan spoof tetap aktif sampai user menekan Stop
            PrefManager.updateNow(!spoofOff, end.latitude, end.longitude, 0f, lastBearing, altitudeAt(1.0))
            _state.value = State(
                running = false, finished = true, position = end,
                progressPercent = 100, spoofOff = spoofOff
            )
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
