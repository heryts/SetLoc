package com.heryts.setloc.route

internal object FakeSchedule : RouteSchedule {
    private const val TICK = 500L

    override fun plan(waitStartMin: Float, waitEndMin: Float, n: Int, chars: Long): Plan {
        val autoStop = waitEndMin < 0f
        val endWait = if (autoStop) 0L else (waitEndMin * 60_000f).toLong()
        val width = if (chars == n.toLong() * RouteRunner.TIMELINE_LINE_WIDTH) RouteRunner.TIMELINE_LINE_WIDTH else 0
        return Plan((waitStartMin * 60_000f).toLong(), autoStop, endWait, width, if (autoStop) 1L else endWait)
    }

    override fun tickAt(now: Long, t0: Long, n: Int): Int {
        val k = ((now - t0) / TICK).toInt().coerceAtLeast(0)
        return if (k >= n - 1) -1 else k
    }

    override fun sleepMs(now: Long, t0: Long, k: Int): Long = (t0 + (k + 1) * TICK - now).coerceIn(20L, TICK)

    override fun endAt(t0: Long, n: Int, endWaitMs: Long): Long = t0 + (n - 1) * TICK + endWaitMs

    override fun prefDue(thin: Boolean, counter: Int): Boolean = !thin || counter % 10 == 0
}
