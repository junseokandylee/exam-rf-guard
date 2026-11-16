package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class SignalPresentationTest {
    @Test fun beaconTypeRequiresActualBlePayloadEvidenceAndExplainsCompanyCodeLimits() {
        val metadata = RadioMetadata(ble = BleRadioMetadata(beaconFormats = listOf(BeaconFormat.IBEACON_COMPATIBLE)))
        val signal = SnapshotSignal("address", ScanSource.BLE, "BLE 기기", -60.0, 0, 1000, 1,
            "Apple · 광고", null, manufacturerIds = setOf(0x004c), radioMetadata = metadata)
        assertEquals("BLE 비콘 광고", signal.equipmentType())
        assertEquals("Bluetooth 장비 · 유형 미확인", signal.copy(radioMetadata = null).equipmentType())
        assertEquals("Bluetooth 장비 · 유형 미확인", signal.copy(source = ScanSource.CLASSIC).equipmentType())
        assertEquals("Wi-Fi 접속점 · 공유기/핫스폿", signal.copy(source = ScanSource.WIFI).equipmentType())
        assertEquals("iBeacon 호환 형식", metadata.detailFields().first { it.first == "관측된 비콘 광고 형식" }.second)
        assertTrue(metadata.detailFields().any { it.second == BEACON_FORMAT_NOTE })
    }

    @Test fun singleObservationDoesNotClaimDuration() {
        assertEquals("한 번 포착 · 기간 판단 불가", observationSpan(1000, 9000, 1))
    }

    @Test fun spanReportsObservedWindowRatherThanContinuousConnection() {
        assertEquals("2분 5초 구간에서 7 회 수신", observationSpan(1000, 126000, 7))
        assertEquals("1초 미만 구간에서 2 회 수신", observationSpan(2000, 1000, 2))
        assertEquals("1시간 1분 구간에서 12 회 수신", observationSpan(0, 3660000, 12))
    }

    @Test fun wifiNameIsNotClassifiedAsEarphonesAndUnknownBleStaysUnknown() {
        val signal = SnapshotSignal("address", ScanSource.WIFI, "AirPods Pro", -50.0, 0, 1000, 2, "미확인", null)
        assertEquals("Wi-Fi 접속점 · 공유기/핫스폿", signal.equipmentType())
        assertEquals("Bluetooth 장비 · 유형 미확인", signal.copy(source = ScanSource.BLE, name = "unknown").equipmentType())
        assertEquals("이어폰 후보", signal.copy(source = ScanSource.BLE).equipmentType())
    }
}
