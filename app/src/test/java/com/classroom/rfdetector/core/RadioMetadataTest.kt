package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class RadioMetadataTest {
    @Test fun beaconFormatsAreSessionEvidenceEvenWhenLatestOrLatePacketHasNoMetadata() {
        val tracker = DeviceTracker()
        fun observe(time: Long, metadata: RadioMetadata?) = tracker.update(
            RfObservation("AA", ScanSource.BLE, null, -70, null, false, time, radioMetadata = metadata))
        observe(3000, RadioMetadata(ble = BleRadioMetadata(legacy = false, beaconFormats = listOf(BeaconFormat.EDDYSTONE_UID))))
        observe(4000, null)
        val afterMissing = tracker.snapshot(4000).single().radioMetadata!!.ble!!
        assertEquals(listOf(BeaconFormat.EDDYSTONE_UID), afterMissing.beaconFormats)
        assertNull(afterMissing.legacy) // Do not borrow old packet metadata alongside the session formats.
        observe(2000, RadioMetadata(ble = BleRadioMetadata(legacy = true, beaconFormats = listOf(BeaconFormat.EDDYSTONE_TLM))))
        observe(5000, RadioMetadata(ble = BleRadioMetadata(connectable = false, beaconFormats = listOf(BeaconFormat.EDDYSTONE_UID))))
        val device = tracker.snapshot(5000).single()
        val metadata = device.radioMetadata!!.ble!!
        assertEquals(listOf(BeaconFormat.EDDYSTONE_UID, BeaconFormat.EDDYSTONE_TLM), metadata.beaconFormats)
        assertEquals(false, metadata.connectable)
        assertNull(metadata.legacy)
        assertEquals("BLE 비콘 광고", device.equipmentType())
        assertNull(device.referenceRssiAt1m)
        tracker.update(RfObservation("BB", ScanSource.BLE, null, -65, null, false, 5000))
        assertNull(tracker.snapshot(5000).first { it.key == "BB" }.radioMetadata)
        tracker.clear()
        observe(6000, null)
        assertNull(tracker.snapshot(6000).single().radioMetadata)
    }

    @Test fun beaconFormatsAreDetachedAtInputAndSnapshotBoundaries() {
        val formats = mutableListOf(BeaconFormat.IBEACON_COMPATIBLE)
        val tracker = DeviceTracker()
        tracker.update(RfObservation("AA", ScanSource.BLE, null, -60, null, false, 1000,
            radioMetadata = RadioMetadata(ble = BleRadioMetadata(beaconFormats = formats))))
        formats.clear()
        val saved = SignalSnapshot.capture("saved", "Room", "after", 1000, 0, false, tracker.snapshot(1000))
        tracker.update(RfObservation("AA", ScanSource.BLE, null, -60, null, false, 2000,
            radioMetadata = RadioMetadata(ble = BleRadioMetadata(beaconFormats = listOf(BeaconFormat.ALTBEACON)))))
        assertEquals(listOf(BeaconFormat.IBEACON_COMPATIBLE), saved.signals.single().radioMetadata!!.ble!!.beaconFormats)
        assertEquals("BLE 비콘 광고", saved.signals.single().equipmentType())
        assertEquals(2, tracker.snapshot(2000).single().radioMetadata!!.ble!!.beaconFormats.size)
    }

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
