package com.classroom.rfdetector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.core.*
import kotlin.math.absoluteValue

private enum class NetworkSection { RECENT, HISTORY, UNCERTAIN }

/** Network names are presentation; a BSSID remains the unit of observation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WifiNetworksPanel(
    analysis: WifiNetworkAnalysis,
    simulation: Boolean = false,
    savedComparison: Boolean = false,
    scanning: Boolean = false,
    onUseCurrent: () -> Unit = {},
    onSimAddWifi: (() -> Unit)? = null,
    onSimAddHidden: (() -> Unit)? = null,
) {
    var band by rememberSaveable { mutableStateOf<WifiBand?>(null) }
    var section by rememberSaveable { mutableStateOf(NetworkSection.RECENT) }
    var hiddenOnly by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var graphOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var explanationOpen by rememberSaveable { mutableStateOf(false) }
    var selectedAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val uncertain = analysis.ambiguousNetworks + analysis.unknownNetworks
    val filtered = band != null || hiddenOnly || query.isNotBlank()
    val source = when (section) {
        NetworkSection.RECENT -> analysis.recentNetworks
        NetworkSection.HISTORY -> analysis.historyNetworks
        NetworkSection.UNCERTAIN -> uncertain
    }
    val visible = source.filter { network ->
        (band == null || network.matchesBand(band!!)) && (!hiddenOnly || network.hasHiddenHistory) &&
            (query.isBlank() || network.address.contains(query.trim(), true) || network.name?.contains(query.trim(), true) == true)
    }
    val graphAnalysis = analysis.copy(networks = analysis.networks.filter { network ->
        (!hiddenOnly || network.hasHiddenHistory) && (query.isBlank() || network.address.contains(query.trim(), true) || network.name?.contains(query.trim(), true) == true)
    })
    val graphBands = band?.let(::listOf) ?: graphAnalysis.recentNetworks.mapNotNull { it.band }.distinct()
        .sortedBy { it.ordinal }.ifEmpty { listOf(WifiBand.GHZ_2_4) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ChannelPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RfGlyph(RfSymbol.WIFI)
                    Text("주변 Wi-Fi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (simulation) Text("연습 데이터 · 실제 무선 수신 아님", color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
                Text(if (analysis.capturedAtMs <= 0) "탐색 전 · 기록 없음" else "${when { savedComparison -> "저장된 시점"; scanning -> "현재 탐색"; else -> "탐색 중지 · 마지막 기록" }} · ${snapshotTime(analysis.capturedAtMs)}",
                    color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                if (!analysis.targetDataAvailable) Text("대상에 주파수 기록이 없습니다 · 최근 네트워크 수는 미확인입니다", color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
                if (savedComparison) TextButton(onClick = onUseCurrent) { Text("현재 네트워크로 돌아가기") }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(section == NetworkSection.RECENT, { section = NetworkSection.RECENT }, label = { Text("최근 확인 · ${analysis.recentAPCount ?: "—"}") }, modifier = Modifier.testTag("networks-recent"))
                FilterChip(section == NetworkSection.HISTORY, { section = NetworkSection.HISTORY }, label = { Text("이력 · ${analysis.historyNetworks.size}") }, modifier = Modifier.testTag("networks-history"))
                FilterChip(section == NetworkSection.UNCERTAIN, { section = NetworkSection.UNCERTAIN }, label = { Text("미확정 · ${uncertain.size}") }, modifier = Modifier.testTag("networks-uncertain"))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExplanationToggle("검색·필터", filtersOpen) { filtersOpen = !filtersOpen }
                ExplanationToggle("채널·세기 그래프", graphOpen) { graphOpen = !graphOpen }
            }
            if (filtersOpen) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(band == null, { band = null }, label = { Text("전체 대역") }, modifier = Modifier.testTag("network-band-all"))
                    listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_6).forEach { value ->
                        FilterChip(band == value, { band = value }, label = { Text(value.label) }, modifier = Modifier.testTag("network-band-${value.name}"))
                    }
                    if (analysis.networks.any { it.matchesBand(WifiBand.OTHER) }) FilterChip(band == WifiBand.OTHER,
                        { band = WifiBand.OTHER }, label = { Text("기타") }, modifier = Modifier.testTag("network-band-OTHER"))
                }
                FilterChip(hiddenOnly, { hiddenOnly = !hiddenOnly }, label = { Text("숨김 이력") }, leadingIcon = { RfGlyph(RfSymbol.HIDDEN, size = 16.dp) }, modifier = Modifier.testTag("networks-hidden"))
                OutlinedTextField(query, { query = it }, label = { Text("SSID · BSSID 검색") }, modifier = Modifier.fillMaxWidth().testTag("network-search"), singleLine = true)
                onSimAddWifi?.let { TextButton(onClick = it) { Text("+ 가상 Wi-Fi 추가") } }
                onSimAddHidden?.let { TextButton(onClick = it) { RfGlyph(RfSymbol.HIDDEN); Spacer(Modifier.width(5.dp)); Text("가상 숨김 AP 추가") } }
            }
            if (band != null || hiddenOnly || query.isNotBlank()) Text("${band?.label ?: "전체 대역"}${if (hiddenOnly) " · 숨김 이력" else ""}${if (query.isNotBlank()) " · 검색 적용" else ""} · ${visible.size}개 표시",
                color = RfColors.primary, style = MaterialTheme.typography.labelSmall)
            if (graphOpen) Text("최근 90초 · 최신 채널이 확정된 AP만 표시합니다. 위 대역·검색·숨김 필터를 적용합니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        }
        if (graphOpen) {
            if (!analysis.targetDataAvailable) item { ChannelPanel { Text("최근 채널·세기 자료가 없어 그래프를 표시할 수 없습니다", color = RfColors.warning) } }
            else items(graphBands, key = { "graph-${it.name}" }) { plotBand ->
                WifiNetworkChannelMap(wifiNetworkPlot(graphAnalysis, plotBand)) { selectedAddress = it.address }
            }
        }
        if (section != NetworkSection.RECENT) item {
            Text(when (section) {
                NetworkSection.RECENT -> "최근 확인된 네트워크"
                NetworkSection.HISTORY -> "사라져도 남는 네트워크 이력"
                NetworkSection.UNCERTAIN -> "최신 채널·현재 상태 미확정"
            }, fontWeight = FontWeight.SemiBold)
            Text(when (section) {
                NetworkSection.RECENT -> "최근 90초 안에 확인한 주소별 최신 채널"
                NetworkSection.HISTORY -> "마지막 기록을 보존합니다 · 현재 근처에 있다는 뜻은 아닙니다"
                NetworkSection.UNCERTAIN -> "같은 시각의 채널이 여러 개이거나 대상 자료가 없습니다 · 현재 그래프에서 제외합니다"
            }, color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        }
        if (visible.isEmpty()) item {
            EmptyState(if (!analysis.targetDataAvailable && section == NetworkSection.RECENT) "최근 네트워크 자료 미확인"
                else if (section == NetworkSection.RECENT && filtered) "이 조건의 네트워크가 없습니다"
                else if (section == NetworkSection.RECENT) "아직 관측된 네트워크가 없습니다" else "이 조건의 기록이 없습니다",
                if (filtered) "대역·검색·숨김 조건을 바꿔 보세요. 기존 기록은 유지됩니다."
                else if (section == NetworkSection.RECENT) "새 결과·권한·수신 상태를 확인하세요. 0개는 주변 장비가 없다는 뜻이 아닙니다." else "다른 기록 구분을 선택하세요.")
        }
        items(visible, key = { it.address }) { network -> WifiNetworkRow(network, analysis.targetDataAvailable) { selectedAddress = network.address } }
        item {
            ExplanationToggle("목록·그래프의 의미", explanationOpen) { explanationOpen = !explanationOpen }
            if (explanationOpen) WifiNetworkExplanation(analysis)
        }
    }
    analysis.networks.firstOrNull { it.address == selectedAddress }?.let { network ->
        AlertDialog(onDismissRequest = { selectedAddress = null }, title = { Text(network.displaySsid()) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("BSSID · ${network.address}", color = RfColors.primary)
                Text(networkStatus(network, analysis.targetDataAvailable), color = RfColors.warning)
                Text("선택 범위의 주소 기록\n최초 ${snapshotTime(network.firstSeenMs)}\n마지막 ${snapshotTime(network.lastSeenMs)}", style = MaterialTheme.typography.bodySmall)
                if (network.channelAmbiguous) Text("같은 최신 시각에 여러 채널이 있어 하나를 고르지 않았습니다. 아래는 각각의 관측 기록입니다.", style = MaterialTheme.typography.bodySmall, color = RfColors.textDim)
                network.latestCandidates.forEach { point ->
                    Text(wifiChannelLabel(point.observation.frequencyMhz), fontWeight = FontWeight.SemiBold)
                    ChannelApDetail(point, analysis.targetDataAvailable)
                }
                Text("주소가 다르면 같은 SSID도 별도 AP로 표시합니다. 물리적 기기 수·소유자·연결된 휴대전화 수는 알 수 없습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { selectedAddress = null }) { Text("닫기") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WifiNetworkRow(network: WifiNetwork, targetDataAvailable: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = RfColors.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("network-row-${network.address}")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RfGlyph(RfSymbol.WIFI, color = networkColor(network.address))
                Column(Modifier.weight(1f)) {
                    Text(network.displaySsid(), fontWeight = FontWeight.Bold)
                    Text("BSSID · ${network.address}", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(network.frequencyMhz?.let(::wifiChannelLabel) ?: "최신 채널 미확정", color = RfColors.primary, style = MaterialTheme.typography.bodyMedium)
                Text(signalLevel(network.latestRssi), style = MaterialTheme.typography.bodyMedium)
            }
            Text(networkStatus(network, targetDataAvailable), color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
            if (network.isAddedAddress == true) Text("추가 · 기준에 없던 AP 주소", color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
            if (network.hidden == true || network.hasHiddenHistory) HiddenWifiLabel(network.hidden == true, network.hasHiddenHistory)
            if (network.hidden == null && network.hasHiddenHistory) Text("최신 SSID 표시 상태 미확정", color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
            network.wifiMetadata?.channelWidthLabel?.let { Text("보고된 대역폭 $it", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall) }
            if (network.movedFromFrequenciesMhz.isNotEmpty()) Text("기준 주소의 채널 이동 기록", color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
            Text("첫 확인 ${snapshotTime(network.firstSeenMs)}\n마지막 ${snapshotTime(network.lastSeenMs)}", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
            Text("네트워크·관측 구간 보기 ›", color = RfColors.primary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Measured primary-channel position and last RSSI, never a fabricated spectrum envelope. */
@Composable
internal fun WifiNetworkChannelMap(plot: WifiNetworkPlot, onSelect: (WifiNetwork) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    ChannelPanel {
        Text("${plot.band.label} · 채널·수신 세기", fontWeight = FontWeight.SemiBold)
        Text("AP별 마지막 RSSI · 실제 스펙트럼·혼잡도 아님", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
        if (!plot.targetDataAvailable) Text("대상 자료 없음 · 최근 분포 미확인", color = RfColors.warning)
        else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val graphWidth = maxOf(maxWidth, ((if (plot.ticks.size > 5) 440 else 320) * fontScale).dp)
                val graphHeight = (220 * fontScale).dp
                val left = 40.dp
                val top = 16.dp
                val spanX = graphWidth - left - 16.dp
                val spanY = graphHeight - top - (30 * fontScale).dp
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Box(Modifier.width(graphWidth).height(graphHeight)) {
                        Canvas(Modifier.fillMaxSize().testTag("network-map-${plot.band.name}")
                            .semantics { contentDescription = "${plot.band.label} 주채널과 RSSI 분포. ${plot.points.size}개 마커. 아래 네트워크 이름을 눌러 상세를 확인하세요." }
                            .pointerInput(plot) {
                                detectTapGestures { position ->
                                    val nearest = plot.points.minByOrNull { point ->
                                        val dx = left.toPx() + spanX.toPx() * point.xFraction - position.x
                                        val dy = top.toPx() + spanY.toPx() * point.yFraction - position.y
                                        dx * dx + dy * dy
                                    }
                                    if (nearest != null) {
                                        val dx = left.toPx() + spanX.toPx() * nearest.xFraction - position.x
                                        val dy = top.toPx() + spanY.toPx() * nearest.yFraction - position.y
                                        if (dx * dx + dy * dy <= 28.dp.toPx() * 28.dp.toPx()) onSelect(nearest.network)
                                    }
                                }
                            }) {
                            (0..4).forEach { step ->
                                val y = top.toPx() + spanY.toPx() * step / 4f
                                drawLine(RfColors.grid, Offset(left.toPx(), y), Offset(left.toPx() + spanX.toPx(), y))
                            }
                            plot.ticks.forEach { tick ->
                                val x = left.toPx() + spanX.toPx() * tick.xFraction
                                drawLine(RfColors.grid.copy(alpha = .5f), Offset(x, top.toPx()), Offset(x, top.toPx() + spanY.toPx()))
                            }
                            plot.points.forEach { point ->
                                val x = left.toPx() + spanX.toPx() * point.xFraction
                                val y = top.toPx() + spanY.toPx() * point.yFraction
                                val color = networkColor(point.network.address)
                                drawLine(color.copy(alpha = .55f), Offset(x, y), Offset(x, top.toPx() + spanY.toPx()), 2.dp.toPx())
                                drawCircle(color, 6.dp.toPx(), Offset(x, y))
                            }
                        }
                        (0..4).forEach { step -> Text("${plot.rssiMaxDbm - (plot.rssiMaxDbm - plot.rssiMinDbm) * step / 4}",
                            Modifier.offset(y = top + spanY * (step / 4f) - 8.dp), color = RfColors.textDim, style = MaterialTheme.typography.labelSmall) }
                        val tickWidth = (56 * fontScale).dp
                        plot.ticks.forEach { tick -> Text(tick.label,
                            Modifier.offset(x = (left + spanX * tick.xFraction - tickWidth / 2).coerceIn(0.dp, graphWidth - tickWidth), y = top + spanY + 4.dp).width(tickWidth),
                            color = RfColors.textDim, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
                    }
                }
            }
            Text("세로 dBm · 가로 ${if (plot.band == WifiBand.OTHER) "주파수(MHz)" else "주채널 번호"} · 넓은 그래프는 가로로 이동", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
            if (plot.points.isEmpty()) Text("그래프에 표시할 최근 채널·세기 관측이 없습니다", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            if ((plot.unknownRssiAPCount ?: 0) > 0) Text("세기 미확인 AP ${plot.unknownRssiAPCount}개 · 그래프에서 제외", color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
            if (plot.points.any { it.rssiClamped }) Text("표시 범위 ${plot.rssiMinDbm}~${plot.rssiMaxDbm} dBm 밖 값은 가장자리에 표시합니다. 아래에는 원래 값이 남습니다.", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
            plot.points.forEach { point ->
                TextButton(onClick = { onSelect(point.network) }, modifier = Modifier.fillMaxWidth().testTag("network-map-open-${point.network.address}")) {
                    Box(Modifier.size(10.dp).background(networkColor(point.network.address), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${point.network.displaySsid()} · ${point.rssiDbm} dBm", color = RfColors.textPrimary)
                        Text("${point.network.address} · ${wifiChannelLabel(point.frequencyMhz)}", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun WifiNetworkExplanation(analysis: WifiNetworkAnalysis) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("기록 전체 ${analysis.observedAPCount}개 주소 · ${analysis.addedAPCount?.let { "기준에 없던 주소 ${it}개" } ?: "기준 비교 미확정"}", color = RfColors.primary, style = MaterialTheme.typography.bodySmall)
        Text("접속점(AP) 목록이며 연결된 휴대전화 수가 아닙니다. 최근 확인은 지금 존재함을 보장하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        Text("SSID는 네트워크 이름이고 BSSID는 AP 주소입니다. 같은 SSID의 여러 주소나 임의 주소 변경을 물리적 기기 수로 단정하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        Text("최근 확인은 대상 시각 기준 90초 안의 주소별 최신 채널입니다. 대역을 고르기 전에 최신 채널을 결정하므로 이전 채널이 현재 네트워크로 되살아나지 않습니다. 최신 시각이 여러 주파수에서 같으면 미확정으로 남깁니다.", style = MaterialTheme.typography.bodySmall)
        Text("그래프의 점은 주채널 위치와 마지막 RSSI입니다. 선은 점의 위치를 쉽게 읽기 위한 것으로 대역폭·전파 스펙트럼·간섭률·트래픽 사용량을 측정한 모양이 아닙니다. 같은 위치의 점은 겹칠 수 있어 아래 이름으로도 선택할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        Text("강함 ≥−60, 보통 −79~−61, 약함 ≤−80 dBm은 보기 위한 구간입니다. 통신 속도·안전도·거리의 확정값이 아닙니다. 대역폭은 Android가 보고한 정보가 있을 때만 표시합니다.", style = MaterialTheme.typography.bodySmall)
        Text("숨김은 마지막 결과에 SSID가 없었다는 뜻입니다. 이전 숨김 이력은 이름을 나중에 받아도 유지하며, 위험성의 판정이 아닙니다.", style = MaterialTheme.typography.bodySmall)
        Text(analysis.comparisonMessage, color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        Text("Android는 Wi-Fi 결과를 제한하거나 지연할 수 있습니다. 앱은 약 30초마다 요청합니다. 수신 상태 탭에서 마지막 결과와 권한 상태를 확인하세요.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
    }
}

private fun WifiNetwork.displaySsid(): String = name ?: if (hidden == true) "숨김 SSID" else "이름 미확인"
private fun networkStatus(network: WifiNetwork, targetDataAvailable: Boolean): String = when {
    !targetDataAvailable -> "대상 자료 없음 · 이전 기록만 확인"
    network.channelAmbiguous -> "최신 채널 미확정 · 현재 그래프 제외"
    network.isRecent == true -> "최근 90초 확인"
    else -> "이력 · 현재 수신 여부 미확인"
}
private fun signalLevel(rssi: Int?): String = rssi?.let { "${when { it >= -60 -> "강함"; it >= -79 -> "보통"; else -> "약함" }} · $it dBm" } ?: "세기 미확인"
private fun networkColor(address: String): Color {
    val colors = listOf(RfColors.primary, Color(0xFF77A7FF), Color(0xFFF4BD72), Color(0xFFCDA5F5), Color(0xFFF58B9B), Color(0xFF96CF9C))
    return colors[(address.hashCode().toLong().absoluteValue % colors.size).toInt()]
}
