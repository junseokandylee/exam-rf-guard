package com.classroom.rfdetector.core

/** Wi-Fi Direct lists never supply RSSI, even if an old/imported row contains a value. */
val SnapshotSignal.receivedRssi: Double? get() = rssi.takeUnless { source == ScanSource.P2P }

/** Classify only from evidence the scanner actually obtained. */
fun SnapshotSignal.equipmentType(): String = when (source) {
    ScanSource.WIFI -> "Wi-Fi 접속점 · 공유기/핫스폿"
    ScanSource.P2P -> "Wi-Fi Direct 주변 기기"
    ScanSource.BLE -> if (radioMetadata?.ble?.beaconFormats.orEmpty().isNotEmpty()) "BLE 비콘 광고"
        else audioNameMatch?.kind?.label ?: wearableNameMatch?.kind?.label ?: "Bluetooth 장비 · 유형 미확인"
    else -> audioNameMatch?.kind?.label ?: wearableNameMatch?.kind?.label ?: "Bluetooth 장비 · 유형 미확인"
}

fun TrackedDevice.equipmentType(): String = when (source) {
    ScanSource.WIFI -> "Wi-Fi 접속점 · 공유기/핫스폿"
    ScanSource.P2P -> "Wi-Fi Direct 주변 기기"
    ScanSource.BLE -> if (radioMetadata?.ble?.beaconFormats.orEmpty().isNotEmpty()) "BLE 비콘 광고"
        else identity.audioNameMatch?.kind?.label ?: identity.wearableNameMatch?.kind?.label ?: "Bluetooth 장비 · 유형 미확인"
    else -> identity.audioNameMatch?.kind?.label ?: identity.wearableNameMatch?.kind?.label ?: "Bluetooth 장비 · 유형 미확인"
}

fun observationSpan(firstSeenMs: Long, lastSeenMs: Long, count: Int): String {
    if (count <= 1) return "한 번 포착 · 기간 판단 불가"
    val seconds = (lastSeenMs.coerceAtLeast(firstSeenMs) - firstSeenMs) / 1000
    return when {
        seconds < 1 -> "1초 미만 구간에서 $count 회 수신"
        seconds < 60 -> "${seconds}초 구간에서 $count 회 수신"
        seconds < 3600 -> "${seconds / 60}분 ${seconds % 60}초 구간에서 $count 회 수신"
        else -> "${seconds / 3600}시간 ${(seconds % 3600) / 60}분 구간에서 $count 회 수신"
    }
}

fun ScanSource.observationNote(): String = when (this) {
    ScanSource.CLASSIC -> "검색 가능 상태의 일반 Bluetooth 기기에서 받은 발견 결과입니다. 시각은 콜백 수신 시각이며 연결·페어링 여부를 확인한 결과가 아닙니다."
    ScanSource.P2P -> "Android의 Wi-Fi Direct 목록에 처음 나타난 시각입니다. OS 캐시일 수 있으며 실제 전파 수신 시각·현재 존재·재등장·지속 시간을 판단하지 않습니다."
    else -> ""
}

fun ScanSource.observationSummary(firstSeenMs: Long, lastSeenMs: Long, count: Int): String =
    if (this == ScanSource.P2P) "목록 첫 확인 · RF 수신 횟수 아님" else observationSpan(firstSeenMs, lastSeenMs, count)

fun SignalPresence.labelFor(source: ScanSource): String = if (source != ScanSource.P2P) label else when (this) {
    SignalPresence.RECENT -> "최근 목록 확인"
    SignalPresence.QUIET -> "목록 확인 이력"
    SignalPresence.HISTORY -> "과거 목록 기록"
}

fun SignalPresence.observationMeaning(saved: Boolean): String {
    val prefix = if (saved) "저장 당시" else "현재"
    return when (this) {
        SignalPresence.RECENT -> "$prefix 최근 수신 · 최근 관측 범위 안에 신호가 있습니다."
        SignalPresence.QUIET -> "$prefix 수신 대기 · 한동안 새 신호를 받지 못했습니다."
        SignalPresence.HISTORY -> "$prefix 수신 기록만 있음 · 잠깐 포착된 신호도 기록에 남습니다."
    }
}
