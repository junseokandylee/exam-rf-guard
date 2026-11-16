package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class ExtendedRadioTest {
    @Test fun widenedDiscoveryScopeWarnsBeforeInterpretingAdditionalAddresses() {
        val base = SignalSnapshot("base", "room", "before", 1000, 0, false, emptyList(),
            discoverySources = setOf(ScanSource.BLE, ScanSource.WIFI))
        assertNull(discoveryScopeNote(base, base.copy(id = "same", capturedAtMs = 2000)))
        val wider = base.copy(discoverySources = base.discoverySources!! + ScanSource.CLASSIC)
        assertTrue(discoveryScopeNote(base, wider)!!.contains("Bluetooth Classic"))
        assertTrue(discoveryScopeNote(base, wider)!!.contains("입실 후 생겼다는 뜻은 아닙니다"))
        assertTrue(discoveryScopeNote(base.copy(discoverySources = null), base)!!.contains("기록이 없는 자료"))
        assertNull(discoveryScopeNote(wider, base))
    }

    private fun signal(source: ScanSource) = SnapshotSignal("AA:BB:CC:DD:EE:FF", source, "AirPods Pro", null,
        1000, 1000, 1, "미확인", null)

    @Test fun classicNameCanSuggestAnEarphoneButDirectNameCannot() {
        assertEquals("이어폰 후보", signal(ScanSource.CLASSIC).equipmentType())
        assertEquals("Wi-Fi Direct 주변 기기", signal(ScanSource.P2P).equipmentType())
        assertNull(signal(ScanSource.P2P).audioNameMatch)
        assertEquals("AirPods Pro", signal(ScanSource.CLASSIC).audioNameMatch!!.model)
    }

    @Test fun transportFiltersAndIdentityNeverMergeBleClassicDirectAddresses() {
        val signals = listOf(signal(ScanSource.BLE), signal(ScanSource.CLASSIC), signal(ScanSource.P2P))
        assertEquals(3, signals.map { it.matchKey }.distinct().size)
        assertEquals(listOf(ScanSource.CLASSIC), filterAndSortSignals(signals, "", RadioFilter.CLASSIC, false, SignalSort.NAME).map { it.source })
        assertEquals(listOf(ScanSource.P2P), filterAndSortSignals(signals, "", RadioFilter.P2P, false, SignalSort.NAME).map { it.source })
        assertTrue(filterAndSortSignals(signals, "", RadioFilter.P2P, true, SignalSort.NAME).isEmpty())
    }

    @Test fun classicRoundGapsAreNotMistakenForBleDisappearances() {
        assertEquals(SignalPresence.RECENT, signalPresence(ScanSource.CLASSIC, 0, 90_000))
        assertEquals(SignalPresence.QUIET, signalPresence(ScanSource.CLASSIC, 0, 180_000))
        assertEquals(SignalPresence.HISTORY, signalPresence(ScanSource.CLASSIC, 0, 180_001))
        val periods = addToPeriods(listOf(ObservationPeriod(1000, 1000, 1)), 62_000, ScanSource.CLASSIC.historyGapMs())
        assertEquals(1, periods.size)
        assertEquals(2, periods.single().count)
    }

    @Test fun directReportDoesNotCallCachedListEntriesPacketReceptionOrReappearance() {
        val baseline = SignalSnapshot("before", "ROOM", "before", 500, 0, false, emptyList())
        val direct = signal(ScanSource.P2P).copy(rssi = -55.0)
        val target = baseline.copy(id = "after", capturedAtMs = 2000, signals = listOf(direct))
        val report = signalReport(baseline, target, emptyList())
        assertNull(direct.receivedRssi)
        assertNull(SignalChange(direct, direct.copy(rssi = -60.0)).deltaDb)
        assertFalse(report.contains("-55.0 dBm"))
        assertFalse(report.contains("  거리 추정:"))
        assertTrue(report.contains("판단 안 함 · OS 목록"))
        assertTrue(report.contains("목록 첫 확인 · RF 수신 횟수 아님"))
        assertTrue(report.contains("실제 수신 구간·재등장 산출 안 함"))
        assertTrue(report.contains("OS 캐시일 수 있으며"))
    }

    @Test fun metadataKeepsDisjointWifiWidthsAndAdvertisedBleEvidenceDistinct() {
        val metadata = RadioMetadata(wifi = WifiRadioMetadata(4, 5210, 5530, "[WPA2-PSK-CCMP][ESS]"),
            ble = BleRadioMetadata(listOf("0000180f-0000-1000-8000-00805f9b34fb"), true, false, 1, 3), classicDeviceClass = 0x0418)
        val fields = metadata.detailFields().toMap()
        assertEquals("80+80 MHz", fields["채널 폭"])
        assertEquals("확장 BLE 광고", fields["광고 형식"])
        assertEquals("LE Coded", fields["보조 PHY"])
        assertEquals("예 · 실제 연결하지 않음", fields["연결 가능 광고"])
        assertTrue(fields.getValue("Bluetooth 기기 클래스").contains("000418"))
    }
}
