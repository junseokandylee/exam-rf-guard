package com.classroom.rfdetector.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.core.*
import java.io.File
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
class ChannelFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun newChannelsKeepBriefHiddenHistoryAndDistinguishAnExistingApMove() {
        val analysis = scenario()
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { ChannelContent(analysis, simulation = true, savedComparison = true) } } }
        openComparison()
        compose.onNodeWithText("② 기준에 없던 채널 3개").assertIsDisplayed()
        compose.onNodeWithText("새 AP 2개").assertExists()
        compose.onNodeWithText("기존 AP 채널 이동 1개").assertExists()
        compose.onNodeWithText("저장 시점 비교 ·", substring = true).assertExists()
        saveScreen("wifi-channels-overview")
        scrollToText("채널별 AP 분포")
        compose.onNodeWithText("최근 90초 · AP 수와 수신 세기").assertExists()
        scrollToTag("channel-card-2462")
        compose.onNodeWithTag("channel-card-2462").performClick()
        compose.onNodeWithText("시험장 공유기").assertExists()
        compose.onNodeWithText("기준 AP의 채널 이동", substring = true).assertExists()
        compose.onNodeWithText("2.4 GHz · 채널 6 → 2.4 GHz · 채널 11", substring = true).assertExists()
        compose.onNodeWithText("2개 관측 구간 · 재등장 1회").performScrollTo().assertIsDisplayed()
        saveScreen("wifi-channel-moved-ap")
        compose.onNodeWithText("관측 구간 보기 ▾").performScrollTo().performClick()
        compose.onNodeWithText("재등장 1 · 1회", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
        scrollToTag("channel-card-2412")
        compose.onNodeWithTag("channel-card-2412").performClick()
        compose.onNodeWithText("이름 없는 AP").assertExists()
        compose.onNodeWithText("수신 이력 · 현재 채널 단정 불가").assertExists()
        compose.onNodeWithText("숨김 SSID · 마지막 수신에 이름 미표시").assertExists()
        compose.onNodeWithText("기준에 없던 AP 주소").assertExists()
        saveScreen("wifi-channel-brief-hidden")
        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
    }

    @Test fun bandAndHiddenFiltersKeepPreviouslyHiddenNamesDiscoverable() {
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { ChannelContent(scenario(), simulation = true) } } }
        openComparison()
        compose.onNodeWithText("중지 시 마지막 관측 ·", substring = true).assertExists()
        scrollToTag("channel-hidden-only")
        compose.onNodeWithTag("channel-hidden-only").performClick().assertIsSelected()
        compose.onNodeWithText("숨김 이력이 있는 채널만 표시합니다. 막대는 그 채널의 전체 AP 수입니다.").assertExists()
        compose.onNodeWithTag("channel-band-GHZ_6").performScrollTo().performClick().assertIsSelected()
        scrollToTag("channel-card-5955")
        compose.onNodeWithTag("channel-card-5955").performClick()
        compose.onNodeWithText("새 핫스폿").assertExists()
        compose.onNodeWithText("이전에 SSID 미표시 · 기록 유지").assertExists()
        compose.onNodeWithText("숨김 이력 필터 적용 · 이름을 나중에 받은 AP도 포함").assertExists()
        saveScreen("wifi-channel-hidden-history")
        compose.onNodeWithText("닫기").performClick()
        scrollToTag("channel-band-GHZ_5")
        compose.onNodeWithTag("channel-band-GHZ_5").performClick()
        scrollToText("이 대역의 채널 기록이 없습니다")
        compose.onNodeWithText("이 대역의 채널 기록이 없습니다").assertIsDisplayed()
    }

    @Test fun legacyBaselineAsksForChannelEvidenceInsteadOfCallingEveryChannelNew() {
        val (before, after, archive) = scenarioSnapshots()
        val analysis = analyzeWifiChannels(before.copy(wifiChannels = null), after, archive)
        var openedComparison = false
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { ChannelContent(analysis, onOpenComparison = { openedComparison = true }) } } }
        openComparison()
        compose.onNodeWithText("기준에 주파수 기록이 없습니다.", substring = true).assertExists()
        compose.onNodeWithText("비교에서 기준·대상 선택").performClick()
        assertTrue(openedComparison)
        scrollToTag("channel-new-only")
        compose.onNodeWithTag("channel-new-only").assertIsNotEnabled()
        saveScreen("wifi-channel-baseline-needed")
        scrollToTag("channel-card-2462")
        compose.onNodeWithTag("channel-card-2462").assertTextContains("기준 비교 미확정")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun smallScreenLargeTextKeepsFiltersDetailsAndExplanationReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppRfTheme { Surface(color = RfColors.bg) { ChannelContent(scenario(), simulation = true) } }
            }
        }
        openComparison()
        scrollToTag("channel-new-only")
        compose.onNodeWithTag("channel-new-only").performClick().assertIsSelected()
        scrollToTag("channel-card-2412")
        compose.onNodeWithTag("channel-card-2412").performClick()
        compose.onNodeWithText("관측 구간 보기 ▾").performScrollTo().performClick()
        compose.onNodeWithText("첫 포착 · 1회", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed()
        saveScreen("wifi-channel-small-large-text")
        compose.onNodeWithText("닫기").performClick()
        scrollToText("AP 분포·겹침의 의미 ▾")
        compose.onNodeWithText("AP 분포·겹침의 의미 ▾").performClick()
        scrollToText("숨김 SSID는 네트워크 이름이 표시되지 않은 관측입니다.", substring = true)
        compose.onNodeWithText("숨김 자체로 위험성이나 부정행위를 확정할 수 없습니다.", substring = true).assertIsDisplayed()
    }

    @Test fun legacyTargetShowsUnavailableDistributionInsteadOfZeroRecentAccessPoints() {
        val (before, after, _) = scenarioSnapshots()
        val analysis = analyzeWifiChannels(before, after.copy(wifiChannels = null))
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { ChannelContent(analysis, savedComparison = true) } } }
        openComparison()
        scrollToText("대상에 주파수 기록이 없어 최근 AP 분포를 알 수 없습니다")
        compose.onNodeWithText("대상에 주파수 기록이 없어 최근 AP 분포를 알 수 없습니다").assertIsDisplayed()
        compose.onAllNodesWithText("최근 90초 · AP 수와 수신 세기").assertCountEquals(0)
        scrollToTag("channel-card-2437")
        compose.onNodeWithTag("channel-card-2437").assertTextContains("최근 AP — · 자료 없음").performClick()
        compose.onNodeWithText("최근 AP 자료 없음", substring = true).assertExists()
        compose.onNodeWithText("대상 채널 자료 없음 · 기준·이전 이력").assertExists()
        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
    }

    @Test fun channelDistributionRendersAllBandsAndOpensTheChosenFrequency() {
        var selectedFrequency: Int? = null
        compose.setContent {
            AppRfTheme { Surface(color = RfColors.bg) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("연습 데이터 · 실제 무선 수신 아님", color = RfColors.warning)
                    ChannelDistribution(scenario().channels) { selectedFrequency = it.frequencyMhz }
                }
            } }
        }
        compose.onNodeWithTag("channel-chart-2412").assertIsDisplayed()
        compose.onNodeWithTag("channel-chart-5955").assertIsDisplayed()
        compose.onNodeWithText("AP 개수 분포이며 실제 트래픽 혼잡률·간섭률이 아닙니다.").assertIsDisplayed()
        saveScreen("wifi-channels-distribution")
        compose.onNodeWithTag("channel-chart-5955").performClick()
        assertEquals(5955, selectedFrequency)
    }

    private fun scenario() = scenarioSnapshots().let { (before, after, archive) -> analyzeWifiChannels(before, after, archive) }

    /** Observed-frequency fixtures exercise channel changes, a brief hidden AP and a later named AP. */
    private fun scenarioSnapshots(): Triple<SignalSnapshot, SignalSnapshot, List<SignalSnapshot>> {
        val start = java.time.Instant.parse("2026-11-16T00:00:00Z").toEpochMilli()
        fun observation(address: String, name: String, frequency: Int, offset: Long, hidden: Boolean = false, everHidden: Boolean = hidden) =
            WifiChannelObservation(address, name, frequency, start + offset, start + offset, 1, -63, -63,
                listOf(ObservationPeriod(start + offset, start + offset, 1)), hidden, everHidden)
        fun snapshot(id: String, offset: Long, observations: List<WifiChannelObservation>) =
            SignalSnapshot(id, "제1시험실", id, start + offset, start, true, emptyList(), wifiChannels = observations)
        val roomAp = observation("00:00:00:00:00:01", "시험장 공유기", 2437, 0)
        val corridorAp = observation("00:00:00:00:00:02", "복도 AP", 5180, 0)
        val before = snapshot("입실 전", 0, listOf(roomAp, corridorAp))
        val brief = observation("00:00:00:00:00:03", "이름 없는 AP", 2412, 2000, hidden = true)
        val middle = snapshot("중간 포착", 3000, listOf(roomAp, corridorAp, brief))
        val moved = observation(roomAp.address, roomAp.name, 2462, 180_000).copy(firstSeenMs = start + 1000, observationCount = 3,
            periods = listOf(ObservationPeriod(start + 1000, start + 2000, 2), ObservationPeriod(start + 180_000, start + 180_000, 1)))
        val namedLater = observation("00:00:00:00:00:04", "새 핫스폿", 5955, 180_000, everHidden = true)
        val after = snapshot("입실 후", 180_000, listOf(roomAp, corridorAp, moved, namedLater))
        return Triple(before, after, listOf(before, middle, after))
    }

    private fun scrollToTag(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun openComparison() { compose.onNodeWithTag("wifi-view-comparison").performClick().assertIsSelected() }

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
