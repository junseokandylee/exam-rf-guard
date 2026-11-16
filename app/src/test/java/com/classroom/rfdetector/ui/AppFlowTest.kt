package com.classroom.rfdetector.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.classroom.rfdetector.App
import com.classroom.rfdetector.AppViewModel
import com.classroom.rfdetector.core.*
import java.io.File
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun examScenarioRetainsVanishedEarbudShowsReturnAndUnion() {
        val vm = AppViewModel(compose.activity.applicationContext)
        try {
            compose.setContent { AppRfTheme { androidx.compose.material3.Surface(color = RfColors.bg) { SnapshotScreen(vm) } } }
            compose.waitUntil(10_000) { !vm.snapshotBusy.value }
            val tracker = DeviceTracker()
            val start = System.currentTimeMillis() - 180_000
            fun observe(address: String, at: Long, source: ScanSource) = tracker.update(
                RfObservation(address, source, if (source == ScanSource.BLE) "AirPods Pro" else "시험장 AP", -68, null, false, start + at, if (source == ScanSource.BLE) setOf(76) else emptySet()))
            fun capture(id: String, at: Long, baselineTime: Long? = null) = SignalSnapshot.capture(id, "제1시험실", id, start + at, start, false,
                tracker.snapshot(start + at, true).map { it.copy(identity = DeviceIdentityResolver(emptyMap(), mapOf(76 to "Apple, Inc.")).resolve(it.key, it.displayName, setOf(it.source), it.manufacturerIds)) }, baselineTime)
            observe("room-ap", 0, ScanSource.WIFI)
            val before = capture("입실 전", 0)
            tracker.setBaseline(before.signals.map { it.matchKey }.toSet())
            observe("brief-earbud", 1000, ScanSource.BLE)
            observe("brief-earbud", 2000, ScanSource.BLE)
            val vanished = capture("입실 후 · 미수신", 120000, start)
            compose.runOnIdle {
                vm.baselineSnapshot.value = before
                vm.comparisonSnapshot.value = vanished
                vm.savedSnapshots.value = listOf(before, vanished)
            }
            scrollToText("AirPods Pro")
            compose.onNodeWithText("추가 관측 · BLE · 수신 기록").assertExists()
            compose.onNodeWithText("1개 관측 구간 · 재등장 0회").assertExists()
            saveScreen("scenario-brief-retained")
            observe("brief-earbud", 121000, ScanSource.BLE)
            val returned = capture("시험 중 · 재포착", 122000, start)
            compose.runOnIdle {
                vm.comparisonSnapshot.value = returned
                vm.savedSnapshots.value = listOf(before, vanished, returned)
            }
            scrollToText("AirPods Pro")
            compose.onNodeWithText("2개 관측 구간 · 재등장 1회").assertExists()
            saveScreen("scenario-returned")
            scrollToText("전체 이력 2")
            compose.onNodeWithText("전체 이력 2").performClick()
            compose.onNodeWithText("기준~비교 사이 등장 주소의 합집합 · 중복 제외").assertExists()
            saveScreen("scenario-union")
            scrollToText("AirPods Pro")
            compose.onNodeWithText("AirPods Pro").performClick()
            compose.onNodeWithText("관측 구간·재등장 보기 ▾").performScrollTo().performClick()
            compose.onNodeWithText("재등장 1").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("이전 구간 후 1분 59초 미수신").assertExists()
            saveScreen("scenario-periods")
            compose.onNodeWithText("관측 구간·재등장 보기 ▴").performScrollTo().performClick()
            compose.onNodeWithText("식별 근거·시간 해석 보기 ▾").performScrollTo().performClick()
            compose.onNodeWithText("모델 가정 범위", substring = true).assertExists()
            compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
        } finally { vm.shutdown() }
    }

    @Test fun baselineNewEarbudAndIdentityDetails() {
        compose.setContent { AppRfTheme { App() } }
        compose.onNodeWithText("연습 모드").performClick()
        compose.onNodeWithText("◎  탐색 시작").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("기기 5").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("레이더").performClick()
        saveScreen("radar")
        compose.onNodeWithText("기기 5").performClick()
        compose.onNodeWithText("기준점 저장").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("기준점 저장됨 · 신규 0대").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("기준점 저장됨 · 신규 0대").assertIsDisplayed()
        compose.onNodeWithText("+ 가상 이어폰 추가 · 신규 감지 체험").performClick()
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("기기 6").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("기준점 저장됨 · 신규 1대").assertIsDisplayed()
        compose.onNodeWithText("AirPods Pro · 추가 가상 1").performScrollTo()
        saveScreen("device-list")
        compose.onNodeWithText("AirPods Pro · 추가 가상 1").performClick()
        compose.onNodeWithText("BLE 광고 회사: Apple, Inc.").assertExists()
        compose.onNodeWithText("모델 후보: AirPods Pro").assertExists()
        saveScreen("earbud-detail")
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("■  탐색 중지").performClick()
        compose.onNodeWithText("◎  탐색 시작").assertIsDisplayed()
        compose.onNodeWithText("◎  탐색 시작").performClick()
        compose.onNodeWithText("기기 5").assertExists()
        scrollToText("기준점 저장됨 · 신규 0대")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun smallScreenLargeTextKeepsNavigationAndControlsReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppRfTheme { App() }
            }
        }
        compose.onNodeWithText("◎  탐색 시작").assertIsDisplayed()
        compose.onNodeWithText("안내").performClick()
        compose.onNodeWithText("확인").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Wi-Fi 채널").performClick()
        compose.onNodeWithTag("wifi-view-networks").assertIsSelected()
        compose.onNodeWithTag("wifi-view-comparison").performClick()
        saveScreen("channels-small-large-text")
        scrollToText("액세스 포인트 · 0")
        compose.onNodeWithContentDescription("기기 0").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("AirPods")
        scrollToText("아직 감지된 기기가 없습니다")
        compose.onNodeWithText("◎  탐색 시작").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-mdpi")
    fun landscapeCanSwitchViewsAndStopScanning() {
        compose.setContent { AppRfTheme { App() } }
        compose.onNodeWithText("연습 모드").performClick()
        compose.onNodeWithText("◎  탐색 시작").performClick()
        compose.onNodeWithContentDescription("Wi-Fi 채널").performClick()
        compose.onNodeWithTag("wifi-view-networks").assertIsSelected()
        compose.onNodeWithTag("wifi-view-comparison").performClick()
        scrollToText("액세스 포인트 · 2")
        saveScreen("channels-landscape")
        compose.onNodeWithText("■  탐색 중지").assertIsDisplayed().performClick()
        compose.onNodeWithText("◎  탐색 시작").assertIsDisplayed()
    }

    @Test fun leavingAppStopsScanningBeforeReturning() {
        compose.setContent { AppRfTheme { App() } }
        compose.onNodeWithText("연습 모드").performClick()
        compose.onNodeWithText("◎  탐색 시작").performClick()
        compose.onNodeWithText("■  탐색 중지").assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("◎  탐색 시작").assertIsDisplayed()
    }

    @Test fun emptyRoomSnapshotComparedWithAfterEntrySnapshot() {
        compose.setContent { AppRfTheme { App() } }
        compose.onNodeWithText("연습 모드").performClick()
        compose.onNodeWithText("◎  탐색 시작").performClick()
        compose.onNodeWithText("비교").performClick()
        compose.onNodeWithText("공간 이름 · 예: 3층 제1시험실").performTextReplacement("제1시험실")
        scrollToText("① 입실 전 저장")
        compose.onNodeWithText("입실 전 저장").assertIsDisplayed()
        saveScreen("flow-1-before-entry")
        compose.onNodeWithText("① 입실 전 저장").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("스냅샷 저장됨", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("기기 5").performClick()
        scrollToText("+ 가상 이어폰 추가 · 신규 감지 체험")
        compose.onNodeWithText("+ 가상 이어폰 추가 · 신규 감지 체험").performClick()
        repeat(8) {
            Thread.sleep(300)
            compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
        }
        compose.onNodeWithText("비교").performClick()
        scrollToText("공간 이름 · 예: 3층 제1시험실")
        compose.onNodeWithText("공간 이름 · 예: 3층 제1시험실").assertTextContains("제1시험실")
        scrollToText("② 입실 후 저장하고 비교")
        compose.onNodeWithText("입실 후 비교").assertIsDisplayed()
        saveScreen("flow-2-after-entry")
        compose.onNodeWithText("② 입실 후 저장하고 비교").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("스냅샷 저장됨 · 제1시험실 · 입실 후", substring = true).fetchSemanticsNodes().isNotEmpty() }
        scrollToText("추가 1")
        compose.onNodeWithText("공통 5").assertExists()
        scrollToText("새 스냅샷 기록")
        scrollToText("3  추가 장비 확인")
        saveScreen("snapshot-comparison")
        scrollToText("AirPods Pro")
        compose.onNodeWithText("이어폰 후보").assertExists()
        compose.onNodeWithText("추가 장비 확인").assertIsDisplayed()
        compose.onNodeWithText("추가 주소는 확인 후보입니다.", substring = true).assertDoesNotExist()
        saveScreen("flow-2-added-signals")
        scrollToText("미관측 0")
        compose.onNodeWithText("미관측 0").performClick()
        scrollToText("미관측 주소가 없습니다")
        scrollToText("공통 5")
        compose.onNodeWithText("공통 5").performClick()
        scrollToText("결과 의미 보기 ▾")
        compose.onNodeWithText("결과 의미 보기 ▾").performClick()
        compose.onNodeWithText("공통: 두 시점에 모두 기록된 주소입니다.", substring = true).assertExists()
        compose.onNodeWithText("추가 1").performClick()
        compose.onNodeWithText("결과 의미 보기 ▴").performClick()
        scrollToText("검색·필터·정렬")
        compose.onNodeWithText("검색·필터·정렬").performClick()
        scrollToText("비교 결과 검색 · 이름/제조사/모델/MAC")
        compose.onNodeWithText("비교 결과 검색 · 이름/제조사/모델/MAC").performTextInput("Sony")
        scrollToText("검색·필터에 맞는 신호가 없습니다")
        scrollToText("추가 1")
        scrollToText("비교 결과 검색 · 이름/제조사/모델/MAC")
        compose.onNodeWithText("지우기").performClick()
        scrollToText("오디오 후보만")
        compose.onNodeWithText("오디오 후보만").performClick()
        scrollToText("정렬 · 강한 신호순 ▾")
        compose.onNodeWithText("정렬 · 강한 신호순 ▾").performClick()
        compose.onNodeWithText("수신 횟수순").performClick()
        scrollToText("AirPods Pro")
        saveScreen("snapshot-audio-analysis")
        compose.onNodeWithText("AirPods Pro").performClick()
        compose.onNode(hasText("이어폰 후보") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("Apple") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("AirPods Pro") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("계속 송신·연결된 시간은 아니며", substring = true).assertDoesNotExist()
        saveScreen("flow-3-earbud-details")
        compose.onNodeWithText("식별 근거·시간 해석 보기 ▾").performClick()
        compose.onNodeWithText("모델 후보: AirPods Pro").assertExists()
        compose.onNodeWithText("계속 송신·연결된 시간은 아니며", substring = true).assertExists()
        saveScreen("snapshot-signal-detail")
        compose.onNodeWithText("식별 근거·시간 해석 보기 ▴").performScrollTo().performClick()
        compose.onNodeWithText("시점별 기록 보기 ▾").performScrollTo().performClick()
        compose.onNodeWithText("저장 시점별 관측").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("미관측").assertExists()
        saveScreen("snapshot-signal-timeline")
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("■  탐색 중지").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("이전 기록 다시 보기", substring = true))
        compose.onNodeWithText("이전 기록 다시 보기", substring = true).performScrollTo().performClick()
        scrollToText("저장 자료 검색 · 공간/시점")
        compose.onNodeWithText("저장 자료 검색 · 공간/시점").performTextInput("없는 공간")
        scrollToText("조건에 맞는 저장 자료가 없습니다")
        scrollToText("검색 해제")
        compose.onNodeWithText("검색 해제").performClick()
        scrollToText("제1시험실 / 입실 후 · 연습")
        saveScreen("snapshot-library")
        scrollToText("추가 1")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun snapshotCaptureWorksOnSmallScreenWithLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppRfTheme { App() }
            }
        }
        compose.onNodeWithText("연습 모드").performClick()
        compose.onNodeWithText("◎  탐색 시작").performClick()
        compose.onNodeWithText("비교").performClick()
        scrollToText("① 입실 전 저장")
        compose.onNodeWithText("① 입실 전 저장").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("스냅샷 저장됨", substring = true).fetchSemanticsNodes().isNotEmpty() }
        scrollToText("3  추가 장비 확인")
        saveScreen("snapshot-small-large-text")
        compose.onNodeWithContentDescription("기기 5").performClick()
        scrollToText("+ 가상 이어폰 추가 · 신규 감지 체험")
        compose.onNodeWithText("+ 가상 이어폰 추가 · 신규 감지 체험").performClick()
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)) }
        compose.onNodeWithText("비교").performClick()
        scrollToText("② 입실 후 저장하고 비교")
        compose.onNodeWithText("② 입실 후 저장하고 비교").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("스냅샷 저장됨 · 시험실 · 입실 후", substring = true).fetchSemanticsNodes().isNotEmpty() }
        scrollToText("AirPods Pro")
        compose.onNodeWithText("추가 장비 확인").assertIsDisplayed()
        saveScreen("snapshot-added-small-large-text")
        compose.onNodeWithText("AirPods Pro").performClick()
        compose.onNodeWithText("식별 근거·시간 해석 보기 ▾").performScrollTo().performClick()
        compose.onNodeWithText("모델 후보: AirPods Pro").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
        compose.onNodeWithText("■  탐색 중지").assertIsDisplayed()
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

    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
}
