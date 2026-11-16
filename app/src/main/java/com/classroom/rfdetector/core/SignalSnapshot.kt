package com.classroom.rfdetector.core

/** A saved observation, scoped by radio transport rather than product name. */
data class SnapshotSignal(
    val address: String,
    val source: ScanSource,
    val name: String,
    val rssi: Double?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val observationCount: Int,
    val manufacturer: String,
    val modelHint: String?,
    val presence: SignalPresence = SignalPresence.RECENT,
    val manufacturerIds: Set<Int> = emptySet(),
    val periods: List<ObservationPeriod> = emptyList(),
    val referenceRssiAt1m: Double? = null,
    val hidden: Boolean = false,
    val everHidden: Boolean = hidden,
    val radioMetadata: RadioMetadata? = null,
) {
    val matchKey: String get() = radioKey(source, address)
    val reappearanceCount: Int get() = (periods.size - 1).coerceAtLeast(0)
}

data class SignalSnapshot(
    val id: String,
    val area: String,
    val label: String,
    val capturedAtMs: Long,
    val scanStartedAtMs: Long,
    val simulation: Boolean,
    val signals: List<SnapshotSignal>,
    /** null means a legacy record without channel data; an empty list means none were observed. */
    val wifiChannels: List<WifiChannelObservation>? = null,
    /** Modem environment only; never part of signals or participant-device comparisons. */
    val cellEnvironment: CellEnvironmentSnapshot? = null,
    /** Requested discovery scope; null in old archives means unknown, not all sources. */
    val discoverySources: Set<ScanSource>? = null,
) {
    companion object {
        fun capture(id: String, area: String, label: String, capturedAtMs: Long, scanStartedAtMs: Long, simulation: Boolean, devices: List<TrackedDevice>, includeNewHistorySinceMs: Long? = null, includeAllHistory: Boolean = false): SignalSnapshot {
            val signals = devices.filter {
                includeAllHistory || it.presence != SignalPresence.HISTORY ||
                    (includeNewHistorySinceMs != null && it.isBaselineNew && it.lastSeenMs >= includeNewHistorySinceMs)
            }.map { device ->
                    SnapshotSignal(device.key, device.source, device.displayName, device.rssiEma, device.firstSeenMs, device.lastSeenMs,
                        device.observationCount, device.identity.summary, device.identity.modelHint, device.presence, device.manufacturerIds.toSet(),
                        device.periods.toList(), device.referenceRssiAt1m, hidden = device.hidden, everHidden = device.everHidden,
                        radioMetadata = device.radioMetadata?.snapshotCopy())
            }
            // Channel history is independent of the visible/current AP list and the signal baseline filter.
            val channels = devices.filter { it.source == ScanSource.WIFI }.flatMap { device ->
                device.wifiChannels.map { it.copy(periods = it.periods.toList()) }
            }.sortedWith(compareBy<WifiChannelObservation> { it.frequencyMhz }.thenBy { it.matchKey })
            return SignalSnapshot(id, area.trim(), label.trim(), capturedAtMs, scanStartedAtMs, simulation, signals, channels)
        }
    }
}

fun discoveryScopeNote(before: SignalSnapshot, after: SignalSnapshot): String? {
    val previous = before.discoverySources
    val current = after.discoverySources
    if (previous == null || current == null) return "탐색 범위 기록이 없는 자료가 포함돼 있습니다. 같은 종류를 탐색했는지 확인하세요."
    val added = current - previous
    return added.takeIf { it.isNotEmpty() }?.joinToString(prefix = "탐색 범위 확대 · ", postfix = "는 기준 때 탐색하지 않았습니다. 추가 주소가 입실 후 생겼다는 뜻은 아닙니다.") { it.label }
}

data class SignalChange(val before: SnapshotSignal, val after: SnapshotSignal) {
    val deltaDb: Double? get() = before.receivedRssi?.let { old -> after.receivedRssi?.minus(old) }
}

data class SnapshotComparison(
    val added: List<SnapshotSignal>,
    val notObserved: List<SnapshotSignal>,
    val common: List<SignalChange>,
)

/** A changed MAC is a changed observation key, never proof of a new physical device. */
fun compareSnapshots(before: SignalSnapshot, after: SignalSnapshot): SnapshotComparison {
    require(before.area.equals(after.area, ignoreCase = true)) { "같은 공간의 스냅샷끼리 비교해 주세요." }
    require(before.simulation == after.simulation) { "실제 탐색과 연습 모드 자료는 비교할 수 없습니다." }
    require(after.capturedAtMs >= before.capturedAtMs) { "비교 대상은 기준 스냅샷 이후 시점이어야 합니다." }
    val previous = before.signals.associateBy { it.matchKey }
    val current = after.signals.associateBy { it.matchKey }
    return SnapshotComparison(
        added = current.filterKeys { it !in previous }.values.sortedByDescending { it.rssi ?: Double.NEGATIVE_INFINITY },
        notObserved = previous.filterKeys { it !in current }.values.toList(),
        common = current.mapNotNull { (key, signal) -> previous[key]?.let { SignalChange(it, signal) } },
    )
}
