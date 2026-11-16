package com.classroom.rfdetector.ui

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.classroom.rfdetector.App
import com.classroom.rfdetector.AppViewModel
import com.classroom.rfdetector.core.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WifiNetworksUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstAppVisitHasNoInventedEpochTimestamp() {
        compose.setContent { AppRfTheme { App() } }
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        try {
            compose.waitUntil(10_000) { !vm.snapshotBusy.value }
            compose.onNodeWithContentDescription("Wi-Fi 채널").performClick()
            compose.onNodeWithTag("wifi-view-networks").assertIsSelected()
            compose.onNodeWithText("탐색 전 · 기록 없음").assertIsDisplayed()
            compose.onNodeWithText("01/01", substring = true).assertDoesNotExist()
        } finally { vm.shutdown() }
    }

    @Test fun fullAppOpensNetworkNamesAndKeepsFirstSsidVisibleWithoutScrolling() {
        compose.setContent { AppRfTheme { App() } }
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        try {
            val scenario = scenario()
            compose.waitUntil(10_000) { !vm.snapshotBusy.value }
            compose.runOnIdle {
                vm.setSim(true)
                vm.baselineSnapshot.value = scenario.before
                vm.comparisonSnapshot.value = scenario.after
                vm.savedSnapshots.value = listOf(scenario.after, scenario.middle, scenario.before)
            }
            compose.onNodeWithContentDescription("Wi-Fi 채널").performClick()
            compose.onNodeWithTag("wifi-view-networks").assertIsSelected()
            compose.waitUntil(10_000) { vm.wifiChannelAnalysis.value.channels.isNotEmpty() }
            compose.onNodeWithText("주변 Wi-Fi").assertIsDisplayed()
            compose.onNodeWithTag("network-row-$FIRST").assertIsDisplayed().assertTextContains("시험장 Wi-Fi")
            compose.onNodeWithText("SSID · BSSID 검색").assertDoesNotExist()
            saveScreen("wifi-networks-overview")
        } finally { vm.shutdown() }
    }

    @Test fun namesAppearWithoutBaselineAndSameSsidKeepsTwoAddresses() {
        val fixture = scenario()
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { ChannelContent(
            analyzeWifiChannels(null, fixture.after, listOf(fixture.middle)), simulation = true) } } }
        compose.onNodeWithTag("wifi-view-networks").assertIsSelected()
        compose.onNodeWithText("탐색 중지 · 마지막 기록", substring = true).assertExists()
        scrollToTag("network-row-$FIRST")
        compose.onNodeWithTag("network-row-$FIRST").assertTextContains("시험장 Wi-Fi").assertTextContains("BSSID · $FIRST")
        scrollToTag("network-row-$SECOND")
        compose.onNodeWithTag("network-row-$SECOND").assertTextContains("시험장 Wi-Fi").assertTextContains("BSSID · $SECOND")
        compose.onNodeWithText("추가 · 기준에 없던 AP 주소").assertDoesNotExist()
    }

    @Test fun bandFilteringCannotReviveAnOldChannelAndHiddenFilterKeepsTheCurrentHiddenSsid() {
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { ChannelContent(scenario().analysis(), simulation = true) } } }
        openFilters()
        compose.onNodeWithTag("network-band-GHZ_2_4").performScrollTo().performClick()
        compose.onNodeWithTag("network-search").performTextInput("시험장 Wi-Fi")
        scrollToText("이 조건의 네트워크가 없습니다")
        compose.onNodeWithText("이 조건의 네트워크가 없습니다").assertIsDisplayed()
        scrollToTag("network-search")
        compose.onNodeWithTag("network-search").performTextClearance()
        compose.onNodeWithTag("networks-hidden").performScrollTo().performClick()
        scrollToTag("network-row-$HIDDEN")
        compose.onNodeWithTag("network-row-$HIDDEN").assertTextContains("숨김 SSID").assertTextContains("2.4 GHz · 채널 11")
        compose.onNodeWithTag("network-row-$HIDDEN").performClick()
        compose.onNode(hasText("BSSID · $HIDDEN") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
    }

    @Test fun brieflyObservedHiddenNetworkRemainsInHistoryAndTiedChannelsStayUncertain() {
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { ChannelContent(scenario().analysis(), simulation = true, savedComparison = true) } } }
        scrollToTag("networks-history")
        compose.onNodeWithTag("networks-history").performClick()
        scrollToTag("network-row-$BRIEF")
        compose.onNodeWithTag("network-row-$BRIEF").assertTextContains("이력 · 현재 수신 여부 미확인").performClick()
        compose.onNode(hasText("BSSID · $BRIEF") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("한 번 포착 · 기간 판단 불가").assertExists()
        saveScreen("wifi-networks-history")
        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
        scrollToTag("networks-uncertain")
        compose.onNodeWithTag("networks-uncertain").performClick()
        scrollToTag("network-row-$AMBIGUOUS")
        compose.onNodeWithTag("network-row-$AMBIGUOUS").assertTextContains("최신 채널 미확정").performClick()
        compose.onNodeWithText("같은 최신 시각에 여러 채널이 있어 하나를 고르지 않았습니다.", substring = true).assertExists()
        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
    }

    @Test fun legacyTargetShowsUnknownCountAndNoGraphInsteadOfMeasuredZero() {
        val fixture = scenario()
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { ChannelContent(
            analyzeWifiChannels(fixture.before, fixture.after.copy(wifiChannels = null), listOf(fixture.middle)), savedComparison = true) } } }
        compose.onNodeWithText("최근 네트워크 수는 미확인입니다", substring = true).assertExists()
        scrollToText("채널·세기 그래프 ▾")
        compose.onNodeWithText("채널·세기 그래프 ▾").performClick()
        scrollToText("최근 채널·세기 자료가 없어 그래프를 표시할 수 없습니다")
        compose.onNodeWithText("최근 채널·세기 자료가 없어 그래프를 표시할 수 없습니다").assertIsDisplayed()
        compose.onNodeWithTag("network-map-GHZ_2_4").assertDoesNotExist()
        compose.onNodeWithText("최근 확인 · 0").assertDoesNotExist()
        scrollToTag("networks-uncertain")
        compose.onNodeWithTag("networks-uncertain").performClick()
        scrollToTag("network-row-$FIRST")
        compose.onNodeWithTag("network-row-$FIRST").assertTextContains("대상 자료 없음 · 이전 기록만 확인")
    }

    @Test fun channelMapUsesMeasuredPrimaryPositionsAndNamesOpenTheirOwnNetwork() {
        val plot = wifiNetworkPlot(analyzeWifiNetworks(scenario().analysis()), WifiBand.GHZ_2_4)
        var selected: String? = null
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("연습 데이터 · 실제 무선 수신 아님", Modifier.fillMaxWidth(), color = RfColors.warning)
                WifiNetworkChannelMap(plot) { selected = it.address }
            }
        } } }
        assertEquals(3, plot.points.size)
        assertFalse(plot.points.any { it.network.address == FIRST || it.network.address == AMBIGUOUS })
        compose.onNodeWithTag("network-map-GHZ_2_4").assertIsDisplayed()
        compose.onNodeWithText("AP별 마지막 RSSI · 실제 스펙트럼·혼잡도 아님").assertIsDisplayed()
        saveScreen("wifi-networks-channel-map")
        compose.onNodeWithTag("network-map-open-$HIDDEN").performScrollTo().performClick()
        assertEquals(HIDDEN, selected)
    }

    @Test fun viewChoiceSurvivesRestorationAndOnlyANewRequestOverridesIt() {
        val tester = StateRestorationTester(compose)
        val request = mutableIntStateOf(0)
        tester.setContent { AppRfTheme { Surface(color = RfColors.bg) {
            ChannelContent(scenario().analysis(), initialView = UiChannelView.NETWORKS, viewRequestKey = request.intValue)
        } } }
        compose.onNodeWithTag("wifi-view-comparison").performClick().assertIsSelected()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("wifi-view-comparison").assertIsSelected()
        compose.runOnIdle { request.intValue++ }
        compose.onNodeWithTag("wifi-view-networks").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun largeTextKeepsNetworkFiltersAndDetailCloseReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppRfTheme { Surface(color = RfColors.bg) { ChannelContent(scenario().analysis(), simulation = true) } }
            }
        }
        openFilters()
        compose.onNodeWithTag("networks-hidden").performScrollTo().performClick()
        scrollToTag("network-row-$HIDDEN")
        compose.onNodeWithTag("network-row-$HIDDEN").performClick()
        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
        scrollToText("목록·그래프의 의미 ▾")
        compose.onNodeWithText("목록·그래프의 의미 ▾").performClick()
        scrollToText("접속점(AP) 목록이며 연결된 휴대전화 수가 아닙니다.", substring = true)
        compose.onNodeWithText("접속점(AP) 목록이며 연결된 휴대전화 수가 아닙니다.", substring = true).assertIsDisplayed()
    }

    private data class Scenario(val before: SignalSnapshot, val middle: SignalSnapshot, val after: SignalSnapshot) {
        fun analysis() = analyzeWifiChannels(before, after, listOf(middle))
    }

    private fun scenario(): Scenario {
        val start = Instant.parse("2026-11-16T00:00:00Z").toEpochMilli()
        val now = start + 180_000
        fun point(address: String, name: String, frequency: Int, at: Long, rssi: Int?, hidden: Boolean = false,
            first: Long = at, width: Int? = null) = WifiChannelObservation(address, name, frequency, first, at,
            if (first == at) 1 else 4, rssi, rssi, listOf(ObservationPeriod(first, at, if (first == at) 1 else 4)),
            hidden = hidden, everHidden = hidden, wifiMetadata = width?.let { WifiRadioMetadata(channelWidthCode = it) })
        val oldFirst = point(FIRST, "시험장 Wi-Fi", 2437, start, -60)
        val before = SignalSnapshot("before-networks", "시험실", "입실 전", start, start, true, emptyList(), listOf(
            oldFirst, point(SECOND, "시험장 Wi-Fi", 5180, start, -70),
        ))
        val middle = SignalSnapshot("brief-network", "시험실", "잠깐 포착", start + 2_000, start, true, emptyList(), listOf(
            point(BRIEF, "이름 없는 AP", 2417, start + 2_000, -82, hidden = true),
        ))
        val after = SignalSnapshot("after-networks", "시험실", "입실 후", now, start, true, emptyList(), listOf(
            oldFirst,
            point(FIRST, "시험장 Wi-Fi", 5180, now, -50, first = start + 10_000, width = 2),
            point(SECOND, "시험장 Wi-Fi", 5180, now, -68, first = start, width = 2),
            point(HIDDEN, "이름 없는 AP", 2462, now, -55, hidden = true, first = now - 2_000),
            point(AMBIGUOUS, "채널 미확정 AP", 2412, now, -62),
            point(AMBIGUOUS, "채널 미확정 AP", 5955, now, -62),
            point("00:11:22:33:44:06", "6 GHz · 세기 미확인", 5955, now, null),
            point("00:11:22:33:44:07", "시험장 프로젝터", 2412, now, -72),
            point("00:11:22:33:44:08", "게스트 네트워크", 2437, now, -86),
        ))
        return Scenario(before, middle, after)
    }

    private fun openFilters() {
        scrollToText("검색·필터 ▾")
        compose.onNodeWithText("검색·필터 ▾").performClick()
    }
    private fun scrollToTag(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo()
    }
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
    companion object {
        private const val FIRST = "00:11:22:33:44:01"
        private const val SECOND = "00:11:22:33:44:02"
        private const val HIDDEN = "00:11:22:33:44:03"
        private const val BRIEF = "00:11:22:33:44:04"
        private const val AMBIGUOUS = "00:11:22:33:44:05"
    }
}
