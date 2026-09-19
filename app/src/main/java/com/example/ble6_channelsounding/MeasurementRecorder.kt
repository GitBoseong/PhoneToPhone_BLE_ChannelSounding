package com.example.ble6_channelsounding

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException

/** All session state and both writers are confined to this executor. Callbacks only enqueue. */
class MeasurementRecorder(context: Context, private val status: (String) -> Unit,
                          private val gpsUnavailable: () -> Unit) {
    private data class Event(val time: Long, val role: String, val source: String, val message: String)
    private val executor = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "PhoneCS-Recorder") }
        .apply { removeOnCancelPolicy = true }
    private val logger = SessionCsvLogger(context)
    private var role = "INITIATOR"
    private var peer: String? = null
    private var active = false
    private var imuEnabled = true
    private var gpsEnabled = true
    private var tx = TxPowerState()
    private val pending = PendingMeasurements()
    private val history = ArrayDeque<Event>()
    private var droppedHistory = 0
    private val sensors = SensorCollector(context) { message -> event("SENSOR", message) }
    private val gps = GpsCollector(context) { message -> event("GPS", message) }
    private var startNanos = 0L
    private var previousNanos: Long? = null
    private var firstNanos = 0L
    private var sampleCount = 0L
    private val intervals = mutableListOf<Double>()
    private val loop = RecorderLoop(executor, SystemClock::elapsedRealtimeNanos) { record("SAMPLE") }

    private fun submit(block: () -> Unit) {
        try { executor.execute { guarded(block) } } catch (_: RejectedExecutionException) { }
    }
    private fun guarded(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            loop.stop(); active = false
            runCatching { sensors.stop() }; runCatching { gps.stop() }
            runCatching { logger.event(System.currentTimeMillis(), role, "ERROR", "Recorder failed: ${e.message}") }
            runCatching { logger.close() }
            status("Recording stopped: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun event(source: String, message: String, eventRole: String? = null, timestamp: Long = System.currentTimeMillis()) = submit {
        val event = Event(timestamp, eventRole ?: role, source, message)
        if (active) logger.event(event.time, event.role, source, message)
        else {
            if (history.size >= 5000) { history.removeFirst(); droppedHistory++ }
            history.addLast(event)
        }
    }
    fun setRole(value: String) = submit { role = value }
    fun setTx(value: TxPowerState) = submit { tx = value }
    fun setImuEnabled(value: Boolean) = submit {
        imuEnabled = value
        if (active && value) sensors.start() else sensors.stop()
        event("SENSOR", "IMU ${if (value) "ON" else "OFF"}")
    }
    fun setGpsEnabled(value: Boolean) = submit {
        gpsEnabled = value
        if (active && value) startGps() else gps.stop()
        event("GPS", "GPS ${if (gpsEnabled) "ON" else "OFF"}")
    }
    private fun startGps() {
        if (!gps.start()) { gpsEnabled = false; gpsUnavailable() }
    }

    fun start(sessionRole: String, address: String?, power: TxPowerState, scan: ScanSample?) = submit {
        if (active) return@submit // Duplicate START must not split a live recording.
        role = sessionRole; peer = address; tx = power
        pending.clear()
        scan?.let { pending.scans.addLast(it) }
        logger.start(role)
        active = true
        startNanos = SystemClock.elapsedRealtimeNanos()
        firstNanos = 0; previousNanos = null; sampleCount = 0; intervals.clear()
        while (history.isNotEmpty()) {
            val item = history.removeFirst()
            logger.event(item.time, item.role, item.source, item.message)
        }
        if (droppedHistory > 0) {
            logger.event(System.currentTimeMillis(), role, "STORAGE", "Pre-session log buffer dropped $droppedHistory oldest events")
            droppedHistory = 0
        }
        logger.event(System.currentTimeMillis(), role, "STORAGE", "Session LOG + DATA opened; peer=$peer")
        logger.event(System.currentTimeMillis(), role, "BLE", "TX_POWER mode=${tx.mode ?: "unavailable"} requested=${tx.requested ?: if (tx.mode == "DEFAULT") "DEFAULT" else "N/A"} applied=${tx.applied ?: "N/A"} advertised=${tx.advertised ?: "N/A"}")
        if (imuEnabled) sensors.start()
        if (gpsEnabled) startGps()
        loop.start()
        status("Recording LOG + DATA → Download/PhoneCS (50 Hz)")
    }

    fun distance(sample: DistanceSample) = submit {
        if (active) pending.distances.addLast(sample)
    }
    fun scan(sample: ScanSample) = submit {
        if (active) pending.scans.addLast(sample)
    }

    private fun record(kind: String) = guarded {
        if (!active) return@guarded
        val nanos = SystemClock.elapsedRealtimeNanos()
        val now = System.currentTimeMillis()
        val fields = MeasurementCsv.row(now, (nanos - startNanos) / 1_000_000.0, role, peer,
            pending.distances.removeFirstOrNull(), pending.scans.removeFirstOrNull(), tx, gps.poll(),
            if (imuEnabled && kind == "SAMPLE") sensors.snapshot() else ImuSnapshot(), kind, nanos)
        logger.data(fields)
        if (kind == "SAMPLE") {
            if (sampleCount == 0L) firstNanos = nanos
            previousNanos?.let { intervals.add((nanos - it) / 1_000_000.0) }
            previousNanos = nanos; sampleCount++
            if (sampleCount % 50L == 0L) logger.flush()
        }
    }

    fun stop(reason: String) = submit { closeSession(reason) }
    private fun closeSession(reason: String) {
        loop.stop()
        sensors.stop()
        gps.stop(clearPending = false)
        if (active) {
            // Preserve events arriving after the last tick. Mark extra drain rows explicitly.
            while (active && (pending.hasPending() || gps.hasPending())) record("FINAL_PENDING")
            val sorted = intervals.sorted()
            val duration = previousNanos?.let { (it - firstNanos) / 1e9 } ?: 0.0
            val median = if (sorted.isEmpty()) null else
                (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0
            val stats = "Recorder samples=$sampleCount duration_s=$duration rate_hz=${if (duration > 0) (sampleCount - 1) / duration else 0.0} " +
                "mean_ms=${intervals.takeIf { it.isNotEmpty() }?.average()} median_ms=$median " +
                "min_ms=${sorted.firstOrNull()} max_ms=${sorted.lastOrNull()} skipped_deadlines=${loop.skippedDeadlines}"
            logger.event(System.currentTimeMillis(), role, "STORAGE", "$reason; $stats")
            status(stats)
        }
        active = false
        pending.clear(); gps.stop()
        logger.close()
    }
    fun destroy() {
        submit { try { closeSession("Activity destroy") } finally { sensors.destroy() } }
        executor.shutdown() // Accepted close runs before termination; no main-thread file I/O.
    }
}
