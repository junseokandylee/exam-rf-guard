package com.classroom.rfdetector

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

import com.classroom.rfdetector.ui.*
import com.classroom.rfdetector.core.SignalPresence

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 17, 24)
        window.navigationBarColor = android.graphics.Color.rgb(10, 17, 24)
        setContent { AppRfTheme { App() } }
    }
}

@Composable
fun App() {
    SignalExportHost { AppContent() }
}

@Composable
private fun AppContent() {
    val context = LocalContext.current
    val compact = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.2f
    val vm = remember(context) { ViewModelProvider(context as ComponentActivity, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext) as T
    })[AppViewModel::class.java] }
    val lifecycle = (context as ComponentActivity).lifecycle
    var tab by rememberSaveable { mutableIntStateOf(2) }
    val screenStates = rememberSaveableStateHolder()
    var help by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    val needed = remember { vm.requiredPermissions() }
    fun hasPermissions() = needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var allGranted by remember { mutableStateOf(hasPermissions()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allGranted = hasPermissions()
        if (pendingStart && allGranted) vm.startScan()
        pendingStart = false
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allGranted = hasPermissions()
            if (event == Lifecycle.Event.ON_STOP) vm.stopScan()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val devices by vm.devices.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val simMode by vm.simMode.collectAsState()
    val expandedDiscovery by vm.expandedDiscovery.collectAsState()
    val geigerOn by vm.geigerOn.collectAsState()
    val baselineSet by vm.baselineSet.collectAsState()
    val newCount by vm.newCount.collectAsState()
    val status by vm.statusMsg.collectAsState()
    val heading by vm.headingDeg.collectAsState()
    val latestNewSignal by vm.latestNewSignal.collectAsState()
    val latestNewWifiChannel by vm.latestNewWifiChannel.collectAsState()
    DisposableEffect(scanning) {
        val window = (context as? Activity)?.window
        if (scanning) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    Surface(Modifier.fillMaxSize(), color = RfColors.bg) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (compact) "RF SWEEPER · ${if (scanning) "탐색 중" else "대기"}" else "RF SWEEPER  /  FIELD TOOLS", style = MaterialTheme.typography.labelSmall, color = RfColors.primary)
                    Text(if (compact) "신호 비교" else "시험장 신호 비교", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                if (!compact) Surface(shape = CircleShape, color = RfColors.surfaceAlt) {
                    Text(if (scanning) "● 탐색 중" else "○ 대기", Modifier.padding(10.dp), style = MaterialTheme.typography.labelMedium, color = if (scanning) RfColors.primary else RfColors.textDim)
                }
                TextButton(onClick = { help = true }) { Text("안내") }
            }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !simMode, onClick = { vm.setSim(false) }, label = { Text("실제 탐색") })
                FilterChip(selected = simMode, onClick = { vm.setSim(true) }, label = { Text("연습 모드") })
                Spacer(Modifier.weight(1f))
                if (!compact) Text(when {
                    simMode && expandedDiscovery -> "가상 · 확장"
                    simMode -> "가상 데이터"
                    expandedDiscovery -> "범위 확장"
                    else -> "BLE + Wi-Fi"
                }, color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
            }
            val tabOrder = listOf(2, 1, 4, 0, 3)
            TabRow(selectedTabIndex = tabOrder.indexOf(tab), containerColor = Color.Transparent, divider = {}) {
                tabOrder.forEach { index ->
                    val title = listOf("레이더", "기기 ${devices.size}", "비교", "Wi-Fi 채널", "수신 상태")[index]
                    val label = if (compact) listOf("탐색", "기기", "비교", "채널", "수신")[index] else listOf("레이더", "기기 ${devices.size}", "비교", "채널", "수신")[index]
                    Tab(selected = tab == index, onClick = { tab = index }, modifier = Modifier.semantics { contentDescription = title }, selectedContentColor = RfColors.primary, unselectedContentColor = RfColors.textDim) {
                        Text(label, Modifier.padding(horizontal = 2.dp, vertical = 14.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            if (latestNewWifiChannel != null) {
                Surface(color = RfColors.surfaceAlt) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("기준에 없던 채널 · $latestNewWifiChannel", Modifier.weight(1f), color = RfColors.warning, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        TextButton(onClick = { tab = 3; vm.comparisonSnapshot.value = null; vm.latestNewWifiChannel.value = null }) { Text("채널 확인") }
                    }
                }
            } else latestNewSignal?.let { name ->
                Surface(color = RfColors.surfaceAlt) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), color = RfColors.warning, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        TextButton(onClick = { tab = 2; vm.comparisonSnapshot.value = null; vm.latestNewSignal.value = null }) { Text("추가 확인") }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                screenStates.SaveableStateProvider(tab) { when (tab) {
                    0 -> RadarScreen(vm, devices.filter { it.presence == SignalPresence.RECENT && it.source != com.classroom.rfdetector.core.ScanSource.P2P }, heading)
                    1 -> DeviceListScreen(vm, devices, baselineSet, newCount)
                    2 -> SnapshotScreen(vm, onOpenChannels = { tab = 3 })
                    3 -> ChannelScreen(vm, onOpenComparison = { tab = 2 })
                    else -> ScanStatusScreen(vm, onCompare = { tab = 2 }, onDevices = { tab = 1 })
                } }
            }
            Surface(color = RfColors.surface) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!simMode && !allGranted) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (compact) "탐색 권한 필요" else "주변 기기·정확한 위치 권한 필요", Modifier.weight(1f), color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }) { Text("권한 설정") }
                    }
                    else status?.let { Text(it, color = RfColors.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            when {
                                scanning -> vm.stopScan()
                                simMode || hasPermissions() -> vm.startScan()
                                else -> { pendingStart = true; launcher.launch((needed + "android.permission.ACTIVITY_RECOGNITION").toTypedArray()) }
                            }
                        }, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (scanning) RfColors.surfaceAlt else RfColors.primary, contentColor = if (scanning) RfColors.textPrimary else RfColors.bg)) {
                            Text(if (scanning) "■  탐색 중지" else "◎  탐색 시작", fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(checked = geigerOn, onCheckedChange = { vm.setGeiger(it) }, enabled = scanning)
                            Text("근접 진동", style = MaterialTheme.typography.labelSmall, color = RfColors.textDim)
                        }
                    }
                }
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("탐색 가이드") }, text = {
        Text("1. 입실 전에 탐색을 시작하고 BLE·Wi-Fi 결과를 기다린 뒤 비교 탭에서 공간 이름과 입실 전 기준 스냅샷을 저장하세요.\n\n2. 입실 후·시험 중 비교 스냅샷을 저장하면 추가·미관측·공통 주소와 신호 세기 변화를 볼 수 있습니다. 저장 자료는 앱을 다시 열어도 남으며 목록에서 기준·대상을 다시 선택할 수 있습니다.\n\n3. 추가 주소는 확인 후보이며 정상 기기·임의 MAC 변경·일시적 누락도 원인일 수 있습니다. 한 번 포착된 신규 신호는 세션 이력에 남고 저장된 스냅샷은 이후 수신으로 바뀌지 않습니다.\n\n4. 천천히 이동하며 신호 변화를 비교하세요. 근접 진동을 켜면 최근 수신한 강한 신호를 진동으로 확인합니다. 보행 기반 방향 추정에는 신체 활동 권한과 걸음 센서가 필요합니다.\n\n거리·방향은 신호 기반 추정값이며 벽·반사·송신 출력에 따라 달라집니다. 신규·숨김 신호는 부정행위의 증거가 아닙니다. Wi-Fi 목록은 AP 목록이며 모든 휴대폰을 나타내지 않습니다. 광고를 보내지 않는 이어폰은 감지되지 않을 수 있습니다. 앱을 벗어나면 탐색이 중지됩니다.", Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { help = false }) { Text("확인") } }, dismissButton = { TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }) { Text("앱 권한 설정") } })
}


