package com.example.ble6_channelsounding

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TxPowerState(
    val mode: String? = null,
    val requested: Int? = null,
    val applied: Int? = null,
    val advertised: Int? = null
)

data class DistanceSample(
    val raw: Double, val smoothed: Double, val count: Int,
    val timestamp: Long, val rssi: Int?, val source: String
)

data class ScanSample(val rssi: Int, val timestamp: Long)
data class GpsFix(val latitude: Double, val longitude: Double, val timestamp: Long)
data class SensorValue(val values: List<Float>, val timestamp: Long)
data class ImuSnapshot(
    val acc: SensorValue? = null, val gyro: SensorValue? = null,
    val mag: SensorValue? = null, val rotation: SensorValue? = null,
    val pressure: SensorValue? = null
)

/** Access only on the recorder executor. Events are consumed, never forward-filled. */
class PendingMeasurements {
    val distances = ArrayDeque<DistanceSample>()
    val scans = ArrayDeque<ScanSample>()
    fun clear() { distances.clear(); scans.clear() }
    fun hasPending() = distances.isNotEmpty() || scans.isNotEmpty()
}

object MeasurementCsv {
    val header = listOf(
        "timestamp_iso8601", "timestamp_epoch_ms", "elapsed_ms", "role", "peer_address",
        "distance_raw_m", "distance_smoothed_m", "distance_sample_count", "distance_timestamp_epoch_ms",
        "rssi_dbm", "ranging_rssi_dbm", "tx_power_mode", "tx_power_requested_dbm",
        "tx_power_applied_dbm", "tx_power_advertised_dbm", "gps_latitude", "gps_longitude",
        "gps_timestamp_epoch_ms", "acc_x", "acc_y", "acc_z", "gyro_x", "gyro_y", "gyro_z",
        "mag_x", "mag_y", "mag_z", "game_rv_x", "game_rv_y", "game_rv_z", "game_rv_w", "pressure_hpa",
        "acc_timestamp_epoch_ms", "gyro_timestamp_epoch_ms", "mag_timestamp_epoch_ms",
        "game_rv_timestamp_epoch_ms", "pressure_timestamp_epoch_ms", "rssi_timestamp_epoch_ms",
        "distance_source", "row_kind", "record_elapsed_realtime_ns"
    )
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    fun iso(epoch: Long): String = formatter.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))
    fun line(fields: List<Any?>): String = fields.joinToString(",") { value ->
        val text = when (value) {
            null -> ""
            is Float -> if (value.isFinite()) value.toString() else ""
            is Double -> if (value.isFinite()) value.toString() else ""
            else -> value.toString()
        }
        if (text.any { it == ',' || it == '"' || it == '\r' || it == '\n' })
            "\"${text.replace("\"", "\"\"")}\"" else text
    }

    fun row(now: Long, elapsedMs: Double, role: String, peer: String?, distance: DistanceSample?,
            scan: ScanSample?, tx: TxPowerState, gps: GpsFix?, imu: ImuSnapshot,
            kind: String, elapsedNanos: Long): List<Any?> = buildList {
        addAll(listOf(iso(now), now, elapsedMs, role, peer,
            distance?.raw, distance?.smoothed, distance?.count, distance?.timestamp,
            scan?.rssi, distance?.rssi, tx.mode, tx.requested, tx.applied, tx.advertised,
            gps?.latitude, gps?.longitude, gps?.timestamp))
        for ((sensor, size) in listOf(imu.acc to 3, imu.gyro to 3, imu.mag to 3, imu.rotation to 4, imu.pressure to 1)) {
            repeat(size) { add(sensor?.values?.getOrNull(it)) }
        }
        addAll(listOf(imu.acc?.timestamp, imu.gyro?.timestamp, imu.mag?.timestamp,
            imu.rotation?.timestamp, imu.pressure?.timestamp, scan?.timestamp,
            distance?.source, kind, elapsedNanos))
    }
}

