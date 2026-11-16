package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class RadioMetadataTest {
    private fun wifi(time: Long, frequency: Int, metadata: WifiRadioMetadata?) = RfObservation(
        "AA", ScanSource.WIFI, "AP", -60, null, false, time, frequencyMhz = frequency,
        radioMetadata = metadata?.let { RadioMetadata(wifi = it) },
    )

    @Test fun lateMetadataCannotOverwriteLatestApOrChannelButCanUpdateAnotherChannel() {
        val tracker = DeviceTracker()
        val oldSix = WifiRadioMetadata(0, capabilities = "[ESS]")
        val latestSix = WifiRadioMetadata(1, 2427, capabilities = "[WPA2-PSK-CCMP][ESS]")
        val latestAp = WifiRadioMetadata(2, 5210, capabilities = "[RSN-SAE-CCMP][ESS]")
        tracker.update(wifi(3000, 2437, oldSix))
        tracker.update(wifi(5000, 5180, latestAp))
        tracker.update(wifi(4000, 2437, latestSix))
        tracker.update(wifi(2000, 2437, oldSix))
        val device = tracker.snapshot(5000).single()
        assertEquals(latestAp, device.radioMetadata!!.wifi)
        assertEquals(latestSix, device.wifiChannels.first { it.frequencyMhz == 2437 }.wifiMetadata)
        assertEquals(latestAp, device.wifiChannels.first { it.frequencyMhz == 5180 }.wifiMetadata)
        assertEquals(4, device.observationCount)
    }

    @Test fun missingMetadataInNewPacketIsUnknownRatherThanBorrowedFromOlderPacket() {
        val tracker = DeviceTracker()
        tracker.update(wifi(1000, 5180, WifiRadioMetadata(2, 5210, capabilities = "[ESS]")))
        tracker.update(wifi(2000, 5180, null))
        val device = tracker.snapshot(2000).single()
        assertNull(device.radioMetadata)
        assertNull(device.wifiChannels.single().wifiMetadata)
    }

    @Test fun capturedBleMetadataIsDetachedFromMutableInputAndLatePackets() {
        val ids = mutableListOf("0000180f-0000-1000-8000-00805f9b34fb")
        val metadata = RadioMetadata(ble = BleRadioMetadata(ids, true, false, 3, 2))
        val tracker = DeviceTracker()
        val observation = RfObservation("AA", ScanSource.BLE, "device", -60, null, false, 3000, radioMetadata = metadata)
        tracker.update(observation)
        ids.clear()
        val saved = SignalSnapshot.capture("saved", "Room", "after", 3000, 0, false, tracker.snapshot(3000))
        tracker.update(observation.copy(timestampMs = 2000, radioMetadata = RadioMetadata(ble = BleRadioMetadata(legacy = true))))
        assertEquals(1, saved.signals.single().radioMetadata!!.ble!!.serviceUuids.size)
        assertEquals(saved.signals.single().radioMetadata, tracker.snapshot(3000).single().radioMetadata)
        tracker.update(observation.copy(timestampMs = 4000, radioMetadata = null))
        assertNull(tracker.snapshot(4000).single().radioMetadata)
        assertEquals(false, saved.signals.single().radioMetadata!!.ble!!.legacy)
    }

    @Test fun oldChannelMetadataSurvivesSignalFilteringAndSnapshotCapture() {
        val tracker = DeviceTracker()
        val metadata = WifiRadioMetadata(4, 5210, 5530, "[RSN-OWE-CCMP][ESS]")
        tracker.update(wifi(1000, 5180, metadata))
        val saved = SignalSnapshot.capture("saved", "Room", "after", 100000, 0, false, tracker.snapshot(100000, true))
        assertTrue(saved.signals.isEmpty())
        assertEquals(metadata, saved.wifiChannels!!.single().wifiMetadata)
    }

    @Test fun widthLabelsPreserveNonContiguousAndUnknownWidths() {
        assertEquals(listOf(20, 40, 80, 160, 160, 320), (0..5).map { WifiRadioMetadata(it).channelWidthMhz })
        assertEquals("80+80 MHz", WifiRadioMetadata(4).channelWidthLabel)
        assertEquals("160 MHz", WifiRadioMetadata(3).channelWidthLabel)
        assertNull(WifiRadioMetadata(99).channelWidthMhz)
        assertNull(WifiRadioMetadata().channelWidthLabel)
    }
}
