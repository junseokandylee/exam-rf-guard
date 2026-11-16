package com.classroom.rfdetector.core

/** Cumulative observations of one BSSID on one frequency, retained for the whole scan session. */
data class WifiChannelObservation(
    val address: String,
    val name: String,
    val frequencyMhz: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val observationCount: Int,
    val lastRssi: Int?,
    val peakRssi: Int?,
    val periods: List<ObservationPeriod> = emptyList(),
    val hidden: Boolean = false,
    val everHidden: Boolean = hidden,
    val wifiMetadata: WifiRadioMetadata? = null,
) {
    val matchKey: String get() = "${radioKey(ScanSource.WIFI, address)}@$frequencyMhz"
    val reappearanceCount: Int get() = (periods.size - 1).coerceAtLeast(0)
}
