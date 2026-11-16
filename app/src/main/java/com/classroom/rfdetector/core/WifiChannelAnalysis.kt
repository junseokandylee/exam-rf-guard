package com.classroom.rfdetector.core

import java.util.Locale

enum class WifiBand(val label: String) { GHZ_2_4("2.4 GHz"), GHZ_5("5 GHz"), GHZ_6("6 GHz"), OTHER("기타 주파수") }

enum class WifiChannelComparisonState {
    READY, NO_BASELINE, BASELINE_UNKNOWN, TARGET_UNKNOWN, INVALID_SCOPE,
}

data class WifiChannelAccessPoint(
    /** Latest session's cumulative observation; counts from successive saves are never summed. */
    val observation: WifiChannelObservation,
    val isRecent: Boolean,
    val isAddedAddress: Boolean?,
    /** Frequencies most recently observed for the same BSSID in the baseline. Not physical motion. */
    val movedFromFrequenciesMhz: List<Int>,
    val latestChannelAmbiguous: Boolean = false,
    /** True if this BSSID/frequency had a hidden SSID in any saved session in the selected interval. */
    val hasHiddenHistory: Boolean = observation.hidden || observation.everHidden,
)

data class WifiChannelSummary(
    val frequencyMhz: Int,
    val baselineAPCount: Int?,
    val recentAPCount: Int,
    val observedAPCount: Int,
    val addedAPCount: Int?,
    val isNewChannel: Boolean?,
    val movedAPCount: Int?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val peakRssi: Int?,
    val accessPoints: List<WifiChannelAccessPoint>,
) {
    val band: WifiBand get() = wifiBand(frequencyMhz)
    val channelNumber: Int? get() = wifiChannelNumber(frequencyMhz)
    val label: String get() = wifiChannelLabel(frequencyMhz)
    val hiddenAPCount: Int get() = accessPoints.count { it.hasHiddenHistory }
    val recentHiddenAPCount: Int get() = accessPoints.count { it.isRecent && it.observation.hidden }
    val addedHiddenAPCount: Int? get() = addedAPCount?.let { accessPoints.count { it.isAddedAddress == true && it.hasHiddenHistory } }
}

data class WifiChannelAnalysis(
    val channels: List<WifiChannelSummary>,
    val comparisonState: WifiChannelComparisonState,
    val message: String,
    val scopeStartMs: Long,
    val capturedAtMs: Long,
    val ambiguousCurrentAddressCount: Int,
    /** False is unknown legacy data, which must not be presented as a measured zero. */
    val targetChannelDataAvailable: Boolean = true,
) {
    val canCompare: Boolean get() = comparisonState == WifiChannelComparisonState.READY
    val hiddenAPCount: Int get() = hiddenAddresses(onlyAdded = false).size
    val addedHiddenAPCount: Int? get() = if (canCompare) hiddenAddresses(onlyAdded = true).size else null
    val recentHiddenAPCount: Int get() = channels.flatMap { it.accessPoints }.filter { it.isRecent && it.observation.hidden }
        .map { addressKey(it.observation.address) }.distinct().size

    private fun hiddenAddresses(onlyAdded: Boolean): Set<String> = channels.flatMap { it.accessPoints }
        .filter { it.hasHiddenHistory && (!onlyAdded || it.isAddedAddress == true) }.map { addressKey(it.observation.address) }.toSet()
}

fun wifiBand(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
    in 2400..2500 -> WifiBand.GHZ_2_4
    in 5000..5895 -> WifiBand.GHZ_5
    in 5935..7115 -> WifiBand.GHZ_6
    else -> WifiBand.OTHER
}

fun wifiChannelNumber(frequencyMhz: Int): Int? = when {
    frequencyMhz == 2484 -> 14
    frequencyMhz in 2412..2472 && (frequencyMhz - 2407) % 5 == 0 -> (frequencyMhz - 2407) / 5
    frequencyMhz == 5935 -> 2
    frequencyMhz in 5955..7115 && (frequencyMhz - 5950) % 5 == 0 -> (frequencyMhz - 5950) / 5
    frequencyMhz in 5005..5895 && frequencyMhz % 5 == 0 -> (frequencyMhz - 5000) / 5
    else -> null
}

fun wifiChannelLabel(frequencyMhz: Int): String {
    val band = wifiBand(frequencyMhz)
    if (band == WifiBand.OTHER) return "$frequencyMhz MHz"
    return wifiChannelNumber(frequencyMhz)?.let { "${band.label} · 채널 $it" } ?: "${band.label} · $frequencyMhz MHz"
}

/** A frequency is a channel identity: equal channel numbers in different bands stay separate.
 * Only records captured within the selected interval are read; later archives cannot alter a saved view.
 */
fun analyzeWifiChannels(baseline: SignalSnapshot?, target: SignalSnapshot, archive: List<SignalSnapshot> = emptyList()): WifiChannelAnalysis {
    val state = comparisonState(baseline, target)
    val validBaseline = baseline?.takeUnless { state == WifiChannelComparisonState.INVALID_SCOPE }
    val start = validBaseline?.capturedAtMs ?: target.scanStartedAtMs.coerceAtMost(target.capturedAtMs)
    val snapshots = (archive + listOfNotNull(validBaseline) + target).filter {
        it.area.equals(target.area, true) && it.simulation == target.simulation && it.capturedAtMs in start..target.capturedAtMs
    }
    val observations = snapshots.flatMap { snapshot -> validObservations(snapshot).map { it to snapshot.capturedAtMs } }
    val baselineObservations = validBaseline?.let(::validObservations).orEmpty()
    val baselineLatest = latestChannelsByAddress(baselineObservations)
    val baselineAddresses = baselineLatest.keys + validBaseline?.signals.orEmpty()
        .filter { it.source == ScanSource.WIFI }.map { addressKey(it.address) }
    val targetLatest = latestChannelsByAddress(validObservations(target))
    val comparable = state == WifiChannelComparisonState.READY
    val channels = observations.groupBy { it.first.frequencyMhz }.toSortedMap().map { (frequency, copies) ->
        val versionsByAddress = copies.groupBy { addressKey(it.first.address) }
        val points = versionsByAddress.values.map { versions ->
            val observation = versions.maxWith(compareBy<Pair<WifiChannelObservation, Long>> { it.first.lastSeenMs }
                .thenBy { it.second }.thenBy { it.first.observationCount }).first
            val address = addressKey(observation.address)
            val latest = targetLatest[address]
            val ambiguous = latest != null && latest.size > 1 && isRecent(latest.first().lastSeenMs, target.capturedAtMs)
            val recent = latest?.singleOrNull()?.let { it.frequencyMhz == frequency && isRecent(it.lastSeenMs, target.capturedAtMs) } == true
            val prior = baselineLatest[address].orEmpty().map { it.frequencyMhz }.distinct().sorted()
            val movedFrom = if (comparable && validBaseline != null && observation.lastSeenMs > validBaseline.capturedAtMs &&
                frequency !in prior) prior else emptyList()
            val hiddenHistory = versions.any { it.first.hidden || it.first.everHidden }
            WifiChannelAccessPoint(observation, recent, if (comparable) address !in baselineAddresses else null, movedFrom, ambiguous, hiddenHistory)
        }.sortedWith(compareByDescending<WifiChannelAccessPoint> { it.isRecent }.thenByDescending { it.observation.lastSeenMs }
            .thenBy { addressKey(it.observation.address) })
        val baselineCount = baselineObservations.filter { it.frequencyMhz == frequency }.map { addressKey(it.address) }.distinct().size
        WifiChannelSummary(
            frequencyMhz = frequency,
            baselineAPCount = if (comparable) baselineCount else null,
            recentAPCount = points.count { it.isRecent },
            observedAPCount = points.size,
            addedAPCount = if (comparable) points.count { it.isAddedAddress == true } else null,
            isNewChannel = if (comparable) baselineCount == 0 else null,
            movedAPCount = if (comparable) points.count { it.movedFromFrequenciesMhz.isNotEmpty() } else null,
            firstSeenMs = copies.minOf { it.first.firstSeenMs },
            lastSeenMs = copies.maxOf { it.first.lastSeenMs },
            peakRssi = copies.mapNotNull { it.first.peakRssi }.maxOrNull(),
            accessPoints = points,
        )
    }
    return WifiChannelAnalysis(channels, state, comparisonMessage(state), start, target.capturedAtMs,
        targetLatest.values.count { it.size > 1 && isRecent(it.first().lastSeenMs, target.capturedAtMs) },
        targetChannelDataAvailable = target.wifiChannels != null)
}

private fun comparisonState(baseline: SignalSnapshot?, target: SignalSnapshot): WifiChannelComparisonState = when {
    baseline != null && (!baseline.area.equals(target.area, true) || baseline.simulation != target.simulation || baseline.capturedAtMs > target.capturedAtMs) -> WifiChannelComparisonState.INVALID_SCOPE
    target.wifiChannels == null -> WifiChannelComparisonState.TARGET_UNKNOWN
    baseline == null -> WifiChannelComparisonState.NO_BASELINE
    baseline.wifiChannels == null -> WifiChannelComparisonState.BASELINE_UNKNOWN
    else -> WifiChannelComparisonState.READY
}

private fun comparisonMessage(state: WifiChannelComparisonState): String = when (state) {
    WifiChannelComparisonState.READY -> "기준과 비교할 수 있습니다. 신규 채널·추가 AP·같은 주소의 채널 변경을 구분합니다."
    WifiChannelComparisonState.NO_BASELINE -> "입실 전 기준을 저장하면 신규 채널과 추가 AP를 구분합니다."
    WifiChannelComparisonState.BASELINE_UNKNOWN -> "기준에 주파수 기록이 없습니다. 새 기준을 저장해야 신규 채널을 판단할 수 있습니다."
    WifiChannelComparisonState.TARGET_UNKNOWN -> "대상에 주파수 기록이 없습니다. 채널 자료가 있는 시점을 선택하세요."
    WifiChannelComparisonState.INVALID_SCOPE -> "같은 공간·같은 모드의 기준 이후 시점을 선택하세요. 현재 대상의 채널 이력만 표시합니다."
}

private fun validObservations(snapshot: SignalSnapshot): List<WifiChannelObservation> = snapshot.wifiChannels.orEmpty().filter {
    it.frequencyMhz > 0 && it.address.isNotBlank() && it.observationCount > 0 && it.firstSeenMs <= it.lastSeenMs && it.lastSeenMs <= snapshot.capturedAtMs
}

/** Tied frequencies retain ambiguity instead of inventing a current channel or double counting it. */
private fun latestChannelsByAddress(observations: List<WifiChannelObservation>): Map<String, List<WifiChannelObservation>> = observations
    .groupBy { addressKey(it.address) }.mapValues { (_, rows) ->
        val lastSeen = rows.maxOf { it.lastSeenMs }
        rows.filter { it.lastSeenMs == lastSeen }.distinctBy { it.frequencyMhz }
    }

private fun isRecent(lastSeenMs: Long, nowMs: Long): Boolean = nowMs - lastSeenMs in 0..90_000L

private fun addressKey(address: String): String = address.uppercase(Locale.ROOT)
