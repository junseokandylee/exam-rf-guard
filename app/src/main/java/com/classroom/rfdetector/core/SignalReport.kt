package com.classroom.rfdetector.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A reproducible plain-text export of the selected RF comparison and its observation union. */
fun signalReport(baseline: SignalSnapshot, target: SignalSnapshot, archive: List<SignalSnapshot>): String {
    val comparison = compareSnapshots(baseline, target)
    val union = observedUnion(baseline, target, archive)
    val scoped = (archive + baseline + target)
        .filter { it.area.equals(target.area, true) && it.simulation == target.simulation && it.capturedAtMs in baseline.capturedAtMs..target.capturedAtMs }
        .groupBy { it.id }.values.map { copies -> copies.maxBy { it.capturedAtMs } }
        .sortedWith(compareBy<SignalSnapshot> { it.capturedAtMs }.thenBy { it.id })
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
    fun time(ms: Long) = formatter.format(Date(ms))

    return buildString {
        appendLine("RF SWEEPER · 신호 비교 보고서")
        appendLine("공간: ${reportField(target.area)}")
        appendLine("모드: ${if (target.simulation) "연습 데이터 · 실제 무선 수신 아님" else "실제 무선 탐색"}")
        appendLine("기준: ${reportField(baseline.label)} · ${time(baseline.capturedAtMs)} · ID ${reportField(baseline.id)}")
        appendLine("대상: ${reportField(target.label)} · ${time(target.capturedAtMs)} · ID ${reportField(target.id)}")
        appendLine("기준 탐색 범위: ${baseline.discoverySources?.joinToString { it.label } ?: "기록 없음"}")
        appendLine("대상 탐색 범위: ${target.discoverySources?.joinToString { it.label } ?: "기록 없음"}")
        discoveryScopeNote(baseline, target)?.let { appendLine(it) }
        appendLine("시각: Asia/Seoul (UTC+09:00), 밀리초까지 표시 · RSSI: dBm · 세기 변화: dB · 거리: m")
        appendLine()
        appendLine("[선택한 두 시점 비교]")
        appendLine("추가 ${comparison.added.size} · 미관측 ${comparison.notObserved.size} · 공통 ${comparison.common.size}개 주소")
        appendLine("추가 = 기준에 없고 대상에 있음 / 미관측 = 기준에 있고 대상에 없음 / 공통 = 양쪽에 있음")
        appendLine("이 구분은 저장된 주소 목록의 차이입니다. 대상에 남은 과거 포착 이력도 포함할 수 있습니다.")
        appendLine("그룹 | 무선 종류 | 주소 | 광고 이름 | 기준 RSSI | 대상 RSSI | 변화")
        comparison.added.forEach { appendComparisonRow("추가", null, it) }
        comparison.notObserved.forEach { appendComparisonRow("미관측", it, null) }
        comparison.common.forEach { appendComparisonRow("공통", it.before, it.after) }
        if (comparison.added.isEmpty() && comparison.notObserved.isEmpty() && comparison.common.isEmpty()) appendLine("비교할 주소 없음")
        appendLine()
        appendWifiChannelReport(analyzeWifiChannels(baseline, target, archive), ::time)
        appendCellEnvironmentReport(baseline, target, ::time)
        appendLine("[관측 합집합 · ${union.size}개 주소]")
        appendLine("동일 공간·모드의 기준~대상 범위에 저장된 ${scoped.size}개 스냅샷에서 한 번 이상 관측한 주소입니다.")
        appendLine("무선 종류 | 주소 | 유형 후보 | 광고 이름 | 회사 식별 정보 | 모델 후보 | 최초 관측 | 마지막 관측 | 관측 횟수 | 재등장 | 마지막 RSSI | 거리 추정")
        union.forEach { signal ->
            val distance = estimateDistance(signal.receivedRssi, signal.referenceRssiAt1m)
            appendLine(listOf(signal.source.name, signal.address, signal.equipmentType(), signal.name, signal.manufacturer,
                signal.reportModel(), time(signal.firstSeenMs), time(signal.lastSeenMs), if (signal.source == ScanSource.P2P) "목록 최초 확인" else "${signal.observationCount}회",
                if (signal.source == ScanSource.P2P) "판단 안 함 · OS 목록" else if (signal.periods.isEmpty()) "기록 없음" else "${signal.reappearanceCount}회", reportRssi(signal.receivedRssi),
                distance?.let { "${it.label} · ${if (it.assumedReference) "미보정" else "1m 기준 사용"}" } ?: "미확인")
                .joinToString(" | ", transform = ::reportField))
        }
        if (union.isEmpty()) appendLine("저장된 주소 없음 · 장비 부재를 뜻하지 않습니다.")
        appendLine()
        appendLine("[주소별 관측 상세]")
        union.forEachIndexed { index, signal ->
            appendLine("${index + 1}. ${signal.source} · ${reportField(signal.address)} · ${reportField(signal.name)}")
            appendLine("  유형 후보: ${reportField(signal.equipmentType())}")
            signal.source.observationNote().takeIf { it.isNotEmpty() }?.let { appendLine("  관측 의미: $it") }
            signal.radioMetadata?.detailFields()?.forEach { (label, value) -> appendLine("  $label: ${reportField(value)}") }
            if (signal.source == ScanSource.WIFI && signal.everHidden) appendLine("  SSID: ${if (signal.hidden) "마지막 수신에 이름 미표시" else "이전에 이름 미표시 · 이력 유지"}")
            appendLine("  회사 식별 정보: ${reportField(signal.manufacturer)}")
            val nameBrand = signal.audioNameMatch?.brand ?: signal.wearableNameMatch?.brand
            nameBrand?.let { appendLine("  광고 이름 기반 제조사 후보: ${reportField(it)}") }
            appendLine("  모델 후보: ${reportField(signal.reportModel())}")
            signal.modelHint?.let { appendLine("  저장 당시 모델 후보: ${reportField(it)}") }
            if (signal.manufacturerIds.isNotEmpty()) appendLine("  광고 회사 코드: " + signal.manufacturerIds.sorted().joinToString { "0x" + it.toString(16).uppercase(Locale.ROOT).padStart(4, '0') })
            appendLine("  최초 ${time(signal.firstSeenMs)} → 마지막 ${time(signal.lastSeenMs)}")
            appendLine("  ${signal.source.observationSummary(signal.firstSeenMs, signal.lastSeenMs, signal.observationCount)} · 대상 시점 기준 ${signal.presence.labelFor(signal.source)}")
            if (signal.source == ScanSource.P2P) appendLine("  시스템 목록 최초 확인 · 실제 수신 구간·재등장 산출 안 함")
            else if (signal.periods.isEmpty()) appendLine("  관측 구간 기록 없음 · 재등장 횟수 판단 불가") else {
                appendLine("  관측 구간 ${signal.periods.size}개 · 재등장 ${signal.reappearanceCount}회")
                signal.periods.forEachIndexed { periodIndex, period ->
                    val gap = signal.periods.getOrNull(periodIndex - 1)?.let { period.firstSeenMs - it.lastSeenMs }
                    appendLine("    구간 ${periodIndex + 1}: ${time(period.firstSeenMs)} → ${time(period.lastSeenMs)} · ${period.count}회" +
                        (gap?.let { " · 이전 수신과 ${it}ms 공백" } ?: ""))
                }
            }
            appendLine("  마지막 RSSI: ${reportRssi(signal.receivedRssi)}")
            estimateDistance(signal.receivedRssi, signal.referenceRssiAt1m)?.let { estimate ->
                appendLine("  거리 추정: ${estimate.label} · ${estimate.rangeLabel}")
                appendLine("  1m RSSI 기준: ${if (estimate.assumedReference) "−59 dBm 가정 · 미보정" else "${reportRssi(signal.referenceRssiAt1m)} · 제공된 기준"} · 경로손실 지수 n=2.7")
            }
            appendLine()
        }
        appendLine("[포함된 스냅샷]")
        scoped.forEach { appendLine("${time(it.capturedAtMs)} · ${reportField(it.label)} · ${it.signals.size}개 주소 · 수집 시작 ${time(it.scanStartedAtMs)} · ID ${reportField(it.id)}") }
        appendLine()
        appendLine("[신호 해석 범위]")
        appendLine("BLE 광고·Wi-Fi 접속점(AP), 확장 탐색의 일반 Bluetooth 발견 응답·Wi-Fi Direct 목록을 구분합니다. Wi-Fi 목록은 모든 휴대전화의 목록이 아니며 통신 내용·광고/발견 응답이 없는 연결 기기는 확인할 수 없습니다.")
        appendLine("일반 Bluetooth의 시각은 발견 콜백 수신 시각입니다. Wi-Fi Direct는 OS 캐시일 수 있는 목록의 최초 등재 확인이며 RF 패킷 수신 횟수나 현재 존재·재등장 증거가 아닙니다.")
        appendLine("합집합은 주소 기준이며 물리적 장비 수가 아닙니다. 임의 주소 변경이나 BLE·Wi-Fi 중복으로 한 장비가 여러 주소로 보일 수 있습니다.")
        appendLine("주소별 상세는 가장 최근 수신이 담긴 저장 기록을 사용합니다. 누적 스냅샷의 횟수를 합산하지 않으며 서로 다른 탐색 세션의 구간을 합치지 않습니다.")
        appendLine("관측 구간은 최초~마지막 수신 범위이며 연속 송신·연결 시간이 아닙니다. BLE 15초 / Wi-Fi 90초 / 일반 Bluetooth 180초 초과 공백 뒤 관측을 재등장으로 구분합니다. 뒤늦게 받은 패킷으로 구간이 합쳐질 수 있습니다.")
        appendLine("유형·제조사·모델은 광고 이름·회사 코드에 근거한 후보이며 실제 제품을 인증하지 않습니다. MAC 회사 정보만으로 모델을 확정하지 않습니다.")
        appendLine("거리는 마지막 RSSI를 이용한 모델 추정입니다. 범위 계산은 RSSI ±6 dB, n=2~4, 미보정 1m 기준 ±10 dB를 사용하며 통계적 신뢰구간이 아닙니다. 벽·인체·송신 출력에 따라 실제 거리는 범위 밖일 수 있습니다.")
        appendLine("표의 광고 이름 등에서 줄바꿈·탭·제어문자는 \\n·\\t·\\uXXXX 문자 표기로 표시합니다.")
    }
}

private fun StringBuilder.appendCellEnvironmentReport(before: SignalSnapshot, after: SignalSnapshot, time: (Long) -> String) {
    appendLine("[이동통신 기지국 환경 · 장비 비교와 분리]")
    appendLine("이 휴대전화의 기본 SIM 모뎀이 제공한 기지국 정보입니다. 주변 휴대전화 목록이 아니며 추가 장비·관측 합집합·거리 추정에 포함하지 않습니다.")
    listOf("기준" to before.cellEnvironment, "대상" to after.cellEnvironment).forEach { (label, environment) ->
        if (environment == null) appendLine("$label: 기록 없음 · 미탐색 또는 이전 형식")
        else {
            appendLine("$label: ${environment.status.label} · 환경 기록 ${time(environment.capturedAtMs)}")
            environment.note?.let { appendLine("  ${reportField(it)}") }
            environment.error?.let { appendLine("  ${reportField(it)}") }
            environment.readings.forEach { cell ->
                appendLine("  ${cell.technology} · ${reportField(cell.identity)} · 채널 ${cell.channel ?: "미확인"} · ${cell.dbm?.let { "$it dBm" } ?: "세기 미확인"} · ${if (cell.registered) "등록 셀" else "주변 셀"} · 모뎀 관측 ${time(cell.observedAtMs)} · ${if (cell.cached) "요청 전 캐시" else "해당 요청 관측"}")
            }
        }
    }
    appendLine("빈 목록·오류·캐시는 주변 휴대전화가 없다는 뜻이 아닙니다. 기지국 변경도 수험생의 장비 등장을 뜻하지 않습니다.")
    appendLine()
}

private fun StringBuilder.appendWifiChannelReport(analysis: WifiChannelAnalysis, time: (Long) -> String) {
    appendLine("[Wi-Fi 채널 변화]")
    appendLine(analysis.message)
    appendLine("숨김 SSID 이력 ${analysis.hiddenAPCount}개 주소 · 추가 숨김 AP ${analysis.addedHiddenAPCount?.toString() ?: "판단 불가"} · 최근 숨김 AP ${if (analysis.targetChannelDataAvailable) analysis.recentHiddenAPCount.toString() else "판단 불가"}")
    appendLine("AP 수는 BSSID 기준입니다. 최근 AP는 대상 시점의 90초 이내 마지막 관측 채널이며, 전체 이력은 선택 범위에 남은 주소·주파수 기록입니다.")
    appendLine("채널 | 기준 대비 | 기준 AP | 최근 AP | 이력 AP | 추가 주소 | 채널 변경 주소 | 최초 수신 | 마지막 수신 | 최대 RSSI")
    analysis.channels.forEach { channel ->
        val status = when (channel.isNewChannel) { true -> "기준 미관측"; false -> "기준 관측"; null -> "비교 불가" }
        appendLine(listOf(channel.label, status, channel.baselineAPCount?.toString() ?: "—", if (analysis.targetChannelDataAvailable) channel.recentAPCount.toString() else "—",
            channel.observedAPCount.toString(), channel.addedAPCount?.toString() ?: "—", channel.movedAPCount?.toString() ?: "—",
            time(channel.firstSeenMs), time(channel.lastSeenMs), reportRssi(channel.peakRssi?.toDouble())).joinToString(" | ", transform = ::reportField))
        channel.accessPoints.forEach { point ->
            val observation = point.observation
            val presence = when { point.latestChannelAmbiguous -> "최근 채널 판단 보류"; point.isRecent -> "90초 이내 수신"; else -> "과거 관측 이력" }
            appendLine("  ${reportField(observation.address)} · ${reportField(observation.name)} · $presence · ${observation.observationCount}회 수신")
            appendLine("    최초 ${time(observation.firstSeenMs)} → 마지막 ${time(observation.lastSeenMs)} · 마지막 ${reportRssi(observation.lastRssi?.toDouble())}")
            if (point.hasHiddenHistory) appendLine("    SSID: ${if (observation.hidden) "마지막 수신에 이름 미표시" else "이전에 이름 미표시 · 이력 유지"}")
            observation.wifiMetadata?.detailFields()?.forEach { (label, value) -> appendLine("    $label: ${reportField(value)}") }
            if (point.movedFromFrequenciesMhz.isNotEmpty()) appendLine("    같은 주소의 채널 변경: ${point.movedFromFrequenciesMhz.joinToString(transform = ::wifiChannelLabel)} → ${channel.label}")
            if (observation.periods.isNotEmpty()) {
                appendLine("    채널 관측 구간 ${observation.periods.size}개 · 재등장 ${observation.reappearanceCount}회")
                observation.periods.forEach { appendLine("      ${time(it.firstSeenMs)} → ${time(it.lastSeenMs)} · ${it.count}회") }
            }
        }
    }
    if (analysis.channels.isEmpty()) appendLine("채널 관측 자료 없음 · Wi-Fi 장비 부재를 뜻하지 않습니다.")
    if (analysis.ambiguousCurrentAddressCount > 0) appendLine("${analysis.ambiguousCurrentAddressCount}개 주소의 마지막 관측이 여러 채널에서 같은 시각이므로 최근 AP 수에서 제외했습니다.")
    appendLine("채널별 AP 분포는 실제 트래픽 사용률이나 간섭량이 아닙니다. 기준 미관측 채널은 기존 AP의 자동 채널 변경이나 수신 누락으로도 나타날 수 있습니다.")
    appendLine("숨김 SSID는 수신 결과에 네트워크 이름이 보이지 않았다는 뜻입니다. 이름을 복원하거나 숨김만으로 위험성을 판정하지 않습니다.")
    appendLine("AP별 수신 횟수·구간은 가장 최근 관측이 담긴 세션 기록이며 누적 저장본을 중복 합산하지 않습니다.")
    appendLine()
}

private fun StringBuilder.appendComparisonRow(group: String, before: SnapshotSignal?, after: SnapshotSignal?) {
    val signal = after ?: requireNotNull(before)
    val delta = before?.receivedRssi?.let { previous -> after?.receivedRssi?.minus(previous) }
    appendLine(listOf(group, signal.source.name, signal.address, signal.name, reportRssi(before?.receivedRssi), reportRssi(after?.receivedRssi),
        delta?.let { String.format(Locale.ROOT, "%+.1f dB", it) } ?: "—").joinToString(" | ", transform = ::reportField))
}

private fun SnapshotSignal.reportModel(): String = audioNameMatch?.model ?: wearableNameMatch?.model ?: modelHint ?: "미확인"

private fun reportRssi(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f dBm", it) } ?: "—"

/** Preserve untrusted advertising strings as one printable table cell. */
private fun reportField(value: String): String = buildString {
    value.forEach { character -> append(when (character) {
        '\\' -> "\\\\"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        '|' -> "\\|"
        else -> if (character.code < 32 || character.code == 127) "\\u" + character.code.toString(16).padStart(4, '0') else character.toString()
    }) }
}
