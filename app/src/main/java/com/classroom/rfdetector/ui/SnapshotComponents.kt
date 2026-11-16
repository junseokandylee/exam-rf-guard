package com.classroom.rfdetector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SnapshotSteps(current: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("입실 전 저장", "입실 후 비교", "추가 장비 확인").forEachIndexed { index, title ->
            val step = index + 1
            val active = step == current
            Surface(color = if (active) MaterialTheme.colorScheme.secondaryContainer else RfColors.surface,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).semantics {
                    stateDescription = when { active -> "현재 단계"; step < current -> "완료"; else -> "다음 단계" }
                }) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$step", color = if (step <= current) RfColors.primary else RfColors.textDim,
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (step < current) RfGlyph(RfSymbol.CHECK)
                    }
                    Text(title, color = if (active) RfColors.primary else RfColors.textDim,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun SignalSummary(signal: SnapshotSignal) {
    val audio = signal.audioNameMatch
    val wearable = signal.wearableNameMatch
    val nameBrand = audio?.brand ?: wearable?.brand
    val distance = estimateDistance(signal.receivedRssi, signal.referenceRssiAt1m)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (signal.source == ScanSource.WIFI) HiddenWifiLabel(signal.hidden, signal.everHidden)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val symbol = when {
                signal.source == ScanSource.WIFI || signal.source == ScanSource.P2P -> RfSymbol.WIFI
                audio?.kind == AudioKind.EARPHONES -> RfSymbol.EARPHONES
                audio?.kind == AudioKind.HEADPHONES -> RfSymbol.HEADPHONES
                wearable != null -> wearable.kind.symbol()
                else -> RfSymbol.BLUETOOTH
            }
            SummaryField("유형", signal.equipmentType(), Modifier.weight(1f), symbol)
            SummaryField(if (nameBrand != null) "제조사 후보" else "제조사·등록사", nameBrand ?: signal.manufacturer, Modifier.weight(1f), RfSymbol.COMPANY)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryField("모델 후보", audio?.model ?: wearable?.model ?: signal.modelHint ?: "미확인", Modifier.weight(1f), RfSymbol.MODEL)
            if (signal.source == ScanSource.P2P) SummaryField("관측 방식", "시스템 목록 · 캐시 가능", Modifier.weight(1f), RfSymbol.INFO)
            else SummaryField("예상 거리 · 마지막 수신", distance?.let { it.label + if (it.assumedReference) " · 미보정" else " · 추정" } ?: "추정 불가", Modifier.weight(1f), RfSymbol.DISTANCE)
        }
        HorizontalDivider(color = RfColors.grid)
        SummaryField("관측 시간", signal.source.observationSummary(signal.firstSeenMs, signal.lastSeenMs, signal.observationCount), symbol = RfSymbol.CLOCK)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RfGlyph(RfSymbol.RETURN, color = if (signal.reappearanceCount > 0) RfColors.warning else RfColors.textDim)
            Text(if (signal.source == ScanSource.P2P) "OS 목록 정보 · 재등장 판단 안 함" else if (signal.periods.isEmpty()) "구간 이력 없음 · 이전 버전 기록" else "${signal.periods.size}개 관측 구간 · 재등장 ${signal.reappearanceCount}회", color = if (signal.reappearanceCount > 0) RfColors.warning else RfColors.textDim, style = MaterialTheme.typography.labelSmall)
        }
        val endFormat = if (snapshotTime(signal.firstSeenMs).take(5) == snapshotTime(signal.lastSeenMs).take(5)) "HH:mm:ss" else "MM/dd HH:mm:ss"
        Text(if (signal.source == ScanSource.P2P) "목록 첫 확인 · ${snapshotTime(signal.firstSeenMs)}" else "${snapshotTime(signal.firstSeenMs)} → ${SimpleDateFormat(endFormat, Locale.getDefault()).format(Date(signal.lastSeenMs))}",
            color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun HiddenWifiLabel(hidden: Boolean, hasHiddenHistory: Boolean) {
    if (!hidden && !hasHiddenHistory) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RfGlyph(RfSymbol.HIDDEN, size = 18.dp, color = RfColors.warning)
        Text(if (hidden) "숨김 SSID · 마지막 수신에 이름 미표시" else "이전에 SSID 미표시 · 기록 유지",
            color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SummaryField(label: String, value: String, modifier: Modifier = Modifier, symbol: RfSymbol = RfSymbol.INFO) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            RfGlyph(symbol, size = 16.dp, color = RfColors.textDim)
            Text(label, color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
        }
        Text(value, color = RfColors.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ExplanationToggle(title: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.semantics { stateDescription = if (expanded) "펼침" else "접힘" }) {
        RfGlyph(RfSymbol.INFO, size = 16.dp)
        Spacer(Modifier.width(5.dp))
        Text("$title ${if (expanded) "▴" else "▾"}")
    }
}

@Composable
internal fun ObservationPeriodList(signal: SnapshotSignal) {
    if (signal.source == ScanSource.P2P) {
        Text(signal.source.observationNote(), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (signal.periods.isEmpty()) Text("이 기록에는 구간 이력이 없습니다. 이전 버전 자료의 재등장 여부는 판단하지 않습니다.")
    signal.periods.forEachIndexed { index, period ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RfGlyph(if (index == 0) RfSymbol.CLOCK else RfSymbol.RETURN)
            Column {
                Text(if (index == 0) "첫 포착" else "재등장 $index", fontWeight = FontWeight.SemiBold)
                Text("${snapshotTime(period.firstSeenMs)} → ${snapshotTime(period.lastSeenMs)}", style = MaterialTheme.typography.labelSmall)
                Text(observationSpan(period.firstSeenMs, period.lastSeenMs, period.count), style = MaterialTheme.typography.bodySmall)
                if (index > 0) {
                    val gapSeconds = (period.firstSeenMs - signal.periods[index - 1].lastSeenMs) / 1000
                    Text("이전 구간 후 ${gapSeconds / 60}분 ${gapSeconds % 60}초 미수신", style = MaterialTheme.typography.labelSmall, color = RfColors.warning)
                }
            }
        }
    }
    Text("BLE는 15초, Wi-Fi AP는 90초, 일반 Bluetooth는 180초를 넘겨 미관측한 뒤 다시 잡히면 새 구간입니다. 같은 주소의 재포착이며 전원·연결 상태를 뜻하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = RfColors.textDim)
}
