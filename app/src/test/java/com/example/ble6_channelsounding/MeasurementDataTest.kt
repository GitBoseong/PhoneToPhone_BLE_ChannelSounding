package com.example.ble6_channelsounding

import org.junit.Assert.*
import org.junit.Test

class MeasurementDataTest {
    @Test fun missingValuesStayEmptyAndSchemaMatches() {
        val row = MeasurementCsv.row(1_789_796_292_123, 20.0, "INITIATOR", "AA", null, null,
            TxPowerState(), null, ImuSnapshot(), "SAMPLE", 123L)
        assertEquals(MeasurementCsv.header.size, row.size)
        val fields = MeasurementCsv.header.zip(row).toMap()
        listOf("distance_raw_m", "ranging_rssi_dbm", "gps_latitude", "acc_x", "game_rv_w", "pressure_hpa", "tx_power_requested_dbm")
            .forEach { assertNull(fields[it]) }
        assertFalse(MeasurementCsv.line(row).contains("null"))
        assertTrue(fields["timestamp_iso8601"].toString().matches(Regex(".*(Z|[+-]\\d{2}:\\d{2})$")))
    }

    @Test fun eventsAreConsumedOnceAndStayAssociated() {
        val pending = PendingMeasurements()
        pending.distances.addLast(DistanceSample(1.2, 1.1, 3, 1000, -58, "LOCAL_RANGING"))
        pending.distances.addLast(DistanceSample(1.3, 1.2, 4, 1002, null, "LOCAL_RANGING"))
        pending.scans.addLast(ScanSample(-54, 900))
        val first = pending.distances.removeFirstOrNull()
        assertEquals(-58, first?.rssi)
        assertEquals(1000L, first?.timestamp)
        assertEquals(-54, pending.scans.removeFirstOrNull()?.rssi)
        assertNull(pending.scans.removeFirstOrNull())
        assertNull(pending.distances.removeFirstOrNull()?.rssi)
        assertNull(pending.distances.removeFirstOrNull())
        assertFalse(pending.hasPending())
    }

    @Test fun defaultRequestedIsEmptyAndAppliedIsIndependent() {
        val row = MeasurementCsv.row(1000, 20.0, "REFLECTOR", null, null, null,
            TxPowerState("DEFAULT", null, -7), null, ImuSnapshot(), "SAMPLE", 123)
        val values = MeasurementCsv.header.zip(row).toMap()
        assertEquals("DEFAULT", values["tx_power_mode"])
        assertNull(values["tx_power_requested_dbm"])
        assertEquals(-7, values["tx_power_applied_dbm"])
        assertNull(values["tx_power_advertised_dbm"])
    }

    @Test fun csvEscapesQuotesCommasAndNewlinesAndRejectsNonFiniteValues() {
        assertEquals("\"a,b\",\"say \"\"yes\"\"\",\"line\nnext\",,,,0", MeasurementCsv.line(
            listOf("a,b", "say \"yes\"", "line\nnext", null, Double.NaN, Float.POSITIVE_INFINITY, 0)))
    }

    @Test fun existingDistancePacketRemainsCompatible() {
        val packet = CsProtocol.encodeDistance(1.25, 1.125, 17)
        assertEquals(12, packet.size)
        val decoded = CsProtocol.decodeDistance(packet)!!
        assertEquals(1.25, decoded.rawMeters, 0.0)
        assertEquals(1.125, decoded.smoothedMeters, 0.0)
        assertEquals(17, decoded.sampleCount)
        assertNull(CsProtocol.decodeDistance(byteArrayOf(1, 2)))
    }
}
