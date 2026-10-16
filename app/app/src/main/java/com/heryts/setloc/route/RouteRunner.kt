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
                0f, lastBearing, altitudeAt(0.0), routeActive = true
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

    /** Satu titik pada timeline: posisi/kecepatan/arah/ketinggian pada tiap kelipatan [TICK_MS]. */
    private class Tick(val lat: Double, val lng: Double, val spd: Float, val brg: Float, val alt: Float, val pct: Int)

    /**
     * Seluruh perjalanan dihitung di muka (tanpa jam dinding): baris pertama = diam di titik awal, lalu satu baris per
     * [TICK_MS] sampai tujuan, baris terakhir = berhenti di tujuan. Timeline ini dipakai app (UI/notifikasi) DAN
     * ditulis ke file untuk daemon, sehingga keduanya selalu sama.
     */
    private fun buildTimeline(points: List<LatLng>, speedKmh: Float, speedVarKmh: Float): List<Tick> {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + meters(points[i - 1], points[i])
        val total = cum.last()
        val speedMs = speedKmh / 3.6
        // variasi +- (m/s): negatif = otomatis 8% dari kecepatan
        val varMs = if (speedVarKmh < 0f) speedMs * SPEED_VARIATION else speedVarKmh / 3.6
        val dt = TICK_MS / 1000.0
        val rnd = java.util.Random()
        val out = ArrayList<Tick>()
        var brg = bearing(points[0], points[1])
        out.add(Tick(points[0].latitude, points[0].longitude, 0f, brg, altitudeAt(0.0), 0))
        var travelled = 0.0
        var seg = 1
        var dev = 0.0   // selisih dari speedMs (m/s): random walk yang ditarik pelan ke rata-rata
        while (travelled < total) {
            dev = dev * (1.0 - SPEED_PULL) + rnd.nextGaussian() * varMs * SPEED_STEP
            dev = dev.coerceIn(-varMs, varMs)
            val cur = (speedMs + dev).coerceAtLeast(0.3)
            travelled = min(total, travelled + cur * dt)
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
                    cur.toFloat(), brg, altitudeAt(frac), (frac * 100).toInt()
                )
            )
        }
        val end = points.last()
        out.add(Tick(end.latitude, end.longitude, 0f, brg, altitudeAt(1.0), 100))
        return out
    }

    private fun timelineText(ticks: List<Tick>): String {
        val sb = StringBuilder(ticks.size * 48)
        for (t in ticks) {
            sb.append(java.lang.String.format(java.util.Locale.US, "%.6f %.6f %.3f %.2f %.2f\n", t.lat, t.lng, t.spd, t.brg, t.alt))
        }
        return sb.toString()
    }

    private suspend fun run(
        points: List<LatLng>, speedKmh: Float, waitAtStartMin: Float, waitAtEndMin: Float, speedVarKmh: Float
    ) {
        val ticks = buildTimeline(points, speedKmh, speedVarKmh)
        val n = ticks.size
        val waitMs = (waitAtStartMin * 60_000f).toLong()
        val endWaitMs = (waitAtEndMin * 60_000f).toLong()
        val t0 = SystemClock.elapsedRealtime() + waitMs   // saat tick bergerak pertama (diam di titik awal sebelum itu)

        // Daemon (root) memutar timeline ini sendiri berdasarkan jam boot, jadi posisi tetap bergerak walau proses app
        // dibekukan sistem saat di background. Bila gagal menulis file, app jalan sendiri seperti sebelumnya.
        if (PrefManager.writeRouteTimeline(timelineText(ticks))) {
            val first = ticks[0]
            synchronized(lock) {
                if (!active) return
                PrefManager.beginPlayback(t0 / 10, TICK_MS, n, endWaitMs)
                PrefManager.updateNow(
                    true, first.lat, first.lng, 0f, lastBearing, first.alt, routeActive = true, routePlay = true
                )
            }
        }

        // diam dulu di titik awal
        while (currentCoroutineContext().isActive) {
            val rem = t0 - SystemClock.elapsedRealtime()
            if (rem <= 0) break
            delay(min(rem, TICK_MS))
        }

        // putar timeline sesuai jam (bukan per-delay): bila app sempat tertahan, langsung lompat ke baris yang benar
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

        // sudah sampai: kecepatan kembali 0 (arah tetap), baik sedang diam di tujuan maupun menunggu Stop
        val last = ticks[n - 1]
        if (!arrive(LatLng(last.lat, last.lng), last.alt)) return

        // diam di tujuan; lalu spoof otomatis mati. 0 = tetap aktif sampai user menekan Stop.
        if (endWaitMs > 0) {
            val endAt = t0 + (n - 1) * TICK_MS + endWaitMs
            while (currentCoroutineContext().isActive) {
                val rem = endAt - SystemClock.elapsedRealtime()
                if (rem <= 0) break
                delay(min(rem, TICK_MS))
            }
            finish(points.last(), spoofOff = true)
        } else {
            finish(points.last(), spoofOff = false)
        }
    }

    private fun write(p: LatLng, pct: Int, speedMs: Float, brg: Float, alt: Float?): Boolean = synchronized(lock) {
        if (!active) return false
        if (brg >= 0f) lastBearing = brg
        PrefManager.updateNow(true, p.latitude, p.longitude, speedMs, lastBearing, alt, routeActive = true, routePlay = true)
        _state.value = State(running = true, position = p, progressPercent = pct)
        true
    }

    private fun arrive(end: LatLng, alt: Float?): Boolean = synchronized(lock) {
        if (!active) return false
        PrefManager.updateNow(true, end.latitude, end.longitude, 0f, lastBearing, alt, routePlay = true)
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
