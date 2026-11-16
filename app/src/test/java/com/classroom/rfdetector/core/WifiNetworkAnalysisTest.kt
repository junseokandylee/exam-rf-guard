package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class WifiNetworkAnalysisTest {
    @Test fun sameSsidKeepsEachBssidAndLatestChannelSelectionPrecedesBandFiltering() {
        val before = snapshot("before", 1_000, listOf(row("aa", 2412, 1_000)))
        val target = snapshot("after", 2_000, listOf(
            row("AA", 2412, 1_000), row("aa", 5180, 2_000), row("bb", 2412, 2_000),
        ))
        val result = analyzeWifiNetworks(analyzeWifiChannels(before, target))
        assertEquals(2, result.observedAPCount)
        assertEquals(2, result.recentAPCount)
        assertEquals(1, result.addedAPCount)
        assertEquals(listOf("BB"), result.networksForBand(WifiBand.GHZ_2_4).map { it.address })
        val moved = result.networks.single { it.address == "AA" }
        assertEquals("Shared SSID", moved.name)
        assertEquals(5180, moved.frequencyMhz)
        assertEquals(listOf(2412), moved.movedFromFrequenciesMhz)
        assertEquals(false, moved.isAddedAddress)
        assertEquals(listOf("BB"), wifiNetworkPlot(result, WifiBand.GHZ_2_4).points.map { it.network.address })
        assertEquals(listOf("AA"), wifiNetworkPlot(result, WifiBand.GHZ_5).points.map { it.network.address })
    }

    @Test fun firstAndLastTimesComeFromAddressHistoryButCountAndRssiComeFromLatestChannel() {
        val old = row("aa", 2412, 2_000, count = 7, first = 100, rssi = -40)
        val latest = row("AA", 5180, 3_000, count = 2, first = 2_500, rssi = -70)
        val metadata = WifiRadioMetadata(channelWidthCode = 2, centerFrequency0Mhz = 5210)
        val result = networks(snapshot("target", 3_000, listOf(old, latest.copy(wifiMetadata = metadata))))
        val network = result.networks.single()
        assertEquals(100L, network.firstSeenMs)
        assertEquals(3_000L, network.lastSeenMs)
        assertEquals(2, network.observationCount)
        assertEquals(-70, network.latestRssi)
        assertEquals(-40, network.peakRssi)
        assertEquals(metadata, network.wifiMetadata)
        assertEquals(80, network.wifiMetadata!!.channelWidthMhz)
    }

    @Test fun cumulativeCopiesNeverSumCountsAndAreNotMutated() {
        val original = row("aa", 2412, 1_000, count = 3)
        val newer = original.copy(lastSeenMs = 2_000, observationCount = 9)
        val first = snapshot("auto", 1_000, listOf(original))
        val target = snapshot("auto", 2_000, listOf(newer))
        val analysis = analyzeWifiChannels(snapshot("baseline", 0, emptyList()), target, listOf(first, first, target))
        val result = analyzeWifiNetworks(analysis)
        assertEquals(1, result.observedAPCount)
        assertEquals(9, result.networks.single().observationCount)
        assertEquals(3, original.observationCount)
        assertEquals(1_000L, original.lastSeenMs)
        assertEquals(listOf(newer), target.wifiChannels)
    }

    @Test fun hiddenHistorySurvivesChannelMoveButCurrentHiddenFlagAndSsidFollowLatestObservation() {
        val hidden = row("aa", 2412, 1_000).copy(hidden = true, everHidden = true)
        val visible = row("AA", 5180, 2_000).copy(hidden = false, everHidden = false)
        val result = networks(snapshot("target", 2_000, listOf(hidden, visible)))
        val network = result.networks.single()
        assertTrue(network.hasHiddenHistory)
        assertEquals(false, network.hidden)
        assertEquals("Shared SSID", network.name)
        assertEquals(1, result.hiddenHistoryAPCount)
        val nowHidden = networks(snapshot("target", 3_000, listOf(visible, hidden.copy(lastSeenMs = 3_000))))
        assertEquals(true, nowHidden.networks.single().hidden)
        assertNull(nowHidden.networks.single().name)
    }

    @Test fun equalLatestTimesDoNotInventAChannelRssiCountOrBandwidth() {
        val target = snapshot("target", 2_000, listOf(
            row("aa", 2412, 2_000, rssi = -40).copy(hidden = true),
            row("AA", 5180, 2_000, rssi = -70).copy(wifiMetadata = WifiRadioMetadata(channelWidthCode = 2)),
        ))
        val result = networks(target)
        val uncertain = result.ambiguousNetworks.single()
        assertEquals(listOf(2412, 5180), uncertain.frequencyCandidatesMhz)
        assertNull(uncertain.frequencyMhz)
        assertNull(uncertain.latestRssi)
        assertNull(uncertain.observationCount)
        assertNull(uncertain.wifiMetadata)
        assertNull(uncertain.hidden)
        assertNull(uncertain.name)
        assertEquals(true, uncertain.isRecent)
        assertEquals(0, result.recentAPCount)
        assertEquals(1, result.recentAmbiguousAPCount)
        assertTrue(result.recentNetworks.isEmpty())
        assertTrue(wifiNetworkPlot(result, WifiBand.GHZ_2_4).points.isEmpty())
        assertTrue(wifiNetworkPlot(result, WifiBand.GHZ_5).points.isEmpty())
    }

    @Test fun historicalAmbiguityDoesNotTurnIntoRecentReception() {
        val target = snapshot("target", 100_001, listOf(row("aa", 2412, 10_000), row("AA", 5180, 10_000)))
        val result = networks(target)
        assertEquals(false, result.ambiguousNetworks.single().isRecent)
        assertEquals(0, result.recentAmbiguousAPCount)
    }

    @Test fun transientAndArchiveOnlyAddressesStayHistoryEvenWhenTheirAgeIsWithinNinetySeconds() {
        val before = snapshot("before", 0, emptyList())
        val archive = snapshot("brief", 1_000, listOf(row("aa", 2412, 1_000)))
        val target = snapshot("target", 2_000, emptyList())
        val result = analyzeWifiNetworks(analyzeWifiChannels(before, target, listOf(archive)))
        assertEquals(listOf("AA"), result.historyNetworks.map { it.address })
        assertEquals(0, result.recentAPCount)
        assertTrue(wifiNetworkPlot(result, WifiBand.GHZ_2_4).points.isEmpty())
    }

    @Test fun recentBoundaryComesFromTargetEvidenceRatherThanJustTheUnionTimestamp() {
        val result = networks(snapshot("target", 100_000, listOf(row("aa", 2412, 10_000), row("bb", 2437, 9_999))))
        assertEquals(listOf("AA"), result.recentNetworks.map { it.address })
        assertEquals(listOf("BB"), result.historyNetworks.map { it.address })
    }

    @Test fun legacyTargetIsUnknownWhileKnownEmptyTargetHasMeasuredZeroRecentAddresses() {
        val before = snapshot("before", 1_000, listOf(row("aa", 2412, 1_000)))
        val legacy = analyzeWifiNetworks(analyzeWifiChannels(before, snapshot("legacy", 2_000, null)))
        assertFalse(legacy.targetDataAvailable)
        assertNull(legacy.recentAPCount)
        assertNull(legacy.recentAmbiguousAPCount)
        assertNull(legacy.networks.single().isRecent)
        assertEquals(1, legacy.unknownNetworks.size)
        assertTrue(legacy.historyNetworks.isEmpty())
        val plot = wifiNetworkPlot(legacy, WifiBand.GHZ_2_4)
        assertNull(plot.unknownRssiAPCount)
        assertTrue(plot.points.isEmpty())
        val empty = networks(snapshot("empty", 2_000, emptyList()))
        assertTrue(empty.targetDataAvailable)
        assertEquals(0, empty.recentAPCount)
        assertEquals(0, wifiNetworkPlot(empty, WifiBand.GHZ_2_4).unknownRssiAPCount)
    }

    @Test fun absentBaselineAndInvalidScopeNeverDeclareAddedAddresses() {
        val target = snapshot("target", 2_000, listOf(row("aa", 2412, 2_000)))
        val noBaseline = analyzeWifiNetworks(analyzeWifiChannels(null, target))
        assertFalse(noBaseline.canCompare)
        assertNull(noBaseline.addedAPCount)
        assertNull(noBaseline.networks.single().isAddedAddress)
        val badBaseline = snapshot("baseline", 1_000, emptyList()).copy(area = "Another room")
        val invalid = analyzeWifiNetworks(analyzeWifiChannels(badBaseline, target))
        assertFalse(invalid.canCompare)
        assertNull(invalid.networks.single().isAddedAddress)
        assertEquals(target.capturedAtMs, invalid.capturedAtMs)
    }

    @Test fun plotUsesPrimaryFrequencyNotCenterFrequencyOrAComputedBandwidthShape() {
        val metadata = WifiRadioMetadata(channelWidthCode = 2, centerFrequency0Mhz = 5210)
        val result = networks(snapshot("target", 2_000, listOf(row("aa", 5180, 2_000).copy(wifiMetadata = metadata))))
        val plot = wifiNetworkPlot(result, WifiBand.GHZ_5)
        val point = plot.points.single()
        assertEquals(5180, point.frequencyMhz)
        assertEquals(0.2f, point.xFraction, 0.00001f)
        assertEquals(0.5f, point.yFraction, 0.00001f)
        assertFalse(point.rssiClamped)
        assertEquals(36, plot.ticks.first().channel)
        assertEquals(5000, plot.frequencyMinMhz)
        assertEquals(5900, plot.frequencyMaxMhz)
    }

    @Test fun unknownRssiIsExcludedButOutOfAxisValidRssiRemainsExplicitlyClamped() {
        val result = networks(snapshot("target", 2_000, listOf(
            row("aa", 2412, 2_000, rssi = null), row("bb", 2437, 2_000, rssi = -120),
            row("cc", 2462, 2_000, rssi = -10), row("dd", 2484, 2_000, rssi = 127),
        )))
        val plot = wifiNetworkPlot(result, WifiBand.GHZ_2_4)
        assertEquals(2, plot.unknownRssiAPCount)
        assertEquals(2, plot.points.size)
        assertTrue(plot.points.all { it.rssiClamped })
        assertEquals(1f, plot.points.single { it.rssiDbm == -120 }.yFraction, 0f)
        assertEquals(0f, plot.points.single { it.rssiDbm == -10 }.yFraction, 0f)
    }

    @Test fun sameCoordinatesAreNotJitteredAndEqualChannelNumbersInDifferentBandsStaySeparate() {
        val result = networks(snapshot("target", 2_000, listOf(
            row("aa", 2412, 2_000), row("bb", 2412, 2_000), row("cc", 5955, 2_000),
        )))
        val low = wifiNetworkPlot(result, WifiBand.GHZ_2_4)
        assertEquals(2, low.points.size)
        assertEquals(low.points[0].xFraction, low.points[1].xFraction)
        assertEquals(low.points[0].yFraction, low.points[1].yFraction)
        val high = wifiNetworkPlot(result, WifiBand.GHZ_6)
        assertEquals("CC", high.points.single().network.address)
        assertEquals(1, low.points.first().network.channelNumber)
        assertEquals(1, high.points.first().network.channelNumber)
    }

    @Test fun unknownBandAxisHasFiniteCoordinatesEvenAtIntegerFrequencyLimit() {
        val result = networks(snapshot("target", 2_000, listOf(row("aa", Int.MAX_VALUE, 2_000))))
        val plot = wifiNetworkPlot(result, WifiBand.OTHER)
        assertTrue(plot.frequencyMaxMhz > plot.frequencyMinMhz)
        assertTrue(plot.points.single().xFraction.isFinite())
        assertTrue(plot.points.single().xFraction in 0f..1f)
    }

    @Test fun addressFirstAndPeakIncludeEarlierSessionsOnTheSameFrequency() {
        val before = snapshot("before", 0, emptyList())
        val old = row("aa", 2412, 1500, count = 7, first = 500, rssi = -30)
        val latest = row("AA", 2412, 2500, count = 2, first = 2000, rssi = -80)
        val result = analyzeWifiNetworks(analyzeWifiChannels(before, snapshot("target", 3000, listOf(latest)),
            listOf(snapshot("old-session", 1600, listOf(old)))))
        val network = result.networks.single()
        assertEquals(500L, network.firstSeenMs)
        assertEquals(2500L, network.lastSeenMs)
        assertEquals(-30, network.peakRssi)
        assertEquals(-80, network.latestRssi)
        assertEquals(2, network.observationCount)
        assertEquals(latest, network.latestCandidates.single().observation)
    }

    @Test fun addressScopeDoesNotExpandToFutureOtherAreaOtherModeOrPreBaselineArchive() {
        val before = snapshot("before", 1000, emptyList())
        val valid = snapshot("valid", 2000, listOf(row("aa", 2412, 1900, first = 1500, rssi = -50)))
        val excluded = row("aa", 2412, 900, first = 100, rssi = -5)
        val target = snapshot("target", 4000, listOf(row("AA", 2412, 3900, first = 3500, rssi = -80)))
        val archive = listOf(valid,
            snapshot("earlier", 999, listOf(excluded)),
            snapshot("future", 4001, listOf(excluded)),
            snapshot("other-area", 2500, listOf(excluded)).copy(area = "Other room"),
            snapshot("other-mode", 2500, listOf(excluded)).copy(simulation = true))
        val network = analyzeWifiNetworks(analyzeWifiChannels(before, target, archive)).networks.single()
        assertEquals(1500L, network.firstSeenMs)
        assertEquals(-50, network.peakRssi)
        assertEquals(3900L, network.lastSeenMs)
        assertEquals(-80, network.latestRssi)
    }

    private fun row(address: String, frequency: Int, at: Long, count: Int = 1, first: Long = at, rssi: Int? = -60) =
        WifiChannelObservation(address, "Shared SSID", frequency, first, at, count, rssi, rssi)

    private fun snapshot(id: String, at: Long, rows: List<WifiChannelObservation>?) =
        SignalSnapshot(id, "Room", id, at, 0, false, emptyList(), wifiChannels = rows)

    private fun networks(target: SignalSnapshot): WifiNetworkAnalysis =
        analyzeWifiNetworks(analyzeWifiChannels(snapshot("before", 0, emptyList()), target))
}
