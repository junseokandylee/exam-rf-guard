package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class WifiChannelAnalysisTest {
    @Test fun channelNumbersKeepBandsAndSpecialFrequenciesSeparate() {
        assertEquals(1, wifiChannelNumber(2412))
        assertEquals(14, wifiChannelNumber(2484))
        assertEquals(36, wifiChannelNumber(5180))
        assertEquals(2, wifiChannelNumber(5935))
        assertEquals(1, wifiChannelNumber(5955))
        assertEquals(233, wifiChannelNumber(7115))
        assertNull(wifiChannelNumber(2413))
        assertNull(wifiChannelNumber(5000))
        assertNull(wifiChannelNumber(4000))
        assertEquals("4000 MHz", wifiChannelLabel(4000))
        assertEquals("6 GHz · 채널 1", wifiChannelLabel(5955))
        val target = snapshot("target", 100, listOf(observation("a", 2412, 100), observation("b", 5005, 100), observation("c", 5955, 100)))
        val channels = analyzeWifiChannels(snapshot("before", 0, emptyList()), target).channels
        assertEquals(listOf(2412, 5005, 5955), channels.map { it.frequencyMhz })
        assertEquals(listOf(1, 1, 1), channels.map { it.channelNumber })
        assertEquals(listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_6), channels.map { it.band })
    }

    @Test fun sameBssidOnNewChannelIsAMoveRatherThanAnAddedAddress() {
        val before = snapshot("before", 1000, listOf(observation("aa:bb", 2412, 1000)))
        val target = snapshot("after", 2000, listOf(observation("AA:BB", 2412, 1000), observation("AA:BB", 2437, 2000)))
        val result = analyzeWifiChannels(before, target)
        assertTrue(result.canCompare)
        assertEquals(0, result.channel(2412).recentAPCount)
        val moved = result.channel(2437)
        assertEquals(1, moved.recentAPCount)
        assertEquals(true, moved.isNewChannel)
        assertEquals(0, moved.addedAPCount)
        assertEquals(1, moved.movedAPCount)
        assertEquals(listOf(2412), moved.accessPoints.single().movedFromFrequenciesMhz)
        assertEquals(false, moved.accessPoints.single().isAddedAddress)
    }

    @Test fun addedAccessPointOnExistingChannelDoesNotMakeChannelNew() {
        val before = snapshot("before", 1000, listOf(observation("a", 5180, 1000)))
        val target = snapshot("after", 2000, listOf(observation("A", 5180, 2000), observation("b", 5180, 2000)))
        val channel = analyzeWifiChannels(before, target).channels.single()
        assertEquals(1, channel.baselineAPCount)
        assertEquals(2, channel.recentAPCount)
        assertEquals(2, channel.observedAPCount)
        assertEquals(false, channel.isNewChannel)
        assertEquals(1, channel.addedAPCount)
        assertEquals(0, channel.movedAPCount)
    }

    @Test fun addressKnownWithoutFrequencyIsNotAnAddedAccessPoint() {
        val known = SnapshotSignal("AA:BB", ScanSource.WIFI, "known AP", -50.0, 0, 1000, 2, "미확인", null)
        val before = snapshot("before", 1000, emptyList()).copy(signals = listOf(known))
        val target = snapshot("after", 2000, listOf(observation("aa:bb", 2437, 2000)))
        val channel = analyzeWifiChannels(before, target).channels.single()
        assertEquals(true, channel.isNewChannel)
        assertEquals(0, channel.addedAPCount)
        assertEquals(0, channel.movedAPCount)
        assertEquals(false, channel.accessPoints.single().isAddedAddress)
        assertTrue(channel.accessPoints.single().movedFromFrequenciesMhz.isEmpty())
    }

    @Test fun brieflyObservedChannelRemainsInHistoryAfterItDisappearsFromTarget() {
        val before = snapshot("before", 1000, listOf(observation("a", 2412, 1000)))
        val brief = snapshot("brief", 2000, listOf(observation("b", 5745, 1500)))
        val target = snapshot("after", 200000, listOf(observation("a", 2412, 200000)))
        val result = analyzeWifiChannels(before, target, listOf(brief))
        val historical = result.channel(5745)
        assertEquals(1, historical.observedAPCount)
        assertEquals(0, historical.recentAPCount)
        assertEquals(1, historical.addedAPCount)
        assertEquals(true, historical.isNewChannel)
        assertEquals(1500L, historical.firstSeenMs)
        assertEquals(1500L, historical.lastSeenMs)
        assertEquals(1, historical.accessPoints.single().observation.observationCount)
    }

    @Test fun cumulativeSnapshotsAndRepeatedArchiveEntriesNeverSumObservationCounts() {
        val before = snapshot("before", 0, emptyList())
        val earlier = snapshot("auto", 2000, listOf(observation("a", 2412, 2000, 3, first = 1000, peak = -40)))
        val latest = snapshot("auto", 3000, listOf(observation("a", 2412, 3000, 7, first = 1000, peak = -40)))
        val result = analyzeWifiChannels(before, latest, listOf(earlier, earlier, latest))
        val point = result.channels.single().accessPoints.single().observation
        assertEquals(7, point.observationCount)
        assertEquals(listOf(ObservationPeriod(1000, 3000, 7)), point.periods)
        assertEquals(1, result.channels.single().observedAPCount)
    }

    @Test fun latestSessionCountsAreKeptWhileChannelWindowAndPeakCoverWholeScope() {
        val before = snapshot("before", 0, emptyList())
        val earlier = snapshot("first-session", 2000, listOf(observation("a", 2412, 2000, 8, first = 1000, peak = -30)))
        val target = snapshot("second-session", 5000, listOf(observation("a", 2412, 5000, 2, first = 4000, peak = -60))).copy(scanStartedAtMs = 4000)
        val channel = analyzeWifiChannels(before, target, listOf(earlier)).channels.single()
        assertEquals(2, channel.accessPoints.single().observation.observationCount)
        assertEquals(4000L, channel.accessPoints.single().observation.firstSeenMs)
        assertEquals(1000L, channel.firstSeenMs)
        assertEquals(5000L, channel.lastSeenMs)
        assertEquals(-30, channel.peakRssi)
    }

    @Test fun latestFrequencyTieIsExplicitlyAmbiguousAndNotDoubleCountedAsCurrent() {
        val before = snapshot("before", 0, emptyList())
        val target = snapshot("after", 1000, listOf(observation("a", 2412, 1000), observation("A", 2437, 1000)))
        val result = analyzeWifiChannels(before, target)
        assertEquals(1, result.ambiguousCurrentAddressCount)
        assertEquals(0, result.channels.sumOf { it.recentAPCount })
        assertEquals(2, result.channels.sumOf { it.observedAPCount })
        assertTrue(result.channels.flatMap { it.accessPoints }.all { it.latestChannelAmbiguous })
    }

    @Test fun recentCountRequiresTargetMembershipAndNinetySecondFreshness() {
        val before = snapshot("before", 0, emptyList())
        val archive = snapshot("middle", 1000, listOf(observation("not-in-target", 2412, 1000)))
        val target = snapshot("after", 100001, listOf(observation("boundary", 2412, 10001), observation("old", 2412, 10000)))
        val channel = analyzeWifiChannels(before, target, listOf(archive)).channels.single()
        assertEquals(1, channel.recentAPCount)
        assertEquals(3, channel.observedAPCount)
        assertEquals("boundary", channel.accessPoints.first { it.isRecent }.observation.address)
    }

    @Test fun unknownOrMissingBaselineNeverClaimsNewChannelsOrAddresses() {
        val target = snapshot("after", 2000, listOf(observation("a", 2412, 2000)))
        listOf(null, snapshot("legacy", 1000, null)).forEach { baseline ->
            val result = analyzeWifiChannels(baseline, target)
            assertFalse(result.canCompare)
            assertEquals(if (baseline == null) WifiChannelComparisonState.NO_BASELINE else WifiChannelComparisonState.BASELINE_UNKNOWN, result.comparisonState)
            val channel = result.channels.single()
            assertNull(channel.baselineAPCount)
            assertNull(channel.addedAPCount)
            assertNull(channel.isNewChannel)
            assertNull(channel.movedAPCount)
            assertNull(channel.accessPoints.single().isAddedAddress)
        }
        val legacyTarget = analyzeWifiChannels(snapshot("before", 0, emptyList()), target.copy(wifiChannels = null))
        assertEquals(WifiChannelComparisonState.TARGET_UNKNOWN, legacyTarget.comparisonState)
        assertFalse(legacyTarget.canCompare)
        assertFalse(legacyTarget.targetChannelDataAvailable)
        assertTrue(analyzeWifiChannels(null, target.copy(wifiChannels = emptyList())).targetChannelDataAvailable)
    }

    @Test fun invalidComparisonScopeReturnsStatusWithoutFailingTheAnalysisFlow() {
        val target = snapshot("after", 2000, listOf(observation("target", 2412, 2000)))
        val baseline = snapshot("before", 1000, listOf(observation("baseline", 5180, 1000)))
        listOf(baseline.copy(area = "other room"), baseline.copy(simulation = true), baseline.copy(capturedAtMs = 3000)).forEach {
            val result = analyzeWifiChannels(it, target)
            assertEquals(WifiChannelComparisonState.INVALID_SCOPE, result.comparisonState)
            assertFalse(result.canCompare)
            assertEquals(listOf(2412), result.channels.map { channel -> channel.frequencyMhz })
            assertNull(result.channels.single().isNewChannel)
        }
        val invalidLegacy = analyzeWifiChannels(baseline.copy(area = "other room"), target.copy(wifiChannels = null))
        assertEquals(WifiChannelComparisonState.INVALID_SCOPE, invalidLegacy.comparisonState)
        assertFalse(invalidLegacy.targetChannelDataAvailable)
    }

    @Test fun otherRoomsModesEarlierRecordsAndFutureArchiveCannotChangeSavedComparison() {
        val before = snapshot("before", 1000, listOf(observation("a", 2412, 1000)))
        val target = snapshot("after", 3000, listOf(observation("a", 2412, 3000)))
        val stray = snapshot("stray", 2000, listOf(observation("stray", 5180, 2000)))
        val unrelated = listOf(stray.copy(area = "other"), stray.copy(simulation = true),
            stray.copy(capturedAtMs = 999, wifiChannels = listOf(observation("old", 5180, 500))),
            stray.copy(capturedAtMs = 3001),
            stray.copy(wifiChannels = listOf(observation("future-packet", 5180, 9000))))
        val frozen = analyzeWifiChannels(before, target)
        assertEquals(frozen, analyzeWifiChannels(before, target, unrelated))
    }

    @Test fun noBaselineScopesHistoryToTheCurrentTargetSession() {
        val previous = snapshot("previous-session", 1000, listOf(observation("old", 5180, 1000)))
        val target = snapshot("current-session", 3000, listOf(observation("current", 2412, 3000))).copy(scanStartedAtMs = 2000)
        val result = analyzeWifiChannels(null, target, listOf(previous))
        assertEquals(2000L, result.scopeStartMs)
        assertEquals(listOf(2412), result.channels.map { it.frequencyMhz })
    }

    @Test fun aChannelRemainsObservedWhenRssiIsUnavailable() {
        val sample = observation("a", 5955, 1000).copy(lastRssi = null, peakRssi = null)
        val channel = analyzeWifiChannels(snapshot("before", 0, emptyList()), snapshot("after", 1000, listOf(sample))).channels.single()
        assertEquals(1, channel.recentAPCount)
        assertEquals(1, channel.observedAPCount)
        assertEquals(true, channel.isNewChannel)
        assertNull(channel.peakRssi)
        assertNull(channel.accessPoints.single().observation.lastRssi)
    }

    @Test fun aLaterVisibleSessionDoesNotEraseEarlierHiddenHistory() {
        val hidden = observation("a", 2412, 1000).copy(name = "", hidden = true, everHidden = true)
        val before = snapshot("before", 1000, listOf(hidden))
        val visible = observation("A", 2412, 4000, first = 3500).copy(name = "Visible now", hidden = false, everHidden = false)
        val target = snapshot("later-session", 4000, listOf(visible)).copy(scanStartedAtMs = 3500)
        val result = analyzeWifiChannels(before, target)
        val point = result.channels.single().accessPoints.single()
        assertEquals("Visible now", point.observation.name)
        assertFalse(point.observation.hidden)
        assertFalse(point.observation.everHidden)
        assertTrue(point.hasHiddenHistory)
        assertEquals(1, result.hiddenAPCount)
        assertEquals(0, result.recentHiddenAPCount)
        assertEquals(0, result.addedHiddenAPCount)
        assertEquals(1, result.channels.single().hiddenAPCount)
        assertEquals(0, result.channels.single().recentHiddenAPCount)
    }

    @Test fun hiddenAddressMovingBetweenFrequenciesIsOnlyCountedOnceInTotals() {
        val before = snapshot("before", 0, emptyList())
        val firstChannel = observation("a", 2412, 1000).copy(hidden = true, everHidden = true)
        val secondChannel = observation("A", 2437, 2000).copy(hidden = true, everHidden = true)
        val result = analyzeWifiChannels(before, snapshot("after", 2000, listOf(firstChannel, secondChannel)))
        assertEquals(2, result.channels.sumOf { it.hiddenAPCount })
        assertEquals(1, result.hiddenAPCount)
        assertEquals(1, result.addedHiddenAPCount)
        assertEquals(1, result.recentHiddenAPCount)
        assertEquals(1, result.channels.sumOf { it.recentHiddenAPCount })
    }

    @Test fun outOfScopeHiddenEvidenceCannotChangeVisibleSavedComparison() {
        val before = snapshot("before", 1000, emptyList())
        val target = snapshot("after", 2000, listOf(observation("a", 2412, 2000)))
        val hidden = observation("a", 2412, 1500).copy(hidden = true, everHidden = true)
        val archive = listOf(snapshot("future-save", 2001, listOf(hidden)), snapshot("other-room", 1600, listOf(hidden)).copy(area = "other"))
        val result = analyzeWifiChannels(before, target, archive)
        assertEquals(0, result.hiddenAPCount)
        assertEquals(0, result.addedHiddenAPCount)
        assertFalse(result.channels.single().accessPoints.single().hasHiddenHistory)
        assertNull(analyzeWifiChannels(null, target).addedHiddenAPCount)
    }

    @Test fun scopeFirstAndPeakSurviveSameFrequencySessionReplacementWithoutCombiningSessionCountsOrPeriods() {
        val old = observation("aa", 2412, 1500, count = 7, first = 500, peak = -30)
        val latest = observation("AA", 2412, 2500, count = 2, first = 2000, peak = -80)
        val analysis = analyzeWifiChannels(snapshot("before", 0, emptyList()),
            snapshot("target", 3000, listOf(latest)), listOf(snapshot("old-session", 1600, listOf(old))))
        val point = analysis.channels.single().accessPoints.single()
        assertEquals(500L, point.scopeFirstSeenMs)
        assertEquals(-30, point.scopePeakRssi)
        assertEquals(latest, point.observation)
        assertEquals(2, point.observation.observationCount)
        assertEquals(listOf(ObservationPeriod(2000, 2500, 2)), point.observation.periods)
        assertEquals(2000L, point.observation.firstSeenMs)
        assertEquals(-80, point.observation.peakRssi)
    }

    @Test fun scopeFirstAndPeakIgnoreEarlierFutureOtherAreaAndOtherModeSaves() {
        val before = snapshot("before", 1000, emptyList())
        val valid = snapshot("valid", 2000, listOf(observation("a", 2412, 1900, first = 1500, peak = -50)))
        val excluded = observation("a", 2412, 900, first = 100, peak = -5)
        val target = snapshot("target", 4000, listOf(observation("A", 2412, 3900, first = 3500, peak = -80)))
        val archive = listOf(valid,
            snapshot("earlier", 999, listOf(excluded)),
            snapshot("future", 4001, listOf(excluded)),
            snapshot("other-area", 2500, listOf(excluded)).copy(area = "Other room"),
            snapshot("other-mode", 2500, listOf(excluded)).copy(simulation = true))
        val point = analyzeWifiChannels(before, target, archive).channels.single().accessPoints.single()
        assertEquals(1500L, point.scopeFirstSeenMs)
        assertEquals(-50, point.scopePeakRssi)
        assertEquals(3500L, point.observation.firstSeenMs)
    }

    private fun observation(address: String, frequency: Int, at: Long, count: Int = 1, first: Long = at, peak: Int = -50) =
        WifiChannelObservation(address, "AP $address", frequency, first, at, count, -60, peak, listOf(ObservationPeriod(first, at, count)))

    private fun snapshot(id: String, at: Long, channels: List<WifiChannelObservation>?) =
        SignalSnapshot(id, "A room", id, at, 0, false, emptyList(), wifiChannels = channels)

    private fun WifiChannelAnalysis.channel(frequency: Int) = channels.single { it.frequencyMhz == frequency }
}
