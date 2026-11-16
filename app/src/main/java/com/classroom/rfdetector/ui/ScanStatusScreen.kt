package com.classroom.rfdetector.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.AppViewModel
import com.classroom.rfdetector.core.*
import com.classroom.rfdetector.scanner.BluetoothSweepPhase

/** Radio reception, retained observations and the next comparison action in one place. */
@Composable
fun ScanStatusScreen(vm: AppViewModel, onCompare: () -> Unit, onDevices: () -> Unit) {
    val scanning by vm.scanning.collectAsState()
    val simulation by vm.simMode.collectAsState()
    val devices by vm.devices.collectAsState()
    val session by vm.scanSession.collectAsState()
    val baseline by vm.baselineSnapshot.collectAsState()
    val checkpoints by vm.checkpoints.collectAsState()
    val checkpointBusy by vm.checkpointBusy.collectAsState()
    val checkpointError by vm.checkpointError.collectAsState()
    val tick by vm.healthTick.collectAsState()
    val expanded by vm.expandedDiscovery.collectAsState()
    val bluetoothPhase by vm.bluetoothPhase.collectAsState()
    val cellEnvironment by vm.cellEnvironmentState.collectAsState()
    val journal by vm.bleJournalStatus.collectAsState()
    val journalExportBusy by vm.bleJournalExportBusy.collectAsState()
    val journalExportMessage by vm.bleJournalExportMessage.collectAsState()
    val journalExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson"), vm::exportBleJournal)
    var limitsOpen by rememberSaveable { mutableStateOf(false) }
    val now = maxOf(tick, System.currentTimeMillis())
    val currentCheckpoint = checkpoints.firstOrNull { it.id == "auto-${session?.id}" }
    val modeCheckpoints = checkpoints.filter { it.simulation == simulation }
    val latestCheckpoint = modeCheckpoints.maxByOrNull { it.capturedAtMs }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            DiscoveryRangePanel(expanded, scanning, simulation, bluetoothPhase, vm::setExpandedDiscovery,
                if (simulation && scanning && expanded) vm::simAddExtendedDevices else null)
        }
        item {
            ScanStatusPanel {
                Text("수신 상태", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("입실 전 저장 → 입실 후 비교 → 추가 신호 확인", color = RfColors.primary, style = MaterialTheme.typography.bodySmall)
                if (simulation) Text("연습 데이터 · 실제 무선 수신이 아닙니다", color = RfColors.warning, fontWeight = FontWeight.SemiBold)
                session?.let {
                    Text("${it.area} · ${if (scanning) "탐색 중" else "탐색 중지"}", style = MaterialTheme.typography.bodyMedium)
                    Text("${snapshotTime(it.startedAtMs)}부터의 관측 이력", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScanCount("한 번 이상 포착", "${devices.size}개", Modifier.weight(1f))
                    ScanCount("최근 수신", "${devices.count { it.source != ScanSource.P2P && it.presence == SignalPresence.RECENT }}개", Modifier.weight(1f))
                }
                Text("주소 기준 · 같은 기기가 여러 주소로 보일 수 있습니다", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                if (devices.any { it.source == ScanSource.P2P }) Text("전체에는 Wi-Fi Direct 목록 주소도 포함됩니다. 최근 수신 수에서는 제외합니다.", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                if (!scanning && devices.isNotEmpty()) Text("최근·대기·이력 구분은 탐색 중지 당시 기록입니다.", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                if (!scanning) Text("아래 ‘탐색 시작’을 누르면 새 신호를 기록합니다.", color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onCompare, modifier = Modifier.fillMaxWidth()) {
                    RfGlyph(if (baseline == null) RfSymbol.SNAPSHOT else RfSymbol.UNION, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(if (baseline == null) "① 입실 전 저장으로 이동" else "② 입실 후·시험 중 비교")
                }
                OutlinedButton(onClick = onDevices, modifier = Modifier.fillMaxWidth()) { Text("포착한 신호 전체 보기") }
            }
        }
        item {
            val health = if (simulation) null else bleReceptionHealth(scanning, bluetoothPhase, vm.ble.isUsable(), vm.ble.active,
                vm.ble.lastReceivedAtMs, vm.ble.lastError, now)
            RadioReceptionCard(RfSymbol.BLUETOOTH, "BLE", health, devices.filter { it.source == ScanSource.BLE }, simulation,
                if (simulation) null else vm.ble.scanModeNotice ?: if (vm.ble.usesExtendedAdvertisements) "기본·확장 BLE 광고 수신 · 기기가 지원하는 PHY 사용" else null)
        }
        item { BleJournalPanel(journal, simulation, journalExportBusy, journalExportMessage) {
            journalExport.launch("rf-ble-advertisements-${System.currentTimeMillis()}.jsonl")
        } }
        if (expanded) item {
            val health = if (simulation) null else classicReceptionHealth(scanning, bluetoothPhase, vm.classic.hasHardware(),
                vm.classic.isUsable(), vm.classic.active, vm.classic.lastReceivedAtMs, vm.classic.lastError)
            RadioReceptionCard(RfSymbol.BLUETOOTH, "일반 Bluetooth", health,
                devices.filter { it.source == ScanSource.CLASSIC }, simulation, "검색 가능한 기기의 발견 응답 · 연결 중인 모든 이어폰을 뜻하지 않습니다")
        }
        item { BluetoothChannelHelp() }
        item {
            val health = if (simulation) null else wifiScanHealth(scanning, vm.wifi.isEnabled(), !vm.locationServicesOff(),
                vm.wifi.lastUpdatedMs, vm.wifi.lastError, now)
            RadioReceptionCard(RfSymbol.WIFI, "Wi-Fi", health, devices.filter { it.source == ScanSource.WIFI }, simulation)
        }
        if (expanded) item {
            WifiDirectReceptionCard(devices.count { it.source == ScanSource.P2P }, simulation, scanning,
                vm.wifiDirect.supported, vm.wifiDirect.active, vm.wifiDirect.lastUpdatedMs, vm.wifiDirect.lastError)
        }
        if (expanded) item { CellEnvironmentCard(cellEnvironment, scanning, simulation) }
        item {
            ScanStatusPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RfGlyph(RfSymbol.CLOCK)
                    Text("자동 보존", fontWeight = FontWeight.SemiBold)
                }
                if (checkpointBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    checkpointError != null -> Text(checkpointError!!, color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
                    currentCheckpoint != null -> {
                        Text("${currentCheckpoint.signals.size}개 주소 보존됨", color = RfColors.primary)
                        Text("최근 저장 ${snapshotTime(currentCheckpoint.capturedAtMs)}", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                    }
                    scanning -> Text("첫 자동 저장 대기 · 5초마다 보존합니다", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                    else -> Text("탐색을 시작하면 수신 이력을 보존합니다", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                }
                if (latestCheckpoint != null && currentCheckpoint == null) Text(
                    "이전 보존 · ${latestCheckpoint.area} · ${snapshotTime(latestCheckpoint.capturedAtMs)} · ${latestCheckpoint.signals.size}개 주소",
                    color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                Text("잠깐 포착된 신호도 저장 대상입니다. 5초마다·정상 중지 시 보존하며 앱을 벗어나면 수신은 멈춥니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            ScanStatusPanel {
                ExplanationToggle("수신 상태·감지 범위 설명", limitsOpen) { limitsOpen = !limitsOpen }
                if (limitsOpen) {
                    Text("최근 수신 / 수신 대기 / 수신 이력은 마지막으로 잡힌 시각을 기준으로 나눕니다. 이력에 남은 주소가 지금도 그 자리에 있다는 뜻은 아닙니다.", style = MaterialTheme.typography.bodySmall)
                    Text("휴대전화 탐색 범위", fontWeight = FontWeight.SemiBold)
                    Text("기본은 BLE 광고와 Wi-Fi 접속점입니다. 범위 확장은 발견 가능한 일반 Bluetooth와 Wi-Fi Direct 시스템 목록을 더합니다. Wi-Fi AP 목록은 주변 모든 휴대전화 목록이 아닙니다.", style = MaterialTheme.typography.bodySmall)
                    Text("기본 BLE는 연속 수신합니다. 확장 시 BLE 45초와 일반 Bluetooth 최대 16초를 번갈아 탐색합니다. Wi-Fi AP는 30초, Direct는 60초 간격으로 요청하며 실제 갱신은 Android에 의해 늦어질 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                    Text("기지국 정보로 시험장 안의 셀룰러 휴대전화를 열거할 수 없습니다. Android의 일반 앱 API는 임의 주파수 전체를 측정하는 수신기를 제공하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    Text("UWB·Wi-Fi Aware는 지원 하드웨어와 상대 기기의 참여·세션 조건이 필요합니다. NFC는 매우 짧은 거리에서 태그 등에 반응하므로 방 전체 장비 탐색용으로 사용하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    Text("페어링 목록을 읽거나 다른 기기에 연결하지 않습니다. 연결 중 광고·발견 응답을 하지 않는 이어폰, 유선 기기와 통신 내용은 이 방식으로 확인할 수 없습니다. 0개는 장비가 없다는 판정이 아닙니다.", style = MaterialTheme.typography.bodySmall)
                    Text("유형·제조사·모델과 거리는 관측 정보에 근거한 후보·추정값입니다. AI 사용이나 소유자를 알아내는 기능은 아닙니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BluetoothChannelHelp() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ScanStatusPanel {
        ExplanationToggle("Bluetooth도 채널로 볼 수 있나요?", expanded) { expanded = !expanded }
        if (expanded) {
            Text("Wi-Fi는 공유기·핫스팟이 알린 채널을 표시합니다. Bluetooth는 연결 중 여러 채널을 바꿔 가며 통신하고, Android는 주변 기기의 채널별 사용 현황을 일반 앱에 제공하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Text("이 앱은 기기가 자신을 알리는 신호와 검색에 응답한 기록을 보여줍니다. 다른 두 기기가 연결되어 통신하더라도 발견용 신호가 없으면 보이지 않을 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            Text("검색에 안 나옴 ≠ Bluetooth를 사용하지 않음", color = RfColors.warning, fontWeight = FontWeight.SemiBold)
            Text("외부 전용 수신 장비는 조건에 따라 통신 신호를 관찰할 수 있지만, 현재 앱에는 연동되어 있지 않습니다. 통신 여부 관측과 내용 해독은 다릅니다.", style = MaterialTheme.typography.bodySmall, color = RfColors.textDim)
        }
    }
}

@Composable
private fun RadioReceptionCard(symbol: RfSymbol, title: String, health: ScanHealth?, devices: List<TrackedDevice>, simulation: Boolean, notice: String? = null) {
    ScanStatusPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RfGlyph(symbol, color = if (health?.warning == true) RfColors.warning else RfColors.primary)
            Text("$title · ${health?.label ?: "가상 신호"}", fontWeight = FontWeight.SemiBold,
                color = if (health?.warning == true) RfColors.warning else RfColors.primary)
        }
        Text("총 ${devices.size}개 주소", style = MaterialTheme.typography.titleMedium)
        Text("최근 ${devices.count { it.presence == SignalPresence.RECENT }} · 대기 ${devices.count { it.presence == SignalPresence.QUIET }} · 이력 ${devices.count { it.presence == SignalPresence.HISTORY }}",
            color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        val lastObserved = if (simulation) devices.maxOfOrNull { it.lastSeenMs } else health?.lastObservedAtMs
        Text("${if (simulation) "가상 관측 시각" else if (title == "Wi-Fi") "마지막 결과 갱신" else "마지막 신호 수신"} · ${lastObserved?.let(::snapshotTime) ?: "아직 없음"}",
            color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
        health?.let { Text(it.detail, color = RfColors.textDim, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it, color = RfColors.textDim, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveryRangePanel(
    expanded: Boolean,
    scanning: Boolean,
    simulation: Boolean,
    phase: BluetoothSweepPhase,
    onExpandedChange: (Boolean) -> Unit,
    onSimAdd: (() -> Unit)? = null,
) {
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    ScanStatusPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RfGlyph(RfSymbol.UNION)
            Text("탐색 범위", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !expanded, onClick = { onExpandedChange(false) },
                label = { Text("BLE 우선") }, leadingIcon = { RfGlyph(RfSymbol.BLUETOOTH) }, modifier = Modifier.testTag("discovery-basic"))
            FilterChip(selected = expanded, onClick = { onExpandedChange(true) },
                label = { Text("범위 확장") }, leadingIcon = { RfGlyph(RfSymbol.UNION) }, modifier = Modifier.testTag("discovery-expanded"))
        }
        Text(if (expanded) "일반 Bluetooth + Wi-Fi Direct 추가 · 일반 Bluetooth 검색 중에는 BLE가 잠시 쉽니다."
            else "BLE를 연속 수신하고 Wi-Fi AP를 주기적으로 확인합니다.", style = MaterialTheme.typography.bodySmall)
        Text("비콘·짧은 광고를 우선 관측할 때는 BLE 우선을 유지하세요.", color = RfColors.primary, style = MaterialTheme.typography.labelSmall)
        if (expanded) Text("기지국 환경도 별도 참고 · 새 장비 수에는 포함하지 않습니다", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
        if (expanded && scanning && !simulation) Text(when (phase) {
            BluetoothSweepPhase.CLASSIC -> "지금: 일반 Bluetooth 탐색 · BLE 교대 대기"
            BluetoothSweepPhase.BLE -> "지금: BLE 수신 · 다음은 일반 Bluetooth"
            BluetoothSweepPhase.WAITING -> "Bluetooth 검색 종료 확인 중 · BLE 대기"
            BluetoothSweepPhase.RETRYING -> "BLE 오류 후 재시도 대기 · 수신 공백 있음"
            BluetoothSweepPhase.STOPPED -> "Bluetooth 탐색 준비 중"
        }, color = RfColors.primary, style = MaterialTheme.typography.labelMedium)
        ExplanationToggle("탐색 방식 자세히", detailsOpen) { detailsOpen = !detailsOpen }
        if (detailsOpen) {
            Text("범위 확장: BLE 45초 → 일반 Bluetooth 최대 16초 → 반복. Direct 목록은 60초마다 요청합니다. BLE가 쉬는 동안 짧은 광고를 놓칠 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            Text("일반 Bluetooth는 발견 가능 상태의 기기만 응답합니다. 검색이 기존 Bluetooth 연결 성능에 영향을 줄 수 있어 BLE와 번갈아 실행합니다.", style = MaterialTheme.typography.bodySmall)
            Text("Wi-Fi Direct는 운영체제의 목록 확인이며 캐시일 수 있습니다. 직접 RF 패킷을 받은 증거가 아닙니다. 기기의 지원 여부와 권한에 따라 결과가 다릅니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            Text("기지국 환경은 이 휴대전화의 모뎀이 제공한 셀 정보입니다. 주변 휴대전화 목록을 제공하지 않으며 캐시는 원래 모뎀 관측 시각을 유지합니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        }
        onSimAdd?.let { action ->
            OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().testTag("add-extended-simulation")) {
                Text("가상 일반 Bluetooth·Direct 추가")
            }
        }
    }
}

internal fun bleReceptionHealth(scanning: Boolean, phase: BluetoothSweepPhase, enabled: Boolean, active: Boolean,
    lastReceivedAtMs: Long?, lastError: String?, nowMs: Long): ScanHealth =
    if (scanning && phase == BluetoothSweepPhase.CLASSIC) ScanHealth("교대 대기",
        "일반 Bluetooth 검색 중 · 끝나면 BLE 수신을 다시 시작합니다", false, lastReceivedAtMs)
    else if (scanning && phase == BluetoothSweepPhase.WAITING) ScanHealth("종료 확인 대기",
        "Bluetooth 검색 종료 확인 중 · BLE 대기", false, lastReceivedAtMs)
    else if (scanning && phase == BluetoothSweepPhase.RETRYING) ScanHealth("재시도 대기",
        lastError ?: "BLE 탐색 오류 후 잠시 기다린 뒤 다시 요청합니다 · 대기 중에는 수신하지 않습니다", true, lastReceivedAtMs)
    else bleScanHealth(scanning, enabled, active, lastReceivedAtMs, lastError, nowMs)

private fun classicReceptionHealth(scanning: Boolean, phase: BluetoothSweepPhase, hardware: Boolean, enabled: Boolean,
    active: Boolean, lastReceivedAtMs: Long?, lastError: String?): ScanHealth = when {
    !scanning -> ScanHealth("탐색 중지", "남아 있는 주소는 이전 발견 기록입니다", false, lastReceivedAtMs)
    !hardware -> ScanHealth("지원 확인", "이 휴대전화에서 Bluetooth 하드웨어를 확인하지 못했습니다", true, lastReceivedAtMs)
    !enabled -> ScanHealth("Bluetooth 확인", "Bluetooth와 주변 기기 권한을 확인하세요", true, lastReceivedAtMs)
    phase == BluetoothSweepPhase.WAITING -> ScanHealth("검색 종료 확인 중", "검색 종료를 확인한 뒤 BLE 수신을 재개합니다", false, lastReceivedAtMs)
    phase != BluetoothSweepPhase.CLASSIC -> ScanHealth("교대 대기", lastError?.let { "이전 회차: $it" }
        ?: "BLE 수신 뒤 일반 Bluetooth 탐색을 진행합니다", lastError != null, lastReceivedAtMs)
    lastError != null -> ScanHealth("탐색 확인", lastError, true, lastReceivedAtMs)
    !active -> ScanHealth("시작 대기", "일반 Bluetooth 탐색을 준비합니다", false, lastReceivedAtMs)
    lastReceivedAtMs == null -> ScanHealth("발견 응답 대기", "검색 가능한 기기의 응답을 기다립니다 · 0개는 부재 판정이 아닙니다", false)
    else -> ScanHealth("발견 확인", "회차마다 같은 주소는 한 번 기록합니다 · 시각은 발견 방송을 받은 때입니다", false, lastReceivedAtMs)
}

@Composable
internal fun WifiDirectReceptionCard(addressCount: Int, simulation: Boolean, scanning: Boolean, supported: Boolean,
    active: Boolean, lastUpdatedMs: Long?, lastError: String?) {
    val state = when {
        simulation -> "가상 목록"
        !scanning -> "탐색 중지"
        !supported -> "이 기기에서 미지원"
        lastError != null -> "목록 확인 대기"
        !active -> "탐색 중단"
        lastUpdatedMs == null -> "첫 목록 대기"
        else -> "목록 확인"
    }
    ScanStatusPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RfGlyph(RfSymbol.WIFI)
            Text("Wi-Fi Direct · $state", fontWeight = FontWeight.SemiBold, color = RfColors.primary)
        }
        Text(if (!simulation && lastUpdatedMs == null && addressCount == 0) "아직 확인된 주소 없음"
            else "처음 목록 확인 · ${addressCount}개 주소", style = MaterialTheme.typography.titleMedium)
        Text("시스템 목록 · 캐시 가능 · 직접 RF 수신 증거 아님", color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
        if (!simulation) Text("마지막 목록 확인 · ${lastUpdatedMs?.let(::snapshotTime) ?: "아직 없음"}",
            color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
        if (!simulation && lastError != null) Text(lastError, color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
        Text("이 탐색에서 주소별 첫 목록 확인만 기록합니다. 반복·사라짐·재등장으로 수신 횟수를 늘리지 않습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        Text("재등장 추정 없음 · RSSI 없음 · 거리 추정 없음", color = RfColors.textDim, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun CellEnvironmentCard(snapshot: CellEnvironmentSnapshot?, scanning: Boolean, simulation: Boolean) {
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val readings = snapshot?.readings.orEmpty()
    ScanStatusPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RfGlyph(RfSymbol.INFO)
            Text("기지국 환경 참고", fontWeight = FontWeight.SemiBold, color = RfColors.primary)
        }
        Text("기지국 정보 ≠ 주변 휴대전화 목록 · 새 장비 집계 제외", color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
        if (simulation) Text(if (snapshot == null) "연습 모드 · 실제 기지국 환경은 조회하지 않습니다" else "연습 기지국 예시 · 실제 무선 수신 아님",
            color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
        when {
            snapshot == null -> if (!simulation) Text(if (scanning) "첫 환경 정보 대기" else "탐색을 시작하면 지원되는 기지국 정보를 확인합니다", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            else -> {
                Text("${if (simulation) "가상 환경 · " else if (scanning) "" else "탐색 중지 · "}${snapshot.status.label}", fontWeight = FontWeight.SemiBold)
                Text("환경 기록 시각 · ${snapshotTime(snapshot.capturedAtMs)}", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                snapshot.error?.let { Text(it, color = RfColors.warning, style = MaterialTheme.typography.bodySmall) }
                if (readings.isNotEmpty()) {
                    Text("기지국 항목 ${readings.size}개", style = MaterialTheme.typography.titleSmall)
                    val shown = if (detailsOpen) readings else readings.take(3)
                    shown.forEach { reading ->
                        Surface(color = RfColors.bg, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${reading.technology} · ${if (reading.registered) "등록 셀" else "주변 셀"}", fontWeight = FontWeight.SemiBold)
                                Text("채널 ${reading.channel?.toString() ?: "미확인"} · ${reading.dbm?.let { "$it dBm" } ?: "수신 세기 미확인"}", style = MaterialTheme.typography.bodySmall)
                                Text("모뎀 관측 · ${snapshotTime(reading.observedAtMs)}", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                                if (reading.cached) Text("캐시 · 이전 모뎀 관측값", color = RfColors.warning, style = MaterialTheme.typography.labelSmall)
                                if (detailsOpen) Text("셀 식별정보 · ${reading.identity}", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (!detailsOpen && readings.size > 3) Text("외 ${readings.size - 3}개 · 아래에서 펼쳐보기", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        ExplanationToggle("기지국 정보·해석 보기", detailsOpen) { detailsOpen = !detailsOpen }
        if (detailsOpen) {
            snapshot?.note?.let { Text(it, color = RfColors.textDim, style = MaterialTheme.typography.bodySmall) }
            Text("등록 셀은 탐색용 휴대전화가 등록한 이동통신 셀입니다. 항목 수는 개별 휴대전화 수나 물리적 기지국 건물 수가 아닙니다.", style = MaterialTheme.typography.bodySmall)
            Text("모뎀 관측 시각과 앱의 환경 기록 시각은 다릅니다. Android가 이전 값을 반환할 수 있으며 캐시라는 이유만으로 갱신 제한 원인을 확정하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Text("채널은 이동통신 채널 번호이며 MHz 값이 아닙니다. 기술마다 수신 세기 기준이 달라 단순 비교나 사람까지의 거리 추정에 사용하지 않습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            Text("이 정보는 환경 참고로만 보관하며 추가 장비·합집합·경보에 포함하지 않습니다. 정보 없음은 주변 휴대전화가 없다는 뜻이 아닙니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ScanCount(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
        Text(value, color = RfColors.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun ScanStatusPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = RfColors.surface, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
