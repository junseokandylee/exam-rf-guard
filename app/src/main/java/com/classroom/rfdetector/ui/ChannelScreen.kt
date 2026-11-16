package com.classroom.rfdetector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.AppViewModel
import com.classroom.rfdetector.core.*

enum class UiChannelView { NETWORKS, COMPARISON }

@Composable
fun ChannelScreen(vm: AppViewModel, onOpenComparison: () -> Unit = {},
    initialView: UiChannelView = UiChannelView.NETWORKS, viewRequestKey: Int = 0) {
    val analysis by vm.wifiChannelAnalysis.collectAsState()
    val simulation by vm.simMode.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val savedTarget by vm.comparisonSnapshot.collectAsState()
    ChannelContent(analysis, simulation, savedTarget != null, onOpenComparison, scanning = scanning,
        initialView = initialView, viewRequestKey = viewRequestKey,
        onUseCurrent = { vm.comparisonSnapshot.value = null },
        onSimAddWifi = if (simulation && scanning) ({ vm.simAddWifiDevice() }) else null,
        onSimAddHidden = if (simulation && scanning) ({ vm.simAddWifiDevice(hidden = true) }) else null)
}

/** Kept independent of the scanner so the exact channel-review flow can be exercised with observations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelContent(
    analysis: WifiChannelAnalysis,
    simulation: Boolean = false,
    savedComparison: Boolean = false,
    onOpenComparison: () -> Unit = {},
    onUseCurrent: () -> Unit = {},
    onSimAddWifi: (() -> Unit)? = null,
    onSimAddHidden: (() -> Unit)? = null,
    scanning: Boolean = false,
    initialView: UiChannelView = UiChannelView.NETWORKS,
    viewRequestKey: Int = 0,
) {
    var view by rememberSaveable { mutableStateOf(initialView) }
    var lastHandledRequest by rememberSaveable { mutableIntStateOf(viewRequestKey) }
    val states = rememberSaveableStateHolder()
    LaunchedEffect(initialView, viewRequestKey) {
        if (viewRequestKey != lastHandledRequest) {
            view = initialView
            lastHandledRequest = viewRequestKey
        }
    }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = view == UiChannelView.NETWORKS, onClick = { view = UiChannelView.NETWORKS },
                label = { Text("주변 네트워크") }, leadingIcon = { RfGlyph(RfSymbol.WIFI, size = 16.dp) },
                modifier = Modifier.testTag("wifi-view-networks"))
            FilterChip(selected = view == UiChannelView.COMPARISON, onClick = { view = UiChannelView.COMPARISON },
                label = { Text("채널 비교") }, leadingIcon = { RfGlyph(RfSymbol.UNION, size = 16.dp) },
                modifier = Modifier.testTag("wifi-view-comparison"))
        }
        Box(Modifier.weight(1f)) {
            states.SaveableStateProvider(view.name) {
                if (view == UiChannelView.NETWORKS) WifiNetworksPanel(analyzeWifiNetworks(analysis), simulation,
                    savedComparison, scanning, onUseCurrent, onSimAddWifi, onSimAddHidden)
                else ChannelComparisonContent(analysis, simulation, savedComparison, onOpenComparison,
                    onUseCurrent, onSimAddWifi, onSimAddHidden, scanning)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelComparisonContent(
    analysis: WifiChannelAnalysis,
    simulation: Boolean,
    savedComparison: Boolean,
    onOpenComparison: () -> Unit,
    onUseCurrent: () -> Unit,
    onSimAddWifi: (() -> Unit)?,
    onSimAddHidden: (() -> Unit)?,
    scanning: Boolean,
) {
    var band by rememberSaveable { mutableStateOf<WifiBand?>(null) }
    var newOnly by rememberSaveable { mutableStateOf(false) }
    var hiddenOnly by rememberSaveable { mutableStateOf(false) }
    var helpExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedFrequency by rememberSaveable { mutableStateOf<Int?>(null) }
    val channels = analysis.channels.filter { (band == null || it.band == band) && (!newOnly || !analysis.canCompare || it.isNewChannel == true) &&
        (!hiddenOnly || it.accessPoints.any { ap -> ap.hasHiddenHistory }) }
    val newChannels = analysis.channels.count { it.isNewChannel == true }
    val allAccessPoints = analysis.channels.flatMap { it.accessPoints }
    val addressCount = allAccessPoints.map { it.observation.address.uppercase(java.util.Locale.ROOT) }.distinct().size
    val addedCount = allAccessPoints.filter { it.isAddedAddress == true }.map { it.observation.address.uppercase(java.util.Locale.ROOT) }.distinct().size
    val movedCount = allAccessPoints.filter { it.movedFromFrequenciesMhz.isNotEmpty() }.map { it.observation.address.uppercase(java.util.Locale.ROOT) }.distinct().size
    val hiddenCount = allAccessPoints.filter { it.hasHiddenHistory }.map { it.observation.address.uppercase(java.util.Locale.ROOT) }.distinct().size
    val addedHiddenCount = allAccessPoints.filter { it.hasHiddenHistory && it.isAddedAddress == true }.map { it.observation.address.uppercase(java.util.Locale.ROOT) }.distinct().size

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ChannelPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RfGlyph(RfSymbol.WIFI)
                    Text("Wi-Fi 채널 비교", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (hiddenCount > 0) Text("숨김 이력 AP ${hiddenCount}개${if (analysis.canCompare) " · 새 주소 ${addedHiddenCount}개" else ""}", color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
                Text("AP 분포 · 기준과 달라진 채널", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                if (simulation) Text("연습 데이터 · 실제 무선 수신 아님", color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
                Text(if (analysis.canCompare) "① 기준 채널 · ${snapshotTime(analysis.scopeStartMs)}" else "① 비교 기준 확인", color = RfColors.primary, fontWeight = FontWeight.SemiBold)
                Text(analysis.message, color = if (analysis.canCompare) RfColors.textDim else RfColors.warning, style = MaterialTheme.typography.bodySmall)
                if (analysis.canCompare) {
                    Text("② 기준에 없던 채널 ${newChannels}개", color = if (newChannels > 0) RfColors.warning else RfColors.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("새 AP ${addedCount}개", style = MaterialTheme.typography.bodySmall)
                        Text("기존 AP 채널 이동 ${movedCount}개", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("③ 아래 채널을 눌러 AP·관측 시간 확인", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                Text("${when { savedComparison -> "저장 시점 비교"; scanning -> "현재 수신과 비교"; else -> "중지 시 마지막 관측" }} · ${snapshotTime(analysis.capturedAtMs)}", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                if (!analysis.canCompare) OutlinedButton(onClick = onOpenComparison, modifier = Modifier.fillMaxWidth()) {
                    RfGlyph(RfSymbol.SNAPSHOT); Spacer(Modifier.width(8.dp)); Text("비교에서 기준·대상 선택")
                }
                if (savedComparison) TextButton(onClick = onUseCurrent) { Text("현재 수신과 비교") }
                onSimAddWifi?.let { TextButton(onClick = it) { Text("+ 가상 새 채널 추가") } }
                onSimAddHidden?.let { TextButton(onClick = it) { RfGlyph(RfSymbol.HIDDEN); Spacer(Modifier.width(5.dp)); Text("가상 숨김 AP 추가") } }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = band == null, onClick = { band = null }, label = { Text("전체 대역") }, modifier = Modifier.testTag("channel-band-all"))
                listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_6).forEach { option ->
                    FilterChip(selected = band == option, onClick = { band = option }, label = { Text(option.label) }, modifier = Modifier.testTag("channel-band-${option.name}"))
                }
                if (analysis.channels.any { it.band == WifiBand.OTHER }) FilterChip(selected = band == WifiBand.OTHER, onClick = { band = WifiBand.OTHER }, label = { Text("기타 주파수") })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = newOnly && analysis.canCompare, enabled = analysis.canCompare, onClick = { newOnly = !newOnly }, modifier = Modifier.testTag("channel-new-only"),
                    label = { Text("기준에 없던 채널만 · $newChannels") }, leadingIcon = { RfGlyph(RfSymbol.WIFI, size = 16.dp, color = RfColors.warning) })
                FilterChip(selected = hiddenOnly, onClick = { hiddenOnly = !hiddenOnly }, label = { Text("숨김 이력만 · $hiddenCount") }, modifier = Modifier.testTag("channel-hidden-only"),
                    leadingIcon = { RfGlyph(RfSymbol.HIDDEN, size = 16.dp, color = RfColors.warning) })
            }
            if (hiddenOnly) Text("숨김 이력이 있는 채널만 표시합니다. 막대는 그 채널의 전체 AP 수입니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            if (analysis.ambiguousCurrentAddressCount > 0) Text("최신 채널 미확정 ${analysis.ambiguousCurrentAddressCount}개 주소 · 최근 AP 분포에서 제외", color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
        }
        if (!analysis.targetChannelDataAvailable) item {
            ChannelPanel { Text("대상에 주파수 기록이 없어 최근 AP 분포를 알 수 없습니다", color = RfColors.warning, style = MaterialTheme.typography.bodySmall) }
        } else if (channels.isNotEmpty()) item { ChannelDistribution(channels, onSelect = { selectedFrequency = it.frequencyMhz }) }
        item {
            Text("액세스 포인트 · $addressCount", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${channels.size}개 채널 표시 · 같은 AP의 채널 이력은 여러 채널에 남습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        }
        if (channels.isEmpty()) item {
            EmptyState(if (newOnly && analysis.canCompare) "이 조건의 새 채널이 없습니다" else "이 대역의 채널 기록이 없습니다",
                if (analysis.channels.isEmpty()) "Wi-Fi 탐색 결과를 기다리세요. 이전 버전의 자료에는 채널 정보가 없을 수 있습니다." else "대역 또는 새 채널 필터를 바꾸세요. 기존 수신 이력은 유지됩니다.")
        }
        items(channels, key = { it.frequencyMhz }) { channel ->
            ChannelOverview(channel, targetDataAvailable = analysis.targetChannelDataAvailable, onClick = { selectedFrequency = channel.frequencyMhz })
        }
        item {
            ExplanationToggle("AP 분포·겹침의 의미", helpExpanded, { helpExpanded = !helpExpanded })
            if (helpExpanded) ChannelExplanation()
        }
    }
    analysis.channels.firstOrNull { it.frequencyMhz == selectedFrequency }?.let { channel ->
        AlertDialog(onDismissRequest = { selectedFrequency = null }, title = { Text(channel.label) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${channel.frequencyMhz} MHz · ${if (analysis.targetChannelDataAvailable) "최근 90초 AP ${channel.recentAPCount}개" else "최근 AP 자료 없음"} · 관측 이력 ${channel.observedAPCount}개", color = RfColors.primary)
                if (channel.isNewChannel == true) Text("입실 전 기준에 없던 채널", color = RfColors.warning, fontWeight = FontWeight.SemiBold)
                Text("같은 주소의 이전 채널 기록도 남습니다. 아래에서 수신 시각과 이동 근거를 확인하세요.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                if (hiddenOnly) Text("숨김 이력 필터 적용 · 이름을 나중에 받은 AP도 포함", color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
                channel.accessPoints.filter { !hiddenOnly || it.hasHiddenHistory }.forEach { ap -> ChannelApDetail(ap, analysis.targetChannelDataAvailable) }
            }
        }, confirmButton = { TextButton(onClick = { selectedFrequency = null }) { Text("닫기") } })
    }
}

// Preserve UI utility callers while keeping the channel conversion in the shared analysis layer.
internal fun channelNumber(frequency: Int): Int? = wifiChannelNumber(frequency)
internal fun channelLabel(frequency: Int): String = wifiChannelLabel(frequency)

@Composable
internal fun ChannelPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = RfColors.surface, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
