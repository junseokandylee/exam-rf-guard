package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class WifiChannelTrackingTest {
    private fun observation(frequency: Int?, time: Long, rssi: Int? = -65, name: String = "Room AP", address: String = "AA:BB:CC:DD:EE:FF") =
        RfObservation(address, ScanSource.WIFI, name, rssi, null, false, time, frequencyMhz = frequency)

    @Test fun onePacketCreatesOneChannelRecordWithoutInventingDuration() {
        val tracker = DeviceTracker()
        tracker.update(observation(2437, 1000, null))
        val channel = tracker.snapshot(1000).single().wifiChannels.single()
        assertEquals(2437, channel.frequencyMhz)
        assertEquals(1000L, channel.firstSeenMs)
        assertEquals(1000L, channel.lastSeenMs)
        assertEquals(1, channel.observationCount)
        assertNull(channel.lastRssi)
        assertNull(channel.peakRssi)
        assertEquals(listOf(ObservationPeriod(1000, 1000, 1)), channel.periods)
        assertEquals(0, channel.reappearanceCount)
    }

    @Test fun movingChannelsKeepsBothHistoriesUnderTheSameAp() {
        val tracker = DeviceTracker()
        tracker.update(observation(2437, 1000, -70))
        tracker.takeBaseline()
        tracker.update(observation(5180, 31000, -50))
        tracker.update(observation(2437, 100001, -60, address = "aa:bb:cc:dd:ee:ff"))
        val device = tracker.snapshot(100001).single()
        assertFalse(device.isBaselineNew)
        assertEquals(listOf(2437, 5180), device.wifiChannels.map { it.frequencyMhz })
        val returned = device.wifiChannels.first()
        assertEquals(2, returned.observationCount)
        assertEquals(1, returned.reappearanceCount)
        assertEquals(-60, returned.lastRssi)
        assertEquals(-60, returned.peakRssi)
        assertEquals(31000L, device.wifiChannels.last().lastSeenMs)
    }

    @Test fun latePacketsUpdateOnlyTheirFrequencyHistoryWithoutOverwritingNewerValues() {
        val tracker = DeviceTracker()
        tracker.update(observation(2437, 3000, -60, "latest on six"))
        tracker.update(observation(5180, 5000, -55, "latest AP"))
        tracker.update(observation(2437, 2000, -30, "old name"))
        val device = tracker.snapshot(5000).single()
        val channel = device.wifiChannels.first()
        assertEquals("latest AP", device.displayName)
        assertEquals("latest on six", channel.name)
        assertEquals(2000L, channel.firstSeenMs)
        assertEquals(3000L, channel.lastSeenMs)
        assertEquals(-60, channel.lastRssi)
        assertEquals(-30, channel.peakRssi)
        assertEquals(2, channel.observationCount)
        // A packet late for the AP can still be the newest observation on its old frequency.
        tracker.update(observation(2437, 4000, -65, "later on six"))
        val updated = tracker.snapshot(5000).single()
        assertEquals("latest AP", updated.displayName)
        assertEquals("later on six", updated.wifiChannels.first().name)
        assertEquals(4000L, updated.wifiChannels.first().lastSeenMs)
        assertEquals(-65, updated.wifiChannels.first().lastRssi)
    }

    @Test fun latePacketCanBridgeAnApparentChannelReappearance() {
        val tracker = DeviceTracker()
        tracker.update(observation(2437, 1000, -70))
        tracker.update(observation(2437, 181000, -50))
        assertEquals(1, tracker.snapshot(181000).single().wifiChannels.single().reappearanceCount)
        tracker.update(observation(2437, 91000, -40))
        val channel = tracker.snapshot(181000).single().wifiChannels.single()
        assertEquals(0, channel.reappearanceCount)
        assertEquals(listOf(ObservationPeriod(1000, 181000, 3)), channel.periods)
        assertEquals(-50, channel.lastRssi)
        assertEquals(-40, channel.peakRssi)
    }

    @Test fun missingInvalidFrequencyAndNonWifiDoNotInventChannels() {
        val tracker = DeviceTracker()
        listOf(null, 0, -1).forEachIndexed { index, frequency -> tracker.update(observation(frequency, 1000L + index)) }
        tracker.update(observation(2437, 1000).copy(source = ScanSource.BLE))
        assertEquals(2, tracker.snapshot(2000).size)
        assertTrue(tracker.snapshot(2000).all { it.wifiChannels.isEmpty() })
        assertEquals(3, tracker.snapshot(2000).single { it.source == ScanSource.WIFI }.observationCount)
    }

    @Test fun savedChannelHistorySurvivesSignalFilteringAndLaterTrackerChanges() {
        val tracker = DeviceTracker()
        tracker.update(observation(2437, 1000))
        tracker.takeBaseline()
        tracker.update(observation(5180, 2000))
        val before = SignalSnapshot.capture("saved", "Room", "after", 100000, 0, false, tracker.snapshot(100000, includeInactive = true), includeNewHistorySinceMs = 1000)
        assertTrue(before.signals.isEmpty())
        assertEquals(listOf(2437, 5180), before.wifiChannels!!.map { it.frequencyMhz })
        tracker.update(observation(2437, 101000))
        assertEquals(1, before.wifiChannels!!.first().observationCount)
        assertEquals(1000L, before.wifiChannels!!.first().lastSeenMs)
        assertEquals(2, tracker.snapshot(101000, true).single().wifiChannels.first().observationCount)
    }

    @Test fun newCaptureWithNoChannelsIsDistinctFromLegacyWithoutChannelInformation() {
        val current = SignalSnapshot.capture("current", "Room", "before", 1000, 0, false, emptyList())
        val legacy = SignalSnapshot("legacy", "Room", "before", 1000, 0, false, emptyList())
        assertEquals(emptyList<WifiChannelObservation>(), current.wifiChannels)
        assertNull(legacy.wifiChannels)
    }

    @Test fun independentAccessPointsShareFrequencyWithoutMergingAndClearResetsHistory() {
        val tracker = DeviceTracker()
        tracker.update(observation(2437, 1000, address = "AA"))
        tracker.update(observation(2437, 1000, address = "BB"))
        val channels = tracker.snapshot(1000).flatMap { it.wifiChannels }
        assertEquals(2, channels.map { it.matchKey }.toSet().size)
        tracker.clear()
        tracker.update(observation(5180, 2000, address = "AA"))
        assertEquals(listOf(5180), tracker.snapshot(2000).single().wifiChannels.map { it.frequencyMhz })
    }

    @Test fun hiddenThenNamedAndLateHiddenKeepLatestStateAndHistoricalFlag() {
        val tracker = DeviceTracker()
        tracker.update(observation(2437, 1000).copy(name = null, hidden = true))
        val frozen = SignalSnapshot.capture("hidden", "Room", "before", 1000, 0, false, tracker.snapshot(1000))
        assertTrue(frozen.signals.single().hidden)
        assertTrue(frozen.wifiChannels!!.single().hidden)
        tracker.update(observation(2437, 3000, name = "Visible SSID"))
        tracker.update(observation(2437, 2000).copy(name = null, hidden = true))
        val device = tracker.snapshot(3000).single()
        val channel = device.wifiChannels.single()
        assertEquals("Visible SSID", device.displayName)
        assertEquals("Visible SSID", channel.name)
        assertFalse(device.hidden)
        assertTrue(device.everHidden)
        assertFalse(channel.hidden)
        assertTrue(channel.everHidden)
        val latest = SignalSnapshot.capture("named", "Room", "after", 3000, 0, false, listOf(device))
        assertFalse(latest.signals.single().hidden)
        assertTrue(latest.signals.single().everHidden)
        assertTrue(frozen.signals.single().hidden)
        assertEquals(1000L, frozen.wifiChannels!!.single().lastSeenMs)
    }

    @Test fun lateHiddenObservationAddsHistoryWithoutChangingCurrentNameOrChannelState() {
        val tracker = DeviceTracker()
        tracker.update(observation(5180, 3000, name = "Visible SSID"))
        tracker.update(observation(5180, 2000).copy(name = " ", hidden = true))
        val device = tracker.snapshot(3000).single()
        assertFalse(device.hidden)
        assertTrue(device.everHidden)
        assertFalse(device.wifiChannels.single().hidden)
        assertTrue(device.wifiChannels.single().everHidden)
        tracker.update(observation(2437, 4000, name = "Visible on six"))
        val moved = tracker.snapshot(4000).single()
        assertTrue(moved.everHidden)
        assertFalse(moved.wifiChannels.first { it.frequencyMhz == 2437 }.everHidden)
        assertTrue(moved.wifiChannels.first { it.frequencyMhz == 5180 }.everHidden)
    }
}
