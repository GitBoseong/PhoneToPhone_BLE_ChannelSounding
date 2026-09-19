package com.example.ble6_channelsounding

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 50 Hz deadlines; overdue deadlines are skipped instead of manufacturing catch-up rows. */
class RecorderLoop(
    private val executor: ScheduledExecutorService,
    private val clockNanos: () -> Long,
    private val record: () -> Unit
) {
    private var future: ScheduledFuture<*>? = null
    private var running = false
    private var deadline = 0L
    var skippedDeadlines = 0L
        private set

    fun start() {
        stop()
        running = true
        skippedDeadlines = 0
        deadline = clockNanos() + PERIOD_NS
        schedule()
    }

    private fun schedule() {
        future = executor.schedule({
            if (running) {
                record()
                if (running) {
                    deadline += PERIOD_NS
                    val now = clockNanos()
                    if (deadline <= now) {
                        val missed = (now - deadline) / PERIOD_NS + 1
                        skippedDeadlines += missed
                        deadline += missed * PERIOD_NS
                    }
                    schedule()
                }
            }
        }, (deadline - clockNanos()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    fun stop() { running = false; future?.cancel(false); future = null }
    companion object { const val PERIOD_NS = 20_000_000L }
}
