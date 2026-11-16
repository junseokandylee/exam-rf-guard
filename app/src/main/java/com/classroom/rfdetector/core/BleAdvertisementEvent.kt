package com.classroom.rfdetector.core

/** One Android advertisement callback, including callbacks excluded from device tracking. */
data class BleAdvertisementEvent(
    val receivedAtMs: Long,
    val observedAtMs: Long?,
    val timestampNanos: Long,
    val address: String?,
    val name: String?,
    val rssi: Int?,
    val rawRecordHex: String?,
    val dataStatus: Int?,
    val primaryPhy: Int?,
    val secondaryPhy: Int?,
    val advertisingSid: Int?,
    val disposition: BleRecordDisposition,
)

enum class BleRecordDisposition { ACCEPTED, BEFORE_SCAN, INVALID_TIMESTAMP, DECODE_ERROR }

/** Session-local counters; received = written + pending + failed. */
data class BleJournalStatus(
    val sessionId: String? = null,
    val received: Long = 0,
    val written: Long = 0,
    val pending: Long = 0,
    val failed: Long = 0,
    val error: String? = null,
    val overflow: Long = 0,
    val writeFailed: Long = 0,
)
