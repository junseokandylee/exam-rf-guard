package com.classroom.rfdetector.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.AppViewModel
import com.classroom.rfdetector.core.ScanSource
import com.classroom.rfdetector.core.CellEnvironmentReading
import com.classroom.rfdetector.core.CellEnvironmentSnapshot
import com.classroom.rfdetector.core.CellEnvironmentStatus
import com.classroom.rfdetector.core.SnapshotStore
import com.classroom.rfdetector.core.DeviceTracker
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.compareSnapshots
import com.classroom.rfdetector.scanner.BluetoothSweepPhase
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtendedDiscoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectingExpandedDiscoveryAddsDistinctClassicAndDirectRecordsAndKeepsThemWhenDisabled() {
        val vm = AppViewModel(compose.activity.applicationContext)
        try {
            compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { ScanStatusScreen(vm, {}, {}) } } }
            compose.waitUntil(10_000) { !vm.snapshotBusy.value }
            compose.runOnIdle { vm.setSim(true); vm.startScan() }
            compose.waitUntil(10_000) { vm.devices.value.size == 5 }
            compose.onNodeWithTag("discovery-basic").assertIsSelected()
            compose.onNodeWithTag("discovery-expanded").performClick().assertIsSelected()
            assertTrue(vm.expandedDiscovery.value)
            compose.onNodeWithTag("add-extended-simulation").performClick()
            advanceSimulation()
            compose.waitUntil(10_000) {
                vm.devices.value.count { it.source == ScanSource.CLASSIC } == 1 &&
                    vm.devices.value.count { it.source == ScanSource.P2P } == 1
            }
            saveScreen("rf-expanded-discovery")
            compose.onNodeWithTag("add-extended-simulation").performClick()
            advanceSimulation()
            assertEquals(1, vm.devices.value.count { it.source == ScanSource.P2P })
            assertEquals(1, vm.devices.value.single { it.source == ScanSource.P2P }.observationCount)
            assertNull(vm.devices.value.single { it.source == ScanSource.P2P }.rssiEma)
            assertNull(vm.devices.value.single { it.source == ScanSource.P2P }.distanceM)
            scrollToText("일반 Bluetooth · 가상 신호")
            compose.onNodeWithText("일반 Bluetooth · 가상 신호").assertIsDisplayed()
            scrollToText("Wi-Fi Direct · 가상 목록")
            compose.onNodeWithText("처음 목록 확인 · 1개 주소").assertExists()
            compose.onNodeWithText("시스템 목록 · 캐시 가능 · 직접 RF 수신 증거 아님").assertExists()
            compose.onNodeWithText("재등장 추정 없음 · RSSI 없음 · 거리 추정 없음").assertExists()
            scrollToText("기지국 환경 참고")
            compose.onNodeWithText("연습 기지국 예시 · 실제 무선 수신 아님").assertExists()
            compose.onNodeWithText("기지국 항목 1개").assertExists()
            assertEquals("A cell environment entry is not another device", 7, vm.devices.value.size)
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("discovery-basic"))
            compose.onNodeWithTag("discovery-basic").performClick().assertIsSelected()
            assertFalse(vm.expandedDiscovery.value)
            assertEquals(1, vm.devices.value.count { it.source == ScanSource.P2P })
            assertEquals(1, vm.devices.value.count { it.source == ScanSource.CLASSIC })
        } finally { vm.shutdown() }
    }

    @Test fun classicPhaseMeansScheduledBlePauseNotReceptionFailure() {
        val paused = bleReceptionHealth(true, BluetoothSweepPhase.CLASSIC, true, false,
            1000, "A previous attempt failed", 2000)
        assertEquals("교대 대기", paused.label)
        assertFalse(paused.warning)
        assertEquals(1000L, paused.lastObservedAtMs)
        val unexpectedlyInactive = bleReceptionHealth(true, BluetoothSweepPhase.BLE, true, false, 1000, null, 2000)
        assertEquals("수신 중단", unexpectedlyInactive.label)
        assertTrue(unexpectedlyInactive.warning)
        assertEquals("탐색 중지", bleReceptionHealth(false, BluetoothSweepPhase.CLASSIC, true, false, 1000, null, 2000).label)
        val waitingForStop = bleReceptionHealth(true, BluetoothSweepPhase.WAITING, true, false, 1000, null, 2000)
        assertEquals("종료 확인 대기", waitingForStop.label)
        assertFalse(waitingForStop.warning)
    }

    @Test fun directCardLabelsListEvidenceAndExpandedRangeExplainsTheBleGap() {
        compose.setContent {
            AppRfTheme {
                Surface(color = RfColors.bg) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("연습 데이터 · 실제 무선 수신 아님", color = RfColors.warning)
                        DiscoveryRangePanel(true, true, true, BluetoothSweepPhase.STOPPED, {})
                        WifiDirectReceptionCard(1, true, true, true, true, null, null)
                    }
                }
            }
        }
        compose.onNodeWithText("Wi-Fi Direct · 가상 목록").assertIsDisplayed()
        compose.onNodeWithText("재등장 추정 없음 · RSSI 없음 · 거리 추정 없음").assertIsDisplayed()
        saveScreen("rf-direct-list-evidence")
        compose.onNodeWithText("탐색 방식 자세히 ▾").performClick()
        compose.onNodeWithText("BLE가 쉬는 동안 짧은 광고를 놓칠 수 있습니다.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("직접 RF 패킷을 받은 증거가 아닙니다.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun largeTextRangeSelectionAndUnavailabilityRemainReachable() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppRfTheme {
                    Surface(color = RfColors.bg) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DiscoveryRangePanel(expanded.value, true, false, BluetoothSweepPhase.CLASSIC, { expanded.value = it })
                            if (expanded.value) WifiDirectReceptionCard(0, false, true, false, false, null, "Wi-Fi Direct 탐색을 지원하지 않는 기기입니다")
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("discovery-expanded").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("지금: 일반 Bluetooth 탐색 · BLE 교대 대기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi Direct · 이 기기에서 미지원").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("아직 확인된 주소 없음").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("마지막 목록 확인 · 아직 없음").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("탐색 방식 자세히 ▾").performScrollTo().performClick()
        compose.onNodeWithText("BLE가 쉬는 동안 짧은 광고를 놓칠 수 있습니다.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("discovery-basic").performScrollTo().performClick().assertIsSelected()
    }

    @Test fun cellEnvironmentPreservesOldModemTimesAndSeparatesCellsFromDevices() {
        val now = Instant.parse("2026-11-16T00:03:00Z").toEpochMilli()
        val environment = CellEnvironmentSnapshot(now, listOf(
            CellEnvironmentReading("LTE", "MCC 450 / MNC 08 / TAC 100 / CI 1234", 1800, -89, true, now - 120_000, true),
            CellEnvironmentReading("NR", "MCC 450 / MNC 08 / TAC ? / NCI ?", null, null, false, now, false),
        ), CellEnvironmentStatus.CACHED, "이전 모뎀 관측 포함 · Android 갱신 제한 가능")
        compose.setContent {
            AppRfTheme { Surface(color = RfColors.bg) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("합성 예시 · 실제 수신 아님", Modifier.fillMaxWidth(), color = RfColors.warning)
                    CellEnvironmentCard(environment, true, false)
                }
            } }
        }
        compose.onNodeWithText("합성 예시 · 실제 수신 아님").assertIsDisplayed()
        compose.onNodeWithText("기지국 정보 ≠ 주변 휴대전화 목록 · 새 장비 집계 제외").assertIsDisplayed()
        compose.onNodeWithText("기지국 항목 2개").assertIsDisplayed()
        compose.onNodeWithText("LTE · 등록 셀").assertIsDisplayed()
        compose.onNodeWithText("채널 1800 · -89 dBm").assertIsDisplayed()
        compose.onNodeWithText("모뎀 관측 · ${snapshotTime(now - 120_000)}").assertIsDisplayed()
        compose.onNodeWithText("환경 기록 시각 · ${snapshotTime(now)}").assertIsDisplayed()
        compose.onNodeWithText("캐시 · 이전 모뎀 관측값").assertIsDisplayed()
        compose.onNodeWithText("채널 미확인 · 수신 세기 미확인").assertIsDisplayed()
        saveScreen("rf-cell-environment-reference")
        compose.onNodeWithText("기지국 정보·해석 보기 ▾").performScrollTo().performClick()
        compose.onNodeWithText("셀 식별정보 · MCC 450 / MNC 08 / TAC 100 / CI 1234").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("항목 수는 개별 휴대전화 수나 물리적 기지국 건물 수가 아닙니다.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("추가 장비·합집합·경보에 포함하지 않습니다.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun missingSimAndPermissionAreEnvironmentStatesNotAZeroPhoneVerdict() {
        val snapshot = mutableStateOf(CellEnvironmentSnapshot(System.currentTimeMillis(), emptyList(), CellEnvironmentStatus.NO_SIM))
        compose.setContent {
            AppRfTheme { Surface(color = RfColors.bg) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    CellEnvironmentCard(snapshot.value, true, false)
                }
            } }
        }
        compose.onNodeWithText("SIM 없음").assertIsDisplayed()
        compose.onNodeWithText("기지국 항목 0개").assertDoesNotExist()
        compose.runOnIdle {
            snapshot.value = snapshot.value.copy(status = CellEnvironmentStatus.PERMISSION_DENIED,
                error = "기지국 환경에는 정확한 위치 권한이 필요합니다")
        }
        compose.onNodeWithText("위치 권한 필요").assertIsDisplayed()
        compose.onNodeWithText("기지국 환경에는 정확한 위치 권한이 필요합니다").assertIsDisplayed()
        compose.onNodeWithText("기지국 정보·해석 보기 ▾").performClick()
        compose.onNodeWithText("정보 없음은 주변 휴대전화가 없다는 뜻이 아닙니다.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun basicBaselineThenExpandedTargetPersistsScopeAndEnvironmentWithoutAddingACellDevice() {
        val vm = AppViewModel(compose.activity.applicationContext)
        val showComparison = mutableStateOf(false)
        try {
            compose.setContent { AppRfTheme { Surface(color = RfColors.bg) {
                if (showComparison.value) SnapshotScreen(vm)
                else ScanStatusScreen(vm, { showComparison.value = true }, {})
            } } }
            compose.waitUntil(10_000) { !vm.snapshotBusy.value }
            compose.runOnIdle { vm.setSim(true); vm.startScan() }
            compose.waitUntil(10_000) { vm.devices.value.size == 5 }
            compose.runOnIdle { vm.saveSnapshot("시험실", "기본 범위 기준", asBaseline = true) }
            compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); !vm.snapshotBusy.value }
            val baselineId = vm.baselineSnapshot.value!!.id
            assertEquals(setOf(ScanSource.BLE, ScanSource.WIFI), vm.baselineSnapshot.value!!.discoverySources)
            compose.onNodeWithTag("discovery-expanded").performClick()
            compose.onNodeWithTag("add-extended-simulation").performClick()
            advanceSimulation()
            compose.waitUntil(10_000) { vm.devices.value.size == 7 && vm.cellEnvironmentState.value != null }
            compose.runOnIdle { vm.saveSnapshot("시험실", "확장 범위 비교") }
            compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); !vm.snapshotBusy.value }
            assertNull(vm.snapshotError.value)
            val targetId = vm.comparisonSnapshot.value!!.id
            val reopened = SnapshotStore(compose.activity.filesDir).load()
            val before = reopened.single { it.id == baselineId }
            val after = reopened.single { it.id == targetId }
            assertEquals(setOf(ScanSource.BLE, ScanSource.WIFI), before.discoverySources)
            assertEquals(setOf(ScanSource.BLE, ScanSource.WIFI, ScanSource.CLASSIC, ScanSource.P2P), after.discoverySources)
            assertNull(before.cellEnvironment)
            assertEquals("LTE", after.cellEnvironment!!.readings.single().technology)
            assertEquals(1650, after.cellEnvironment!!.readings.single().channel)
            assertEquals(5, before.signals.size)
            assertEquals(7, after.signals.size)
            val comparison = compareSnapshots(before, after)
            assertEquals(setOf(ScanSource.CLASSIC, ScanSource.P2P), comparison.added.map { it.source }.toSet())
            assertEquals(2, comparison.added.size)
            scrollToText("② 입실 후·시험 중 비교")
            compose.onNodeWithText("② 입실 후·시험 중 비교").performClick()
            scrollToText("탐색 범위 확대", substring = true)
            compose.onNodeWithText("추가 주소가 입실 후 생겼다는 뜻은 아닙니다.", substring = true).assertIsDisplayed()
        } finally { vm.shutdown() }
    }

    @Test fun directDetailsNeverRenderPacketChartsOrDistanceEvenForAnOldRecordWithRssi() {
        val vm = AppViewModel(compose.activity.applicationContext)
        val tracker = DeviceTracker()
        tracker.update(RfObservation("AA:BB:CC:DD:EE:FF", ScanSource.P2P, "Direct 목록", -50, null, false, System.currentTimeMillis()))
        val records = tracker.snapshot(System.currentTimeMillis(), true)
        try {
            compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { DeviceListScreen(vm, records, false, 0) } } }
            compose.onNodeWithText("Direct 목록").performScrollTo().performClick()
            compose.onNodeWithText("목록 첫 확인 · ${snapshotTime(records.single().firstSeenMs)}").assertExists()
            compose.onNodeWithText("RSSI·거리·방향 정보 없음 · 재등장·지속 시간은 추정하지 않습니다").assertExists()
            compose.onNodeWithText("dBm", substring = true).assertDoesNotExist()
            compose.onNodeWithText("최근 10초 신호 이력", substring = true).assertDoesNotExist()
            compose.onNodeWithContentDescription("최근 신호 세기", substring = true).assertDoesNotExist()
            compose.onNodeWithText("방향 미확정", substring = true).assertDoesNotExist()
            compose.onNodeWithText("총 1회", substring = true).assertDoesNotExist()
            compose.onNodeWithText("닫기").performClick()
        } finally { vm.shutdown() }
    }

    private fun advanceSimulation() = compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)) }

    private fun scrollToText(text: String, substring: Boolean = false) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring))
        compose.onNodeWithText(text, substring = substring).performScrollTo()
    }

    private fun saveScreen(name: String) {
        val target = File("build/outputs/ui-captures/$name.png")
        target.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().lastOrNull() ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
