package com.example.ble6_channelsounding

import java.util.UUID

object CsProtocol {
    val SERVICE_UUID: UUID =
        UUID.fromString("7810A190-9A7A-4D52-8A31-14E90C2F1A01")

    val CONTROL_UUID: UUID =
        UUID.fromString("7810A190-9A7A-4D52-8A31-14E90C2F1A02")

    val STATUS_UUID: UUID =
        UUID.fromString("7810A190-9A7A-4D52-8A31-14E90C2F1A03")

    val CCCD_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val START = "START"
    const val STOP = "STOP"

    const val PREPARING = "PREPARING"
    const val READY = "READY"
    const val STOPPED = "STOPPED"

    const val ERROR_PREFIX = "ERROR:"
}
