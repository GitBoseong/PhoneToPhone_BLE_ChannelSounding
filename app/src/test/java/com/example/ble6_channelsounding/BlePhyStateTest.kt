package com.example.ble6_channelsounding

import org.junit.Assert.*
import org.junit.Test

class BlePhyStateTest {
    @Test fun codedCallbackNeverClaimsRequestedCodingWasApplied() {
        for (request in listOf("LE_CODED_S2", "LE_CODED_S8")) {
            val phy = BlePhyState(BlePhyState.label(3), BlePhyState.label(3), request, 1000)
            assertEquals("LE_CODED", phy.actual)
            val row = MeasurementCsv.row(1200, 20.0, "INITIATOR", "AA", null, null,
                TxPowerState(), null, ImuSnapshot(), "SAMPLE", 123, phy = phy)
            assertEquals(MeasurementCsv.header.size, row.size)
            val values = MeasurementCsv.header.zip(row).toMap()
            assertEquals("LE_CODED", values["ble_phy"])
            assertEquals(request, values["ble_phy_requested"])
            assertEquals(1000L, values["ble_phy_timestamp_epoch_ms"])
        }
    }

    @Test fun fallbackAndAsymmetricPhyUseActualDirections() {
        assertEquals("LE_1M", BlePhyState("LE_1M", "LE_1M", "LE_2M").actual)
        assertEquals("TX=LE_2M/RX=LE_1M", BlePhyState("LE_2M", "LE_1M").actual)
        assertEquals("LE_2M", BlePhyState.label(2))
        assertNull(BlePhyState.label(0))
    }

    @Test fun unconfirmedOrDisconnectedPhyStaysEmpty() {
        val row = MeasurementCsv.row(1200, 20.0, "INITIATOR", "AA", null, null,
            TxPowerState(), null, ImuSnapshot(), "SAMPLE", 123,
            phy = BlePhyState(requested = "LE_2M"))
        val values = MeasurementCsv.header.zip(row).toMap()
        listOf("ble_phy", "ble_tx_phy", "ble_rx_phy", "ble_phy_timestamp_epoch_ms")
            .forEach { assertNull(values[it]) }
        assertEquals("LE_2M", values["ble_phy_requested"])
    }
}
