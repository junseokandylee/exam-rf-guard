package com.classroom.rfdetector.core

import kotlin.math.abs

enum class RadioFilter(val label: String, val source: ScanSource? = null) {
    ALL("전체 종류"), BLE("BLE", ScanSource.BLE), WIFI("Wi-Fi", ScanSource.WIFI),
    CLASSIC("일반 Bluetooth", ScanSource.CLASSIC), P2P("Wi-Fi Direct", ScanSource.P2P)
}
enum class SignalSort(val label: String) {
    RSSI("강한 신호순"), LAST_SEEN("최근 관측순"), OBSERVATIONS("수신 횟수순"), CHANGE("변화 큰 순"), NAME("이름순"),
}

val SnapshotSignal.audioNameMatch: AudioNameMatch?
    get() = if (source.isBluetooth) AudioNameCatalog.match(name) ?: AudioNameCatalog.match(modelHint) else null

val SnapshotSignal.wearableNameMatch: WearableNameMatch?
    get() = if (source.isBluetooth) WearableNameCatalog.match(name) ?: WearableNameCatalog.match(modelHint) else null

fun SnapshotSignal.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    val audio = audioNameMatch
    val wearable = wearableNameMatch
    return fieldsMatchQuery(query, listOfNotNull(name, address, manufacturer, modelHint, audio?.model, audio?.searchModel, audio?.brand, source.name,
        wearable?.brand, wearable?.model, wearable?.kind?.label) + (wearable?.aliases ?: emptyList()) +
        (audio?.brandAliases ?: emptyList()) + manufacturerIds.map { "0x" + it.toString(16).padStart(4, '0') })
}

fun filterAndSortSignals(
    signals: List<SnapshotSignal>, query: String, radio: RadioFilter, audioOnly: Boolean, sort: SignalSort,
    changes: Map<String, Double?> = emptyMap(),
): List<SnapshotSignal> {
    val comparator = when (sort) {
        SignalSort.RSSI -> compareByDescending<SnapshotSignal> { it.rssi ?: Double.NEGATIVE_INFINITY }
        SignalSort.LAST_SEEN -> compareByDescending<SnapshotSignal> { it.lastSeenMs }
        SignalSort.OBSERVATIONS -> compareByDescending<SnapshotSignal> { it.observationCount }
        SignalSort.CHANGE -> compareByDescending<SnapshotSignal> { changes[it.matchKey]?.let(::abs) ?: Double.NEGATIVE_INFINITY }
        SignalSort.NAME -> compareBy<SnapshotSignal> { it.name.lowercase(java.util.Locale.ROOT) }
    }.thenBy { it.matchKey }
    return signals.filter { signal ->
        (radio.source == null || radio.source == signal.source) &&
            (!audioOnly || signal.audioNameMatch != null) && signal.matchesQuery(query)
    }.sortedWith(comparator)
}

data class SignalTimelinePoint(val snapshot: SignalSnapshot, val signal: SnapshotSignal?)

/** Absence is shown as not observed; session counts are never summed across snapshots. */
fun signalTimeline(signal: SnapshotSignal, snapshots: List<SignalSnapshot>, target: SignalSnapshot): List<SignalTimelinePoint> = snapshots
    .filter { it.simulation == target.simulation && it.area.equals(target.area, true) && it.capturedAtMs <= target.capturedAtMs }
    .distinctBy { it.id }
    .sortedWith(compareBy<SignalSnapshot> { it.capturedAtMs }.thenBy { it.id })
    .map { snapshot -> SignalTimelinePoint(snapshot, snapshot.signals.firstOrNull { it.matchKey == signal.matchKey }) }
