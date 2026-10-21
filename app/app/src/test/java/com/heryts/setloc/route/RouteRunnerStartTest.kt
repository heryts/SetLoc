package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter
import java.io.Writer
import java.util.Collections

class RouteRunnerStartTest {
    private class FakeSink : RouteSink {
        val begins: MutableList<Int> = Collections.synchronizedList(ArrayList())
        override val altitudeManual: Float = 0f
        override fun updateNow(
            start: Boolean, la: Double, ln: Double, speedMs: Float, bearing: Float, altitude: Float?,
            routeActive: Boolean, routePlay: Boolean
        ) {}
        override fun writeRouteTimeline(write: (Writer) -> Unit): Boolean {
            write(StringWriter())
            return true
        }
        override fun beginPlayback(t0Centis: Long, tickMs: Long, n: Int, holdEndMs: Long, lineWidth: Int) {
            begins.add(n)
        }
    }

    @Test
    fun `start berurutan hanya memulai playback rute terakhir`() {
        val oldSink = RouteRunner.sink
        val oldClock = RouteRunner.clock
        val oldElev = RouteRunner.elevations
        val fake = FakeSink()
        RouteRunner.sink = fake
        RouteRunner.clock = { System.nanoTime() / 1_000_000L }
        RouteRunner.elevations = floatArrayOf(0f, 0f)
        try {
            val big = (0 until 20000).map { LatLng(-7.7 + it * 0.00001, 110.4) }
            val small = listOf(LatLng(10.0, 10.0), LatLng(10.0002, 10.0), LatLng(10.0004, 10.0))
            val smallN = RouteRunner.buildTimeline(small, 50f, 0f).size
            repeat(15) {
                fake.begins.clear()
                RouteRunner.start(big, 50f, 0f, -1f, 0f)
                RouteRunner.start(small, 50f, 0f, -1f, 0f)
                Thread.sleep(400)
                assertEquals(listOf(smallN), fake.begins.toList())
                RouteRunner.stop()
            }
        } finally {
            RouteRunner.stop()
            RouteRunner.sink = oldSink
            RouteRunner.clock = oldClock
            RouteRunner.elevations = oldElev
        }
    }
}
