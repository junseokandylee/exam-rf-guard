package com.classroom.rfdetector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.AppViewModel
import com.classroom.rfdetector.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SnapshotScreen(vm: AppViewModel, onOpenChannels: () -> Unit = {}) {
    val records by vm.devices.collectAsState()
    val manualSnapshots by vm.savedSnapshots.collectAsState()
    val checkpoints by vm.checkpoints.collectAsState()
    val snapshots = (manualSnapshots + checkpoints).sortedByDescending { it.capturedAtMs }
    val baseline by vm.baselineSnapshot.collectAsState()
    val savedTarget by vm.comparisonSnapshot.collectAsState()
    val simulation by vm.simMode.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val busy by vm.snapshotBusy.collectAsState()
    val error by vm.snapshotError.collectAsState()
    val area by vm.areaDraft.collectAsState()
    val session by vm.scanSession.collectAsState()
    val healthTick by vm.healthTick.collectAsState()
    val channelAnalysis by vm.wifiChannelAnalysis.collectAsState()
    val cellEnvironment by vm.cellEnvironmentState.collectAsState()
    var label by rememberSaveable { mutableStateOf("입실 전") }
    var group by rememberSaveable { mutableStateOf("추가") }
    var query by rememberSaveable { mutableStateOf("") }
    var radio by rememberSaveable { mutableStateOf(RadioFilter.ALL) }
    var audioOnly by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SignalSort.RSSI) }
    var sortOpen by remember { mutableStateOf(false) }
    var captureExpanded by rememberSaveable { mutableStateOf(true) }
    var captureOptions by rememberSaveable { mutableStateOf(false) }
    var analysisOptions by rememberSaveable { mutableStateOf(false) }
    var resultHelp by rememberSaveable { mutableStateOf(false) }
    var comparisonInfo by rememberSaveable { mutableStateOf(false) }
    var archiveExpanded by rememberSaveable { mutableStateOf(false) }
    var detailHelp by remember { mutableStateOf(false) }
    var timelineExpanded by remember { mutableStateOf(false) }
    var periodsExpanded by remember { mutableStateOf(false) }
    var archiveQuery by rememberSaveable { mutableStateOf("") }
    var currentRoomOnly by rememberSaveable { mutableStateOf(false) }
    var selectedSignal by remember { mutableStateOf<SnapshotSignal?>(null) }
    var selectedFromBaseline by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(baseline?.id) { label = if (baseline == null) "입실 전" else "입실 후" }
    LaunchedEffect(savedTarget?.id) {
        if (savedTarget != null) {
            captureExpanded = false
            withFrameNanos { }
            listState.animateScrollToItem(0)
        }
    }
    val liveTargetAvailable = scanning || records.isNotEmpty()
    val liveCapturedAt = maxOf(healthTick, records.maxOfOrNull { it.lastSeenMs } ?: 0, session?.startedAtMs ?: 0)
    val target = savedTarget ?: SignalSnapshot.capture("live", session?.area ?: area, if (scanning) "현재 수신·신규 이력" else "중지 시 마지막 관측", liveCapturedAt, session?.startedAtMs ?: liveCapturedAt, simulation, records,
        includeNewHistorySinceMs = baseline?.capturedAtMs).let { live -> with(vm) { live.withDiscoveryContext() } }.copy(cellEnvironment = cellEnvironment)
    val comparison = remember(baseline, target, savedTarget, liveTargetAvailable) {
        if (savedTarget == null && !liveTargetAvailable) null else baseline?.let { runCatching { compareSnapshots(it, target) } }
    }
    val result = comparison?.getOrNull()
    val union = remember(baseline, target, snapshots, result) {
        if (result == null) emptyList() else baseline?.let { observedUnion(it, target, snapshots) } ?: emptyList()
    }
    val available = snapshots.filter { it.simulation == simulation }
    val archiveRows = remember(snapshots, simulation, archiveQuery, currentRoomOnly, area) {
        filterSnapshotArchive(snapshots, simulation, archiveQuery, if (currentRoomOnly) area else null)
    }
    val groupRows = when (group) { "추가" -> result?.added; "미관측" -> result?.notObserved; "공통" -> result?.common?.map { it.after }; else -> union } ?: emptyList()
    val changes = remember(result) { result?.common?.associate { it.after.matchKey to it.deltaDb } ?: emptyMap() }
    val effectiveSort = if (group != "공통" && sort == SignalSort.CHANGE) SignalSort.RSSI else sort
    val rows = remember(groupRows, query, radio, audioOnly, effectiveSort, changes) {
        filterAndSortSignals(groupRows, query, radio, audioOnly, effectiveSort, changes)
    }

    Column(Modifier.fillMaxSize()) {
    SnapshotSteps(when { baseline == null -> 1; savedTarget == null || result == null -> 2; else -> 3 })
    LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (captureExpanded) item {
            Surface(color = RfColors.surface, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (baseline == null) "빈 시험장을 먼저 저장하세요" else "입실 후, 같은 자리에서 비교하세요", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(area, vm::updateAreaDraft, label = { Text("공간 이름 · 예: 3층 제1시험실") }, readOnly = scanning && session?.areaLocked == true, singleLine = true, modifier = Modifier.fillMaxWidth())
                    session?.takeIf { it.areaLocked }?.let {
                        Text("이번 탐색 공간: ${it.area}", color = RfColors.primary, style = MaterialTheme.typography.labelSmall)
                        if (scanning) Text("공간을 바꾸려면 탐색을 중지하세요.", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                        if (!it.area.equals(area.trim(), true)) Text("다른 공간은 탐색을 중지한 뒤 다시 시작하세요. 기존 저장 자료는 유지됩니다.", color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
                    }
                    if (captureOptions) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("입실 전", "입실 후", "시험 중").forEach { stage -> FilterChip(label = { Text(stage) }, selected = label == stage, onClick = { label = stage }) }
                    }
                    if (captureOptions) OutlinedTextField(label, { label = it.take(80) }, label = { Text("시점 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(if (simulation) "연습 데이터" else "Wi-Fi 결과도 확인한 뒤 저장하세요.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                    if (scanning && !simulation) {
                        val now = maxOf(healthTick, System.currentTimeMillis())
                        val bleHealth = bleScanHealth(true, vm.ble.isUsable(), vm.ble.active, vm.ble.lastReceivedAtMs, vm.ble.lastError, now)
                        val wifiHealth = wifiScanHealth(true, vm.wifi.isEnabled(), !vm.locationServicesOff(), vm.wifi.lastUpdatedMs, vm.wifi.lastError, now)
                        Text("BLE ${bleHealth.label} · Wi-Fi ${wifiHealth.label}", color = if (bleHealth.warning || wifiHealth.warning) RfColors.warning else RfColors.primary, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("${if (baseline == null) "이번 탐색에 포착된" else "최근 수신"} 주소 · BLE ${records.count { it.source == ScanSource.BLE && (baseline == null || it.presence != SignalPresence.HISTORY) }} · Wi-Fi ${records.count { it.source == ScanSource.WIFI && (baseline == null || it.presence != SignalPresence.HISTORY) }}", color = RfColors.primary, style = MaterialTheme.typography.labelSmall)
                    val classicCount = records.count { it.source == ScanSource.CLASSIC && (baseline == null || it.presence != SignalPresence.HISTORY) }
                    val directCount = records.count { it.source == ScanSource.P2P }
                    if (classicCount > 0 || directCount > 0) Text("일반 Bluetooth $classicCount · Wi-Fi Direct 목록 $directCount", color = RfColors.primary, style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { vm.saveSnapshot(area, label, asBaseline = baseline == null) }, enabled = scanning && !busy && area.isNotBlank() && label.isNotBlank(), modifier = Modifier.fillMaxWidth()) { RfGlyph(if (baseline == null) RfSymbol.SNAPSHOT else RfSymbol.UNION, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)); Text(if (baseline == null) "① 입실 전 저장" else "② $label 저장하고 비교") }
                    TextButton(onClick = { captureOptions = !captureOptions }) { Text(if (captureOptions) "기록 옵션 닫기" else "다른 시점·기준 다시 설정") }
                    if (captureOptions) {
                        Text("기준은 이번 탐색에 한 번이라도 포착된 주소, 비교는 최근 수신과 기준 이후 새 주소의 이력을 저장합니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                        if (baseline != null) OutlinedButton(onClick = { vm.saveSnapshot(area, "입실 전", asBaseline = true) }, enabled = scanning && !busy && area.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("입실 전 기준 다시 저장") }
                    }
                    if (!scanning) Text("공간 이름을 입력한 뒤 아래 ‘탐색 시작’을 누르세요. 저장된 자료는 탐색 없이 비교할 수 있습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = RfColors.warning, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            if (baseline != null) {
            Text("3  추가 장비 확인", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${baseline?.area} · ${baseline?.signals?.size} → ${if (liveTargetAvailable || savedTarget != null) target.signals.size else "—"}개 주소 · ${if (savedTarget != null) "저장 시점 비교" else if (scanning) "실시간 비교" else "중지 시점 비교"}", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            if (comparisonInfo) {
            Text(baseline?.let { "기준: ${it.area} / ${it.label}\n${snapshotTime(it.capturedAtMs)} · ${it.signals.size}개 주소" } ?: "아직 기준을 선택하지 않았습니다.", color = RfColors.primary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(if (savedTarget == null && !liveTargetAvailable) "대상: 탐색을 시작하거나 저장된 비교 시점을 선택하세요." else "대상: ${target.area} / ${target.label}\n${snapshotTime(target.capturedAtMs)} · ${target.signals.size}개 주소${if (savedTarget == null && !scanning) " · 중지 시 마지막 관측" else ""}", style = MaterialTheme.typography.bodyMedium)
            if (savedTarget != null) TextButton(onClick = { vm.comparisonSnapshot.value = null }) { Text("현재 수신과 비교") }
            }
            }
        }
        if (comparison?.isFailure == true) item { Text(comparison.exceptionOrNull()?.message ?: "비교할 수 없는 자료입니다.", color = RfColors.warning) }
        if (result != null) {
            item {
                baseline?.let { discoveryScopeNote(it, target) }?.let { note ->
                    Text(note, color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                val newChannels = channelAnalysis.channels.count { it.isNewChannel == true }
                val addedHidden = channelAnalysis.addedHiddenAPCount ?: 0
                OutlinedButton(onClick = onOpenChannels, modifier = Modifier.fillMaxWidth()) {
                    RfGlyph(RfSymbol.WIFI, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (channelAnalysis.canCompare) "Wi-Fi 새 채널 $newChannels · 추가 숨김 AP $addedHidden ›" else "Wi-Fi 채널 관측 확인 ›")
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("추가" to result.added.size, "미관측" to result.notObserved.size, "공통" to result.common.size).forEach { (name, count) ->
                        FilterChip(selected = group == name, onClick = { group = name }, label = { Text("$name $count") }, leadingIcon = { RfGlyph(when (name) { "전체 이력" -> RfSymbol.UNION; "추가" -> RfSymbol.BLUETOOTH; "미관측" -> RfSymbol.CLOCK; else -> RfSymbol.CHECK }, size = 16.dp) })
                    }
                }
                FilterChip(selected = group == "전체 이력", onClick = { group = "전체 이력" }, label = { Text("전체 이력 ${union.size}") }, leadingIcon = { RfGlyph(RfSymbol.UNION, size = 16.dp) })
                Text(when (group) { "추가" -> "기준에 없던 주소 · 사라져도 기록 유지"; "미관측" -> "이번 자료에 없는 주소"; "공통" -> "두 시점 모두 기록된 주소"; else -> "기준~비교 사이 등장 주소의 합집합 · 중복 제외" }, color = RfColors.primary, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ExplanationToggle("비교 시점", comparisonInfo, { comparisonInfo = !comparisonInfo })
                    ExplanationToggle("결과 의미 보기", resultHelp, { resultHelp = !resultHelp })
                    TextButton(onClick = { analysisOptions = !analysisOptions }) { Text(if (analysisOptions) "검색·필터 닫기" else "검색·필터·정렬") }
                }
                if (resultHelp) {
                Text(when (group) {
                    "추가" -> "추가: 입실 전 기준에 없고 이후 포착된 주소입니다. 아래 카드를 눌러 확인하세요."
                    "미관측" -> "미관측: 기준에는 있었지만 이번 자료에는 없는 주소입니다. 전원이 꺼졌거나 떠났다는 뜻은 아닙니다."
                    "공통" -> "공통: 두 시점에 모두 기록된 주소입니다. +dB는 더 강하게, −dB는 더 약하게 수신됐다는 뜻입니다."
                    else -> "전체 이력: 같은 공간·같은 모드에서 선택한 기준부터 비교 시점까지의 수동·자동 보존 자료와 비교 대상의 합집합입니다. 사라진 주소도 포함합니다. 각 카드는 가장 최근 수신된 탐색의 기록이며 중복된 스냅샷의 횟수를 더하지 않습니다."
                }, color = RfColors.primary, style = MaterialTheme.typography.bodySmall)
                Text("추가 주소는 확인 후보입니다. 일반 기기·임의 주소 변경·일시적 수신 누락도 원인일 수 있어 부정행위로 단정할 수 없습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                }
                if (comparisonInfo && baseline != null) {
                    SignalExportButton(baseline!!, target, snapshots)
                    target.cellEnvironment?.let { CellEnvironmentCard(it, scanning = savedTarget == null && scanning, simulation = simulation) }
                }
            }
            if (analysisOptions) item {
                OutlinedTextField(query, { query = it }, label = { Text("비교 결과 검색 · 이름/제조사/모델/MAC") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("지우기") } })
            }
            if (analysisOptions) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioFilter.entries.forEach { filter -> FilterChip(selected = radio == filter, onClick = { radio = filter }, label = { Text(filter.label) }) }
                    FilterChip(selected = audioOnly, onClick = { audioOnly = !audioOnly }, label = { Text("오디오 후보만") })
                }
                Text("${rows.size}개 표시 / ${groupRows.size}개 $group 주소", style = MaterialTheme.typography.labelSmall, color = RfColors.textDim)
                Box {
                    TextButton(onClick = { sortOpen = true }) { Text("정렬 · ${effectiveSort.label} ▾") }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        SignalSort.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, enabled = option != SignalSort.CHANGE || group == "공통", onClick = { sort = option; sortOpen = false }) }
                    }
                }
            }
            if (rows.isEmpty()) item {
                EmptyState(if (groupRows.isEmpty()) "${group} 주소가 없습니다" else "검색·필터에 맞는 신호가 없습니다",
                    if (groupRows.isEmpty()) "다른 비교 그룹을 선택하거나 다음 시점의 스냅샷을 저장하세요." else "검색어를 지우거나 종류·오디오 필터를 바꿔 보세요. 전체 비교 건수는 그대로 유지됩니다.")
            }
            items(rows, key = { "compare-${it.matchKey}" }) { signal ->
                val change = changes[signal.matchKey]
                val tag = when (group) { "추가" -> "추가 관측"; "미관측" -> "이번에 미관측"; "전체 이력" -> "한 번 이상 관측"; else -> change?.let { "공통 · ${if (it >= 0) "+" else ""}${it.roundToInt()} dB" } ?: "공통" }
                Surface(onClick = { selectedSignal = signal; selectedFromBaseline = group == "미관측"; detailHelp = false; timelineExpanded = false; periodsExpanded = false }, color = RfColors.surface, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("$tag · ${signal.source.label} · ${signal.presence.labelFor(signal.source)}", color = if (group == "추가") RfColors.warning else RfColors.primary, style = MaterialTheme.typography.labelSmall)
                        SignalSummary(signal)
                        Text(if (group == "미관측") "입실 전 기준 기록 · 상세 보기 ›" else "유형·모델은 후보 · 상세 보기 ›", color = RfColors.primary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (!captureExpanded) item {
            TextButton(onClick = { captureExpanded = true }) { Text("새 스냅샷 기록") }
        }
        item {
            ExplanationToggle("이전 기록 다시 보기 · ${available.size}개", archiveExpanded, { archiveExpanded = !archiveExpanded })
        }
        if (archiveExpanded) item {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(archiveQuery, { archiveQuery = it }, label = { Text("저장 자료 검색 · 공간/시점") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                trailingIcon = { if (archiveQuery.isNotEmpty()) TextButton(onClick = { archiveQuery = "" }) { Text("검색 해제") } })
            FilterChip(selected = currentRoomOnly, onClick = { currentRoomOnly = !currentRoomOnly }, label = { Text("이 공간만 · ${area.ifBlank { "공간 이름 필요" }}") })
            Text("${archiveRows.size}개 표시 / 현재 모드 전체 ${available.size}개 · 검색은 선택된 기준·대상을 변경하지 않습니다.", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
        }
        if (archiveExpanded && archiveRows.isEmpty()) item { EmptyState(if (available.isEmpty()) "저장된 스냅샷이 없습니다" else "조건에 맞는 저장 자료가 없습니다", if (available.isEmpty()) "공간 이름을 정하고 입실 전 기준을 저장하세요. 자료는 앱 내부에 보관됩니다." else "공간 필터나 검색어를 바꿔 보세요. 기존 자료는 유지됩니다.") }
        items(if (archiveExpanded) archiveRows else emptyList(), key = { "saved-${it.id}" }) { snapshot ->
            Surface(color = RfColors.surface, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${snapshot.area} / ${snapshot.label}${if (snapshot.simulation) " · 연습" else ""}", fontWeight = FontWeight.SemiBold)
                    Text("${snapshotTime(snapshot.capturedAtMs)} · ${snapshot.signals.size}개 주소", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                    Text("수집 시작 ${snapshotTime(snapshot.scanStartedAtMs)}", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                    Text(snapshot.signals.groupingBy { it.source }.eachCount().entries.joinToString(" · ") { "${it.key.label} ${it.value}" }.ifEmpty { "저장된 주소 없음" }, color = RfColors.primary, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.selectBaseline(snapshot) }, modifier = Modifier.weight(1f)) { Text(if (baseline?.id == snapshot.id) "선택된 기준" else "기준으로 선택") }
                        TextButton(onClick = { vm.comparisonSnapshot.value = snapshot }, modifier = Modifier.weight(1f)) { Text(if (savedTarget?.id == snapshot.id) "선택된 대상" else "대상으로 선택") }
                    }
                }
            }
        }
    }
    }
    selectedSignal?.let { signal -> AlertDialog(onDismissRequest = { selectedSignal = null }, title = { Text(signal.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalSummary(signal)
            Text("${if (savedTarget != null || selectedFromBaseline) "저장 당시" else if (scanning) "현재" else "중지 당시"} · ${signal.presence.labelFor(signal.source)}", color = RfColors.primary)
            signal.source.observationNote().takeIf { it.isNotEmpty() }?.let { Text(it, color = RfColors.warning, style = MaterialTheme.typography.bodySmall) }
            RadioMetadataPanel(signal.radioMetadata)
            if (selectedFromBaseline) Text("입실 전 기준 기록 · 이번에는 미관측", color = RfColors.warning)
            ExplanationToggle("관측 구간·재등장 보기", periodsExpanded, { periodsExpanded = !periodsExpanded })
            if (periodsExpanded) ObservationPeriodList(signal)
            ExplanationToggle("식별 근거·시간 해석 보기", detailHelp, { detailHelp = !detailHelp })
            if (detailHelp) {
            if (signal.source != ScanSource.P2P) Text("최초~마지막 수신 사이의 관측 구간입니다. 계속 송신·연결된 시간은 아니며 중간 공백은 알 수 없습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            Text("${signal.source} · ${signal.address}")
            if (signal.source != ScanSource.P2P) Text("수신 세기 ${signal.receivedRssi?.roundToInt() ?: "—"} dBm · 0에 가까울수록 강합니다. 거리나 위치를 확정하지 않습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            estimateDistance(signal.receivedRssi, signal.referenceRssiAt1m)?.let { estimate ->
                Text("${estimate.label} · ${estimate.rangeLabel}\n${if (estimate.assumedReference) "1m RSSI −59 dBm 가정 · 기기별 보정 없음" else "기기에서 받은 1m RSSI 기준 사용"}\nn=2.7, RSSI ±6 dB·경로손실 n=2~4로 범위를 계산합니다. 미보정 기준은 ±10 dB도 적용합니다. 범위는 모델 가정이며 통계적 신뢰구간이 아닙니다. 벽·인체·송신 출력에 따라 실제 거리는 범위 밖일 수 있습니다. 마지막 수신 기준이며 현재 위치가 아닙니다.", style = MaterialTheme.typography.bodySmall, color = RfColors.textDim)
            }
            Text(signal.manufacturer)
            val audio = signal.audioNameMatch
            val wearable = signal.wearableNameMatch
            if (wearable != null) WearableIdentityPanel(wearable)
            else AudioIdentityPanel(audio, audio?.model ?: signal.modelHint, audioEvidenceNote(audio, signal.manufacturerIds))
            if (audio != null && signal.modelHint != null && audio.model != signal.modelHint) Text("저장 당시 모델 후보: ${signal.modelHint}\n위 후보는 저장된 광고 이름을 현재 규칙으로 해석한 값입니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            if (selectedFromBaseline) Text("이번 시점에는 미관측입니다. 아래 상태와 시간은 입실 전 기준 기록입니다.", color = RfColors.warning)
            if (signal.source != ScanSource.P2P) Text(signal.presence.observationMeaning(savedTarget != null || selectedFromBaseline))
            if (signal.manufacturerIds.isNotEmpty()) Text("광고 회사 코드: " + signal.manufacturerIds.sorted().joinToString { "0x" + it.toString(16).uppercase().padStart(4, '0') })
            }
            HorizontalDivider(color = RfColors.grid)
            ExplanationToggle("시점별 기록 보기", timelineExpanded, { timelineExpanded = !timelineExpanded })
            if (timelineExpanded) {
            Text("저장 시점별 관측", fontWeight = FontWeight.SemiBold)
            signalTimeline(signal, snapshots + target, target).forEach { point ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(point.snapshot.label, style = MaterialTheme.typography.bodyMedium)
                        Text(snapshotTime(point.snapshot.capturedAtMs), color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(point.signal?.let { if (it.source == ScanSource.P2P) "목록 확인 기록" else "${it.receivedRssi?.roundToInt() ?: "—"} dBm\n${it.presence.labelFor(it.source)}" } ?: "미관측", color = if (point.signal == null) RfColors.textDim else RfColors.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("미관측은 해당 시점 자료에 주소가 없다는 뜻입니다. 기기의 전원·위치를 확정하지 않습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { selectedSignal = null }) { Text("닫기") } }) }
}

internal fun snapshotTime(timeMs: Long): String = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
