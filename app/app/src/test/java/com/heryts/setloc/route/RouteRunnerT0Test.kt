package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RouteRunnerT0Test {
    private class SlowSink(val delayMs: Long, val now: () -> Long) : RouteSink {
        val begun = CountDownLatch(1)
        val t0Ms = AtomicLong(-1)
        val writeEndMs = AtomicLong(-1)
        override val altitudeManual: Float = 0f
        override fun updateNow(
            start: Boolean, la: Double, ln: Double, speedMs: Float, bearing: Float, altitude: Float?,
            routeActive: Boolean, routePlay: Boolean
        ) {}
        override fun writeRouteTimeline(write: (Writer) -> Unit): Boolean {
            write(StringWriter())
            Thread.sleep(delayMs)
            writeEndMs.set(now())
            return true
        }
        override fun beginPlayback(t0Centis: Long, tickMs: Long, n: Int, holdEndMs: Long, lineWidth: Int) {
            t0Ms.set(t0Centis * 10)
            begun.countDown()
        }
    }

    @Test
    fun `awal rute dihitung setelah timeline selesai ditulis`() {
        val oldSink = RouteRunner.sink
        val oldClock = RouteRunner.clock
        val oldElev = RouteRunner.elevations
        val restoreEngine = FakeEngine.install()
        val clock = { System.nanoTime() / 1_000_000L }
        val sink = SlowSink(300, clock)
        RouteRunner.sink = sink
        RouteRunner.clock = clock
        RouteRunner.elevations = floatArrayOf(0f, 0f)
        try {
            val pts = listOf(LatLng(10.0, 10.0), LatLng(10.002, 10.0))
            RouteRunner.start(pts, 50f, 0f, -1f, 0f)
            assertTrue(sink.begun.await(5, TimeUnit.SECONDS))
            assertTrue(sink.t0Ms.get() >= sink.writeEndMs.get() - 10)
        } finally {
            RouteRunner.stop()
            RouteRunner.sink = oldSink
            RouteRunner.clock = oldClock
            RouteRunner.elevations = oldElev
            restoreEngine()
        }
    }
}
