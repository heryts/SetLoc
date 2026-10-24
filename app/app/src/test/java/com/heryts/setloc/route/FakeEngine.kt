package com.heryts.setloc.route

import com.google.android.gms.maps.model.LatLng

internal object FakeEngine {
    fun build(points: List<LatLng>, speedKmh: Float, speedVarKmh: Float): List<RouteRunner.Tick> =
        points.mapIndexed { i, p ->
            RouteRunner.Tick(p.latitude, p.longitude, 0f, 0f, 0f, if (i == points.size - 1) 100 else 0)
        }

    fun write(ticks: List<RouteRunner.Tick>, @Suppress("UNUSED_PARAMETER") w: java.io.Writer): Long =
        ticks.size.toLong() * RouteRunner.TIMELINE_LINE_WIDTH

    fun install(): () -> Unit {
        val ob = RouteRunner.builder
        val ow = RouteRunner.writer
        val os = RouteRunner.schedule
        RouteRunner.builder = { p, s, v -> build(p, s, v) }
        RouteRunner.writer = { t, w -> write(t, w) }
        RouteRunner.schedule = FakeSchedule
        return {
            RouteRunner.builder = ob
            RouteRunner.writer = ow
            RouteRunner.schedule = os
        }
    }
}
