package com.classroom.rfdetector.core

/** 관측 출처 */
enum class ScanSource(val label: String) {
    BLE("BLE"), WIFI("Wi-Fi AP"), P2P("Wi-Fi Direct"), SIM("연습"), CLASSIC("Bluetooth Classic");
    val isBluetooth: Boolean get() = this == BLE || this == CLASSIC
}

fun radioKey(source: ScanSource, address: String): String = "${source.name}:${address.uppercase(java.util.Locale.ROOT)}"

enum class SignalPresence(val label: String) {
    RECENT("최근 수신"),
    QUIET("수신 대기"),
    HISTORY("수신 기록"),
}

data class ScanSession(val id: String, val startedAtMs: Long, val area: String, val areaLocked: Boolean = false)

/** 거리 등급 — RSSI 기반 추정이며 실제 거리 측정이 아니다. */
enum class DistanceClass(val label: String) {
    VERY_CLOSE("바로 옆"),
    CLOSE("가까움"),
    NEAR("중간"),
    FAR("멀다"),
    UNKNOWN("측정 중"),
}

/** 스캐너/시뮬레이터가 올리는 단일 관측값 */
data class RfObservation(
    val key: String,          // BLE MAC 또는 Wi-Fi BSSID
    val source: ScanSource,
    val name: String?,        // 기기명/SSID. null 이면 미지
    val rssi: Int?,           // null 이면 존재만 확인(P2P 등)
    val txPower: Int?,        // 보정된 1m 기준 RSSI(dBm). 일반 AD 송신 출력값과 다르다.
    val hidden: Boolean,      // Wi-Fi SSID 숨김 AP
    val timestampMs: Long,
    val manufacturerIds: Set<Int> = emptySet(),
    val frequencyMhz: Int? = null, // Wi-Fi AP's observed primary frequency; absent for BLE.
    val radioMetadata: RadioMetadata? = null,
) {
    val trackingKey: String get() = radioKey(source, key)
}

/** RSSI 시계열 한 점 */
data class RssiPoint(val tsMs: Long, val rssi: Int)

/** UI 로 내보내는 추적 단위 */
data class TrackedDevice(
    val key: String,
    val displayName: String,
    val source: ScanSource,
    val hidden: Boolean,
    val rssiEma: Double?,
    val trendDbPer10s: Double?,   // + 면 가까워지는 중
    val distanceM: Double?,
    val distanceClass: DistanceClass,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val isBaselineNew: Boolean,   // 기준점 이후 새로 나타난 기기
    val bearingDeg: Double?,      // 상대 방위(정면=0, 시계방향 +). 움직인 양이 부족하면 null
    val bearingConfidence: Double, // 0..1
    val history: List<RssiPoint>,
    val manufacturerIds: Set<Int> = emptySet(),
    val identity: DeviceIdentity = DeviceIdentity(),
    val presence: SignalPresence = SignalPresence.RECENT,
    val lastSeenAgeSeconds: Long = 0,
    val observationCount: Int = 0,
    val peakRssi: Int? = null,
    val periods: List<ObservationPeriod> = emptyList(),
    val referenceRssiAt1m: Double? = null,
    val wifiChannels: List<WifiChannelObservation> = emptyList(),
    val everHidden: Boolean = hidden,
    val radioMetadata: RadioMetadata? = null,
) {
    val trackingKey: String get() = radioKey(source, key)
}
