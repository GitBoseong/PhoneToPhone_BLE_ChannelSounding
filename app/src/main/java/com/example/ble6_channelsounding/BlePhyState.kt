package com.example.ble6_channelsounding

/** Android callbacks report Coded PHY, but do not report its S=2/S=8 coding. */
data class BlePhyState(
    val tx: String? = null,
    val rx: String? = null,
    val requested: String? = null,
    val timestamp: Long? = null
) {
    val actual: String?
        get() = if (tx == null || rx == null) null
            else if (tx == rx) tx else "TX=$tx/RX=$rx"

    companion object {
        fun label(phy: Int): String? = when (phy) {
            1 -> "LE_1M"
            2 -> "LE_2M"
            3 -> "LE_CODED"
            else -> null
        }
    }
}

enum class BlePhyChoice(val label: String, val csv: String) {
    LE_1M("LE 1M", "LE_1M"),
    LE_2M("LE 2M", "LE_2M"),
    LE_CODED_S2("LE Coded S=2", "LE_CODED_S2"),
    LE_CODED_S8("LE Coded S=8", "LE_CODED_S8")
}
