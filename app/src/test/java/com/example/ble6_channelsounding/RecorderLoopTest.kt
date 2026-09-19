package com.example.ble6_channelsounding

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class RecorderLoopTest {
    @Test fun recordsForThirtySecondsUsingActualClock() {
        val executor = ScheduledThreadPoolExecutor(1)
        val ticks = mutableListOf<Long>()
        val done = CountDownLatch(1)
        val loop = RecorderLoop(executor, System::nanoTime) { ticks.add(System.nanoTime()) }
        try {
            executor.execute { loop.start() }
            executor.schedule({ loop.stop(); done.countDown() }, 30, TimeUnit.SECONDS)
            assertTrue("Recorder did not finish", done.await(40, TimeUnit.SECONDS))
            val deltas = ticks.zipWithNext { a, b -> (b - a) / 1e6 }.sorted()
            val duration = (ticks.last() - ticks.first()) / 1e9
            val hz = (ticks.size - 1) / duration
            println("HOST 30s: rows=${ticks.size}, duration_s=$duration, rate_hz=$hz, mean_ms=${deltas.average()}, " +
                "median_ms=${(deltas[(deltas.size - 1) / 2] + deltas[deltas.size / 2]) / 2}, min_ms=${deltas.first()}, max_ms=${deltas.last()}, skipped=${loop.skippedDeadlines}")
            assertTrue("Rate outside 45..52 Hz: $hz", hz in 45.0..52.0)
            assertTrue(deltas.all { it > 0 })
        } finally { executor.shutdownNow() }
    }

    @Test fun slowTickSkipsDeadlinesAndStopIsIdempotent() {
        val executor = ScheduledThreadPoolExecutor(1)
        val ticks = mutableListOf<Long>()
        val done = CountDownLatch(1)
        val loop = RecorderLoop(executor, System::nanoTime) {
            ticks.add(System.nanoTime())
            if (ticks.size == 1) Thread.sleep(120)
        }
        try {
            executor.execute { loop.start() }
            executor.schedule({ loop.stop(); loop.stop(); done.countDown() }, 300, TimeUnit.MILLISECONDS)
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(loop.skippedDeadlines >= 5)
            assertTrue(ticks.size < 15)
            assertTrue(ticks[1] - ticks[0] >= 120_000_000L)
        } finally { executor.shutdownNow() }
    }
}
