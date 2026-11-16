package com.classroom.rfdetector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.core.*

private val mediumSignal = Color(0xFF77A7FF)
private val weakSignal = Color(0xFF64788D)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelDistribution(channels: List<WifiChannelSummary>, onSelect: (WifiChannelSummary) -> Unit) {
    val maximum = (channels.maxOfOrNull { it.recentAPCount } ?: 1).coerceAtLeast(1)
    val fontScale = LocalDensity.current.fontScale
    ChannelPanel {
        Text("채널별 AP 분포", fontWeight = FontWeight.SemiBold)
        Text("최근 90초 · AP 수와 수신 세기", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SignalLegend("강 ≥−60", RfColors.primary)
            SignalLegend("중 −79~−61", mediumSignal)
            SignalLegend("약 ≤−80", weakSignal)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            channels.forEach { channel ->
                val recent = channel.accessPoints.filter { it.isRecent }
                val strong = recent.count { (it.observation.lastRssi ?: Int.MIN_VALUE) >= -60 }
                val medium = recent.count { it.observation.lastRssi?.let { rssi -> rssi in -79..-61 } == true }
                val weak = recent.count { it.observation.lastRssi != null && it.observation.lastRssi!! <= -80 }
                val unknown = recent.count { it.observation.lastRssi == null }
                val description = "${channel.label}, 최근 AP ${channel.recentAPCount}개, 강 $strong, 중 $medium, 약 $weak, 세기 미확인 $unknown" + if (channel.isNewChannel == true) ", 기준에 없던 채널" else ""
                Column(Modifier.width((60 * fontScale).dp).clickable { onSelect(channel) }.testTag("channel-chart-${channel.frequencyMhz}").semantics { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (channel.isNewChannel == true) "새 채널" else " ", color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.height((108 + 30 * fontScale).dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${channel.recentAPCount}", color = RfColors.textPrimary, style = MaterialTheme.typography.labelMedium)
                            Column(Modifier.width(38.dp)) {
                                listOf(unknown to RfColors.surfaceAlt, weak to weakSignal, medium to mediumSignal, strong to RfColors.primary).forEach { (count, color) ->
                                    if (count > 0) Box(Modifier.fillMaxWidth().height((108f * count / maximum).dp).background(color))
                                }
                                if (channel.recentAPCount == 0) Box(Modifier.fillMaxWidth().height(4.dp).background(RfColors.surfaceAlt))
                            }
                        }
                    }
                    Text(channel.channelNumber?.let { "채널 $it" } ?: "${channel.frequencyMhz}", color = if (channel.isNewChannel == true) RfColors.warning else RfColors.textPrimary, style = MaterialTheme.typography.labelMedium)
                    Text(channel.band.label, color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("가로로 밀고 채널을 누르세요. 이력만 남은 채널은 막대가 0입니다.", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
        Text("AP 개수 분포이며 실제 트래픽 혼잡률·간섭률이 아닙니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SignalLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = RfColors.textDim)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelOverview(channel: WifiChannelSummary, targetDataAvailable: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, color = RfColors.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("channel-card-${channel.frequencyMhz}")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RfGlyph(RfSymbol.WIFI, color = if (channel.isNewChannel == true) RfColors.warning else RfColors.primary)
                Column(Modifier.weight(1f)) {
                    Text(channel.label, fontWeight = FontWeight.Bold)
                    Text(if (channel.isNewChannel == true) "기준에 없던 채널" else if (channel.isNewChannel == false) "기준에서 관측한 채널" else "기준 비교 미확정",
                        color = if (channel.isNewChannel == true) RfColors.warning else RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("기준 AP ${channel.baselineAPCount?.toString() ?: "—"}", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                Text(if (targetDataAvailable) "최근 AP ${channel.recentAPCount}" else "최근 AP — · 자료 없음", color = RfColors.primary, style = MaterialTheme.typography.bodySmall)
                Text("이력 전체 ${channel.observedAPCount}", style = MaterialTheme.typography.bodySmall)
            }
            if (channel.addedAPCount != null) Text("새 AP ${channel.addedAPCount} · 기존 AP 이동 ${channel.movedAPCount ?: 0}", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            val hidden = channel.accessPoints.count { it.hasHiddenHistory }
            if (hidden > 0) Text("숨김 SSID 이력 포함 · AP ${hidden}개", color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
            Text("최초 ${snapshotTime(channel.firstSeenMs)}\n마지막 ${snapshotTime(channel.lastSeenMs)}", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
            Text("AP·관측 시간 상세 보기 ›", color = RfColors.primary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun ChannelApDetail(ap: WifiChannelAccessPoint, targetDataAvailable: Boolean = true) {
    val observation = ap.observation
    var periodsExpanded by rememberSaveable(observation.matchKey) { mutableStateOf(false) }
    Surface(color = RfColors.surfaceAlt, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(observation.name, fontWeight = FontWeight.SemiBold)
            Text(observation.address, color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
            HiddenWifiLabel(observation.hidden, ap.hasHiddenHistory)
            Text(when { !targetDataAvailable -> "대상 채널 자료 없음 · 기준·이전 이력"; ap.latestChannelAmbiguous -> "최신 채널 미확정"; ap.isRecent -> "최근 90초 수신"; else -> "수신 이력 · 현재 채널 단정 불가" },
                color = if (ap.latestChannelAmbiguous) RfColors.warning else RfColors.primary, style = MaterialTheme.typography.bodySmall)
            if (ap.isAddedAddress == true) Text("기준에 없던 AP 주소", color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
            if (ap.movedFromFrequenciesMhz.isNotEmpty()) Text("기준 AP의 채널 이동\n${ap.movedFromFrequenciesMhz.joinToString { wifiChannelLabel(it) }} → ${wifiChannelLabel(observation.frequencyMhz)}",
                color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
            Text("마지막 ${observation.lastRssi?.let { "$it dBm" } ?: "세기 미확인"} · 최대 ${observation.peakRssi?.let { "$it dBm" } ?: "미확인"}", style = MaterialTheme.typography.bodySmall)
            observation.wifiMetadata?.let { RadioMetadataPanel(RadioMetadata(wifi = it)) }
            Text("최초 ${snapshotTime(observation.firstSeenMs)}\n마지막 ${snapshotTime(observation.lastSeenMs)}", style = MaterialTheme.typography.bodySmall)
            Text(observationSpan(observation.firstSeenMs, observation.lastSeenMs, observation.observationCount), color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            if (observation.periods.isNotEmpty()) Text("${observation.periods.size}개 관측 구간 · 재등장 ${observation.reappearanceCount}회", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
            ExplanationToggle("관측 구간 보기", periodsExpanded, { periodsExpanded = !periodsExpanded })
            if (periodsExpanded) {
                if (observation.periods.isEmpty()) Text("이 자료에는 관측 구간 정보가 없습니다.", style = MaterialTheme.typography.bodySmall)
                observation.periods.forEachIndexed { index, period ->
                    Text("${if (index == 0) "첫 포착" else "재등장 $index"} · ${period.count}회\n${snapshotTime(period.firstSeenMs)} → ${snapshotTime(period.lastSeenMs)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("같은 주소·채널에서 90초 초과 미수신 뒤 다시 받으면 새 구간입니다. 연결·사용 시간은 아닙니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun ChannelExplanation() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("새 채널은 입실 전 기준에 없었지만 선택한 범위에서 관측된 주파수입니다. 새 AP는 기준에 없던 주소이며, 기존 AP가 채널만 바꾼 경우와 구분합니다.", style = MaterialTheme.typography.bodySmall)
        Text("막대는 최근 90초 안에 받은 AP의 최신 채널을 한 번씩 셉니다. 같은 주소의 최신 시각이 여러 채널에서 같으면 최신 채널을 확정하지 않습니다. 사라진 AP와 이전 채널은 이력에 남습니다.", style = MaterialTheme.typography.bodySmall)
        Text("RSSI 색은 AP의 마지막 수신 세기입니다. 강 ≥−60, 중 −79~−61, 약 ≤−80 dBm으로 나눈 표시이며 통신 속도·안전도 등급이 아닙니다.", style = MaterialTheme.typography.bodySmall)
        Text("숨김 SSID는 네트워크 이름이 표시되지 않은 관측입니다. 이름을 나중에 받아도 이전 숨김 이력은 남습니다. 숨김 자체로 위험성이나 부정행위를 확정할 수 없습니다.", style = MaterialTheme.typography.bodySmall)
        Text("가까운 2.4 GHz 채널은 대역이 겹칠 수 있습니다. AP 개수와 주파수 배치만으로 실제 간섭량은 알 수 없습니다. 채널 폭·트래픽 사용률·패킷 오류율은 이 그래프에서 측정하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        Text("Wi-Fi는 약 30초마다 탐색을 요청하며 실제 갱신은 Android 제한에 영향을 받습니다. 접속점(AP) 분포이며 연결된 모든 휴대전화의 목록은 아닙니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
    }
}
