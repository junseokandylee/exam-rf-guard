package com.classroom.rfdetector.core

/** Public discovery metadata only; no manufacturer payloads or connection traffic. */
data class RadioMetadata(
    val wifi: WifiRadioMetadata? = null,
    val ble: BleRadioMetadata? = null,
    val classicDeviceClass: Int? = null,
) {
    fun snapshotCopy(): RadioMetadata = copy(ble = ble?.let { it.copy(serviceUuids = it.serviceUuids.toList()) })
}

data class WifiRadioMetadata(
    /** Android ScanResult width code. Keep 80+80 distinct from contiguous 160 MHz. */
    val channelWidthCode: Int? = null,
    val centerFrequency0Mhz: Int? = null,
    val centerFrequency1Mhz: Int? = null,
    val capabilities: String? = null,
) {
    val channelWidthMhz: Int? get() = when (channelWidthCode) {
        0 -> 20
        1 -> 40
        2 -> 80
        3, 4 -> 160
        5 -> 320
        else -> null
    }
    val channelWidthLabel: String? get() = if (channelWidthCode == 4) "80+80 MHz" else channelWidthMhz?.let { "$it MHz" }
}

data class BleRadioMetadata(
    val serviceUuids: List<String> = emptyList(),
    /** Advertisement flags, not a connection attempt or a verified device function. */
    val connectable: Boolean? = null,
    val legacy: Boolean? = null,
    val primaryPhy: Int? = null,
    /** Android PHY_UNUSED (0) means that there is no secondary advertising PHY. */
    val secondaryPhy: Int? = null,
)
