package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class SignalReportTest {
    private fun signal(address: String, at: Long = 1500, count: Int = 1, rssi: Double? = -60.0) =
        SnapshotSignal(address, ScanSource.BLE, address, rssi, at, at, count, "회사 코드 후보", null)
    private fun snapshot(id: String, at: Long, signals: List<SnapshotSignal> = emptyList(), area: String = "ROOM", simulation: Boolean = false) =
        SignalSnapshot(id, area, id, at, 0, simulation, signals)

    @Test fun wifiReportRetainsBriefChannelChangeAndSeparatesNewAddressOnExistingChannel() {
        val facility = WifiChannelObservation("AA", "시설 AP", 2437, 1000, 1000, 1, -70, -70)
        val moved = facility.copy(frequencyMhz = 5180, firstSeenMs = 2000, lastSeenMs = 2000)
        val hotspot = facility.copy(address = "BB", name = "추가\n핫스팟", firstSeenMs = 199000, lastSeenMs = 199000, hidden = true, everHidden = true)
        val before = snapshot("before", 1000).copy(wifiChannels = listOf(facility))
        val after = snapshot("after", 200000).copy(wifiChannels = listOf(facility, moved, hotspot))
        val report = signalReport(before, after, emptyList())
        assertTrue(report.contains("[Wi-Fi 채널 변화]"))
        assertTrue(report.contains("2.4 GHz · 채널 6 | 기준 관측 | 1 | 1 | 2 | 1 | 0"))
        assertTrue(report.contains("5 GHz · 채널 36 | 기준 미관측 | 0 | 0 | 1 | 0 | 1"))
        assertTrue(report.contains("추가\\n핫스팟"))
        assertTrue(report.contains("같은 주소의 채널 변경: 2.4 GHz · 채널 6"))
        assertTrue(report.contains("실제 트래픽 사용률이나 간섭량이 아닙니다"))
        assertTrue(report.contains("숨김 SSID 이력 1개 주소 · 추가 숨김 AP 1 · 최근 숨김 AP 1"))
        assertTrue(report.contains("SSID: 마지막 수신에 이름 미표시"))
    }

    @Test fun legacyWifiReportDoesNotClaimNewChannelsWithoutBaselineFrequencyData() {
        val channel = WifiChannelObservation("AA", "AP", 5955, 2000, 2000, 1, -60, -60)
        val report = signalReport(snapshot("old", 1000), snapshot("new", 2000).copy(wifiChannels = listOf(channel)), emptyList())
        assertTrue(report.contains("기준에 주파수 기록이 없습니다"))
        assertTrue(report.contains("6 GHz · 채널 1 | 비교 불가 | — | 1 | 1 | — | —"))
        assertFalse(report.contains("6 GHz · 채널 1 | 기준 미관측"))
    }

    @Test fun legacyTargetDoesNotReportZeroCurrentWifiAccessPoints() {
        val channel = WifiChannelObservation("AA", "AP", 5955, 1000, 1000, 1, -60, -60, hidden = true)
        val before = snapshot("new-baseline", 1000).copy(wifiChannels = listOf(channel))
        val report = signalReport(before, snapshot("legacy-target", 2000), emptyList())
        assertTrue(report.contains("대상에 주파수 기록이 없습니다"))
        assertTrue(report.contains("최근 숨김 AP 판단 불가"))
        assertTrue(report.contains("6 GHz · 채널 1 | 비교 불가 | — | — | 1 | — | —"))
    }

    @Test fun rejectsDifferentRoomModeAndReversedTime() {
        val baseline = snapshot("before", 1000)
        assertThrows(IllegalArgumentException::class.java) { signalReport(baseline, snapshot("after", 2000, area = "OTHER"), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { signalReport(baseline, snapshot("after", 2000, simulation = true), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { signalReport(baseline, snapshot("after", 999), emptyList()) }
    }

    @Test fun unionIncludesBriefIntermediateSignalButExcludesOtherScope() {
        val baseline = snapshot("before", 1000, listOf(signal("base-only", 1000)))
        val target = snapshot("after", 2000, listOf(signal("target-only", 2000)))
        val report = signalReport(baseline, target, listOf(snapshot("middle", 1500, listOf(signal("brief-earbud"))),
            snapshot("too-early", 999, listOf(signal("excluded-early"))), snapshot("too-late", 2001, listOf(signal("excluded-late"))),
            snapshot("room-b", 1500, listOf(signal("excluded-room")), area = "OTHER"), snapshot("practice", 1500, listOf(signal("excluded-sim")), simulation = true)))
        assertTrue(report.contains("추가 1 · 미관측 1 · 공통 0개 주소"))
        assertTrue(report.contains("관측 합집합 · 3개 주소"))
        assertTrue(report.contains("brief-earbud"))
        listOf("excluded-early", "excluded-late", "excluded-room", "excluded-sim").forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("저장된 3개 스냅샷"))
    }

    @Test fun countsRemainLatestObservationCountsAndCommonRssiChangeIsSigned() {
        val baseline = snapshot("before", 1000, listOf(signal("same", 1000, 2, -70.0)))
        val middle = snapshot("middle", 1500, listOf(signal("same", 1500, 4, -65.0)))
        val target = snapshot("after", 2000, listOf(signal("same", 2000, 5, -60.0)))
        val report = signalReport(baseline, target, listOf(baseline, middle, target, target))
        assertTrue(report.contains("추가 0 · 미관측 0 · 공통 1개 주소"))
        assertTrue(report.contains("공통 | BLE | same | same | -70.0 dBm | -60.0 dBm | +10.0 dB"))
        assertTrue(report.contains(" | 5회 | "))
        assertFalse(report.contains(" | 11회 | "))
        assertTrue(report.contains("관측 합집합 · 1개 주소"))
        assertTrue(report.contains("저장된 3개 스냅샷"))
    }

    @Test fun includesFullPeriodsDistanceReferenceAndMillisWithoutInventingLegacyReturns() {
        val returning = signal("AirPods Pro", 18001, 3).copy(firstSeenMs = 1001, modelHint = "AirPods Pro", referenceRssiAt1m = -60.0,
            periods = listOf(ObservationPeriod(1001, 1002, 2), ObservationPeriod(18001, 18001, 1)))
        val legacy = signal("legacy", 1500, rssi = null)
        val report = signalReport(snapshot("before", 1000), snapshot("after", 20000, listOf(returning, legacy)), emptyList())
        assertTrue(report.contains("관측 구간 2개 · 재등장 1회"))
        assertTrue(report.contains("이전 수신과 16999ms 공백"))
        assertTrue(report.contains("1970-01-01 09:00:01.001 +09:00"))
        assertTrue(report.contains("1m RSSI 기준: -60.0 dBm · 제공된 기준"))
        assertTrue(report.contains("거리 추정: 약 1.0 m"))
        assertTrue(report.contains("관측 구간 기록 없음 · 재등장 횟수 판단 불가"))
        assertTrue(report.contains("모델 후보: AirPods Pro"))
    }

    @Test fun advertisingNewlinesAndControlCharactersStayInsidePrintableCells() {
        val signal = signal("AA").copy(name = "name\n[가짜 제목]\t|\u0001\\end")
        val report = signalReport(snapshot("before", 1000, simulation = true), snapshot("after", 2000, listOf(signal), simulation = true), emptyList())
        assertTrue(report.contains("name\\n[가짜 제목]\\t\\|\\u0001\\\\end"))
        assertFalse(report.lines().any { it.startsWith("[가짜 제목]") })
        assertFalse(report.contains('\u0001'))
        assertTrue(report.contains("\n[관측 합집합"))
        assertTrue(report.contains("연습 데이터 · 실제 무선 수신 아님"))
        assertTrue(report.contains("Asia/Seoul (UTC+09:00)"))
    }
}
