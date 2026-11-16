package com.classroom.rfdetector.core

import java.util.Locale

/** One observed AP address, not an Internet connection, client phone, or physical-device identity. */
data class WifiNetwork(
    val address: String,
    val name: String?,
    val frequencyMhz: Int?,
    val frequencyCandidatesMhz: List<Int>,
    val latestRssi: Int?,
    val peakRssi: Int?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** Latest single frequency/session's cumulative count; never summed across saves or channels. */
    val observationCount: Int?,
    /** Latest observation's hidden state, or null when tied candidates disagree. */
    val hidden: Boolean?,
    val hasHiddenHistory: Boolean,
    val isAddedAddress: Boolean?,
    /** Null when the target lacks channel data; false must not stand in for unavailable evidence. */
    val isRecent: Boolean?,
    val movedFromFrequenciesMhz: List<Int>,
    val wifiMetadata: WifiRadioMetadata?,
    val latestCandidates: List<WifiChannelAccessPoint>,
) {
    val band: WifiBand? get() = frequencyMhz?.let(::wifiBand)
    val channelNumber: Int? get() = frequencyMhz?.let(::wifiChannelNumber)
    val channelAmbiguous: Boolean get() = frequencyCandidatesMhz.size > 1
    fun matchesBand(band: WifiBand): Boolean = frequencyCandidatesMhz.any { wifiBand(it) == band }
}

data class WifiNetworkAnalysis(
    val networks: List<WifiNetwork>,
    val targetDataAvailable: Boolean,
    val capturedAtMs: Long,
    val scopeStartMs: Long,
    val canCompare: Boolean,
    val comparisonMessage: String,
) {
    /** Recent addresses with one known latest primary frequency; uncertain channels are separate. */
    val recentNetworks: List<WifiNetwork> get() = networks.filter { it.isRecent == true && !it.channelAmbiguous }
    val historyNetworks: List<WifiNetwork> get() = networks.filter { it.isRecent == false && !it.channelAmbiguous }
    val ambiguousNetworks: List<WifiNetwork> get() = networks.filter { it.channelAmbiguous }
    val unknownNetworks: List<WifiNetwork> get() = networks.filter { it.isRecent == null && !it.channelAmbiguous }
    val observedAPCount: Int get() = networks.size
    val recentAPCount: Int? get() = if (targetDataAvailable) recentNetworks.size else null
    val recentAmbiguousAPCount: Int? get() = if (targetDataAvailable) ambiguousNetworks.count { it.isRecent == true } else null
    val hiddenHistoryAPCount: Int get() = networks.count { it.hasHiddenHistory }
    val addedAPCount: Int? get() = if (canCompare) networks.count { it.isAddedAddress == true } else null
    fun networksForBand(band: WifiBand): List<WifiNetwork> = networks.filter { it.matchesBand(band) }
}

/** Select latest observations globally before any band filter, so an old channel cannot revive. */
fun analyzeWifiNetworks(channels: WifiChannelAnalysis): WifiNetworkAnalysis {
    val networks = channels.channels.flatMap { it.accessPoints }
        .groupBy { it.observation.address.uppercase(Locale.ROOT) }
        .map { (address, copies) ->
            val perFrequency = copies.groupBy { it.observation.frequencyMhz }.values.map { versions ->
                versions.maxWith(compareBy<WifiChannelAccessPoint> { it.observation.lastSeenMs }
                    .thenBy { it.observation.observationCount }.thenBy { it.observation.name })
            }
            val latestTime = perFrequency.maxOf { it.observation.lastSeenMs }
            val latest = perFrequency.filter { it.observation.lastSeenMs == latestTime }.sortedBy { it.observation.frequencyMhz }
            val single = latest.singleOrNull()
            val names = latest.map { point -> point.observation.name.takeIf { it.isNotBlank() && !point.observation.hidden } }.distinct()
            val addedStates = copies.map { it.isAddedAddress }.distinct()
            val hiddenStates = latest.map { it.observation.hidden }.distinct()
            WifiNetwork(
                address = address,
                name = names.singleOrNull(),
                frequencyMhz = single?.observation?.frequencyMhz,
                frequencyCandidatesMhz = latest.map { it.observation.frequencyMhz },
                latestRssi = single?.observation?.lastRssi.validWifiRssi(),
                peakRssi = copies.mapNotNull { it.scopePeakRssi.validWifiRssi() }.maxOrNull(),
                firstSeenMs = copies.minOf { it.scopeFirstSeenMs },
                lastSeenMs = latestTime,
                observationCount = single?.observation?.observationCount,
                hidden = hiddenStates.singleOrNull(),
                hasHiddenHistory = copies.any { it.hasHiddenHistory || it.observation.hidden || it.observation.everHidden },
                isAddedAddress = if (channels.canCompare) addedStates.singleOrNull() else null,
                isRecent = if (!channels.targetChannelDataAvailable) null else latest.any { it.isRecent || it.latestChannelAmbiguous },
                movedFromFrequenciesMhz = latest.flatMap { it.movedFromFrequenciesMhz }.distinct().sorted(),
                wifiMetadata = single?.observation?.wifiMetadata,
                latestCandidates = latest,
            )
        }.sortedWith(compareByDescending<WifiNetwork> { it.isRecent == true }
            .thenBy { it.channelAmbiguous }.thenByDescending { it.latestRssi ?: Int.MIN_VALUE }
            .thenByDescending { it.lastSeenMs }.thenBy { it.address })
    return WifiNetworkAnalysis(networks, channels.targetChannelDataAvailable, channels.capturedAtMs,
        channels.scopeStartMs, channels.canCompare, channels.message)
}

data class WifiNetworkPlotTick(val channel: Int?, val label: String, val frequencyMhz: Int, val xFraction: Float)

data class WifiNetworkPlotPoint(
    val network: WifiNetwork,
    val frequencyMhz: Int,
    /** Actual reported RSSI remains available even when its plotted coordinate is clamped. */
    val rssiDbm: Int,
    val xFraction: Float,
    /** 0 is the strongest/top end, 1 is the weakest/bottom end. */
    val yFraction: Float,
    val rssiClamped: Boolean,
)

data class WifiNetworkPlot(
    val band: WifiBand,
    val frequencyMinMhz: Int,
    val frequencyMaxMhz: Int,
    val rssiMinDbm: Int,
    val rssiMaxDbm: Int,
    val ticks: List<WifiNetworkPlotTick>,
    val points: List<WifiNetworkPlotPoint>,
    val unknownRssiAPCount: Int?,
    val targetDataAvailable: Boolean,
)

/**
 * AP primary-frequency/RSSI markers, not a measured RF spectrum, channel occupancy or bandwidth
 * shape. Only recent, unambiguous addresses are plotted. Equal coordinates intentionally overlap.
 * OTHER has no standard band window, so only that axis follows its latest observed frequencies.
 */
fun wifiNetworkPlot(analysis: WifiNetworkAnalysis, band: WifiBand): WifiNetworkPlot {
    val networks = analysis.recentNetworks.filter { it.band == band }
    val domain = when (band) {
        WifiBand.GHZ_2_4 -> 2400..2500
        WifiBand.GHZ_5 -> 5000..5900
        WifiBand.GHZ_6 -> 5925..7125
        WifiBand.OTHER -> analysis.networksForBand(band).mapNotNull { it.frequencyMhz }.let { frequencies ->
            if (frequencies.isEmpty()) 0..1 else
                (frequencies.min().toLong() - 10).coerceAtLeast(0).toInt()..
                    (frequencies.max().toLong() + 10).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
    val tickFrequencies = when (band) {
        WifiBand.GHZ_2_4 -> listOf(2412, 2437, 2462, 2484)
        WifiBand.GHZ_5 -> listOf(5180, 5260, 5500, 5580, 5660, 5745, 5825)
        WifiBand.GHZ_6 -> listOf(5955, 6115, 6275, 6435, 6595, 6755, 6915, 7115)
        WifiBand.OTHER -> listOf(domain.first, domain.last)
    }
    val rssiRange = -100..-20
    val points = networks.mapNotNull { network ->
        val rssi = network.latestRssi ?: return@mapNotNull null
        val frequency = network.frequencyMhz ?: return@mapNotNull null
        WifiNetworkPlotPoint(network, frequency, rssi, fraction(frequency, domain),
            1f - fraction(rssi, rssiRange), rssi !in rssiRange)
    }
    return WifiNetworkPlot(band, domain.first, domain.last, rssiRange.first, rssiRange.last,
        tickFrequencies.map { frequency -> WifiNetworkPlotTick(wifiChannelNumber(frequency),
            wifiChannelNumber(frequency)?.toString() ?: "$frequency MHz", frequency, fraction(frequency, domain)) },
        points, if (analysis.targetDataAvailable) networks.count { it.latestRssi == null } else null,
        analysis.targetDataAvailable)
}

private fun Int?.validWifiRssi(): Int? = this?.takeIf { it in -127..-1 }
private fun fraction(value: Int, range: IntRange): Float =
    ((value.toLong() - range.first).toDouble() / (range.last.toLong() - range.first).coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
