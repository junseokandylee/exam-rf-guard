package com.classroom.rfdetector.core

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun beaconFormatsRoundTripAndOldOrFutureFormatNamesDoNotBreakVersionThree() {
        val signal = SnapshotSignal("beacon", ScanSource.BLE, "BLE 기기", -70.0, 1000, 1000, 1, "Apple · 광고", null,
            radioMetadata = RadioMetadata(ble = BleRadioMetadata(beaconFormats = listOf(BeaconFormat.IBEACON_COMPATIBLE, BeaconFormat.EDDYSTONE_TLM))))
        val baseline = SignalSnapshot("before", "Room", "before", 0, 0, false, emptyList())
        val saved = baseline.copy(id = "after", label = "after", capturedAtMs = 2000, signals = listOf(signal))
        val store = SnapshotStore(directory.root)
        store.save(listOf(saved))
        assertEquals(saved, store.load().single())
        val report = signalReport(baseline, store.load().single(), emptyList())
        assertTrue(report.contains("BLE 비콘 광고"))
        assertTrue(report.contains("iBeacon 호환 형식 · Eddystone TLM"))
        assertTrue(report.contains(BEACON_FORMAT_NOTE))

        val file = File(directory.root, "signal-snapshots.json")
        val json = JSONObject(file.readText())
        assertEquals(3, json.getInt("version"))
        val ble = json.getJSONArray("snapshots").getJSONObject(0).getJSONArray("signals").getJSONObject(0)
            .getJSONObject("radioMetadata").getJSONObject("ble")
        ble.getJSONArray("beaconFormats").put("FUTURE_BEACON_FORMAT").put(BeaconFormat.IBEACON_COMPATIBLE.name)
        file.writeText(json.toString())
        assertEquals(signal.radioMetadata, store.load().single().signals.single().radioMetadata)
        ble.remove("beaconFormats")
        file.writeText(json.toString())
        val legacy = store.load().single().signals.single()
        assertTrue(legacy.radioMetadata!!.ble!!.beaconFormats.isEmpty())
        assertEquals("Bluetooth 장비 · 유형 미확인", legacy.equipmentType())
    }

    @Test fun savedSnapshotsSurviveNewStoreInstanceWithNullFieldsAndUnicode() {
        val snapshot = SignalSnapshot("id", "3층 제1시험실", "입실 전", 3000, 1000, true,
            listOf(SnapshotSignal("AA:BB:CC:DD:EE:FF", ScanSource.BLE, "이어폰", null, 1000, 2000, 1, "Apple · 광고", null)))
        SnapshotStore(directory.root).save(listOf(snapshot))
        assertEquals(listOf(snapshot), SnapshotStore(directory.root).load())
        val next = snapshot.copy(id = "after", label = "시험 중", capturedAtMs = 4000)
        SnapshotStore(directory.root).save(listOf(next, snapshot))
        assertEquals(listOf(next, snapshot), SnapshotStore(directory.root).load())
    }

    @Test fun modemEnvironmentAndRequestedScopePersistOutsideDeviceCounts() {
        val before = SignalSnapshot("before", "시험실", "입실 전", 1000, 1, false, emptyList(),
            discoverySources = setOf(ScanSource.BLE, ScanSource.WIFI))
        val environment = CellEnvironmentSnapshot(1800, listOf(
            CellEnvironmentReading("LTE", "450:08:123:456", 1650, -85, true, 1700, true)), CellEnvironmentStatus.CACHED)
        val after = before.copy(id = "after", capturedAtMs = 2000, cellEnvironment = environment,
            discoverySources = setOf(ScanSource.BLE, ScanSource.WIFI, ScanSource.CLASSIC, ScanSource.P2P))
        val store = SnapshotStore(directory.root)
        store.save(listOf(before, after))
        val restored = store.load()
        assertEquals(listOf(before, after), restored)
        assertTrue(compareSnapshots(restored[0], restored[1]).added.isEmpty())
        assertTrue(observedUnion(restored[0], restored[1], restored).isEmpty())
        val report = signalReport(restored[0], restored[1], restored)
        assertTrue(report.contains("LTE · 450:08:123:456 · 채널 1650 · -85 dBm"))
        assertTrue(report.contains("요청 전 캐시"))
        assertTrue(report.contains("기준 때 탐색하지 않았습니다"))
    }

    @Test fun missingFileIsAnEmptyArchive() {
        assertTrue(SnapshotStore(directory.root).load().isEmpty())
    }

    @Test fun observationPeriodsAndDistanceReferenceSurviveReopen() {
        val signal = SnapshotSignal("earbud", ScanSource.BLE, "AirPods Pro", -65.0, 1000, 30000, 3, "Apple", null,
            periods = listOf(ObservationPeriod(1000, 1100, 2), ObservationPeriod(30000, 30000, 1)), referenceRssiAt1m = -60.0)
        val snapshot = SignalSnapshot("id", "시험실", "시험 중", 40000, 0, false, listOf(signal))
        SnapshotStore(directory.root).save(listOf(snapshot))
        assertEquals(snapshot, SnapshotStore(directory.root).load().single())
        assertEquals(1, SnapshotStore(directory.root).load().single().signals.single().reappearanceCount)
    }

    @Test fun oldVersionRemainsReadableWithoutInventingObservationPeriods() {
        File(directory.root, "signal-snapshots.json").writeText("""{"version":1,"snapshots":[{"id":"old","area":"시험실","label":"입실 전","capturedAtMs":2000,"scanStartedAtMs":0,"simulation":false,"signals":[{"address":"old","source":"BLE","name":"old","rssi":-65,"firstSeenMs":1000,"lastSeenMs":2000,"observations":3,"manufacturer":"unknown","model":null}]}]}""")
        val snapshot = SnapshotStore(directory.root).load().single()
        val signal = snapshot.signals.single()
        assertTrue(signal.periods.isEmpty())
        assertNull(signal.referenceRssiAt1m)
        assertNull(snapshot.wifiChannels)
    }

    @Test fun channelHistoryRoundTripsAsVersionThreeIncludingPeriodsAndMissingRssi() {
        val channels = listOf(
            WifiChannelObservation("AA", "숨김 AP", 2437, 1000, 200000, 3, -70, -60,
                listOf(ObservationPeriod(1000, 2000, 2), ObservationPeriod(200000, 200000, 1))),
            WifiChannelObservation("AA", "AP 이름", 5180, 3000, 3000, 1, null, null, listOf(ObservationPeriod(3000, 3000, 1))),
        )
        val snapshot = SignalSnapshot("channels", "시험실", "시험 중", 300000, 0, false, emptyList(), channels)
        SnapshotStore(directory.root).save(listOf(snapshot))
        assertEquals(3, JSONObject(File(directory.root, "signal-snapshots.json").readText()).getInt("version"))
        assertEquals(snapshot, SnapshotStore(directory.root).load().single())
    }

    @Test fun versionTwoChannelAbsenceStaysUnknownAfterSavingAsVersionThree() {
        File(directory.root, "signal-snapshots.json").writeText("""{"version":2,"snapshots":[{"id":"old","area":"시험실","label":"입실 전","capturedAtMs":2000,"scanStartedAtMs":0,"simulation":false,"signals":[]}]}""")
        val legacy = SnapshotStore(directory.root).load().single()
        assertNull(legacy.wifiChannels)
        val currentEmpty = legacy.copy(id = "new-empty", wifiChannels = emptyList())
        SnapshotStore(directory.root).save(listOf(legacy, currentEmpty))
        val restored = SnapshotStore(directory.root).load()
        assertNull(restored.first().wifiChannels)
        assertEquals(emptyList<WifiChannelObservation>(), restored.last().wifiChannels)
    }

    @Test fun currentAndHistoricalHiddenFlagsRoundTripAndMissingKeysDefaultToFalse() {
        val hidden = SnapshotSignal("AA", ScanSource.WIFI, "이름 없는 AP", -70.0, 1000, 1000, 1,
            "미확인", null, hidden = true)
        val named = hidden.copy(address = "BB", name = "Visible SSID", hidden = false, everHidden = true)
        val channel = WifiChannelObservation("AA", "이름 없는 AP", 2437, 1000, 1000, 1, -70, -70,
            listOf(ObservationPeriod(1000, 1000, 1)), hidden = true)
        val namedChannel = channel.copy(address = "BB", name = "Visible SSID", hidden = false, everHidden = true)
        val snapshot = SignalSnapshot("hidden", "시험실", "시험 중", 2000, 0, false, listOf(hidden, named), listOf(channel, namedChannel))
        val store = SnapshotStore(directory.root)
        store.save(listOf(snapshot))
        assertEquals(snapshot, SnapshotStore(directory.root).load().single())

        val file = File(directory.root, "signal-snapshots.json")
        val json = JSONObject(file.readText())
        val row = json.getJSONArray("snapshots").getJSONObject(0)
        listOf("signals", "wifiChannels").forEach { field ->
            val records = row.getJSONArray(field)
            for (index in 0 until records.length()) {
                records.getJSONObject(index).remove("hidden")
                records.getJSONObject(index).remove("everHidden")
            }
        }
        file.writeText(json.toString())
        val legacyV3 = SnapshotStore(directory.root).load().single()
        assertTrue(legacyV3.signals.none { it.hidden || it.everHidden })
        assertTrue(legacyV3.wifiChannels!!.none { it.hidden || it.everHidden })
    }

    @Test(expected = IllegalArgumentException::class) fun unsupportedVersionIsNotSilentlyDiscarded() {
        File(directory.root, "signal-snapshots.json").writeText("{\"version\":99,\"snapshots\":[]}")
        SnapshotStore(directory.root).load()
    }

    @Test fun radioMetadataRoundTripsWithoutLosingFalseFlagsZeroPhyOrNonContiguousWidth() {
        val wifi = WifiRadioMetadata(4, 5210, 5530, "[RSN-OWE-CCMP][ESS]")
        val records = listOf(
            SnapshotSignal("wifi", ScanSource.WIFI, "AP", -60.0, 1000, 1000, 1, "unknown", null, radioMetadata = RadioMetadata(wifi = wifi)),
            SnapshotSignal("ble", ScanSource.BLE, "BLE", -70.0, 1000, 1000, 1, "unknown", null,
                radioMetadata = RadioMetadata(ble = BleRadioMetadata(listOf("0000180f-0000-1000-8000-00805f9b34fb"), false, true, 1, 0))),
            SnapshotSignal("classic", ScanSource.CLASSIC, "Classic", null, 1000, 1000, 1, "unknown", null,
                radioMetadata = RadioMetadata(classicDeviceClass = 0x404)),
            SnapshotSignal("unknown", ScanSource.BLE, "Unknown", null, 1000, 1000, 1, "unknown", null,
                radioMetadata = RadioMetadata(ble = BleRadioMetadata())),
        )
        val channel = WifiChannelObservation("wifi", "AP", 5180, 1000, 1000, 1, -60, -60, wifiMetadata = wifi)
        val saved = SignalSnapshot("meta", "Room", "before", 2000, 0, false, records, listOf(channel))
        SnapshotStore(directory.root).save(listOf(saved))
        assertEquals(saved, SnapshotStore(directory.root).load().single())
    }

    @Test fun missingOptionalVersionThreeMetadataRemainsUnknown() {
        val signal = SnapshotSignal("wifi", ScanSource.WIFI, "AP", -60.0, 1000, 1000, 1, "unknown", null)
        val channel = WifiChannelObservation("wifi", "AP", 5180, 1000, 1000, 1, -60, -60)
        SnapshotStore(directory.root).save(listOf(SignalSnapshot("old-v3", "Room", "before", 2000, 0, false, listOf(signal), listOf(channel))))
        val file = File(directory.root, "signal-snapshots.json")
        val json = JSONObject(file.readText())
        val snapshot = json.getJSONArray("snapshots").getJSONObject(0)
        snapshot.getJSONArray("signals").getJSONObject(0).remove("radioMetadata")
        snapshot.getJSONArray("wifiChannels").getJSONObject(0).remove("wifiMetadata")
        file.writeText(json.toString())
        val restored = SnapshotStore(directory.root).load().single()
        assertNull(restored.signals.single().radioMetadata)
        assertNull(restored.wifiChannels!!.single().wifiMetadata)
    }
}
