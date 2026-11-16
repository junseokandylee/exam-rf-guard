package com.classroom.rfdetector.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.classroom.rfdetector.App
import com.classroom.rfdetector.AppViewModel
import com.classroom.rfdetector.core.CheckpointStore
import com.classroom.rfdetector.core.SnapshotStore
import com.classroom.rfdetector.core.SignalSnapshot
import com.classroom.rfdetector.core.WifiChannelObservation
import com.classroom.rfdetector.core.WifiChannelComparisonState
import com.classroom.rfdetector.sim.RadioSimulator
import java.io.File
import java.time.Duration
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
class WifiIntegrationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun choosingBaselineWithoutStartingReceptionDoesNotCreateAnEmptyTarget() {
        compose.setContent { AppRfTheme { App() } }
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        try {
            compose.waitUntil(10_000) { !vm.snapshotBusy.value }
            compose.runOnIdle {
                vm.setSim(true)
                val at = System.currentTimeMillis() - 1000
                val channel = WifiChannelObservation("AA", "기준 AP", 2437, at, at, 1, -60, -60)
                vm.selectBaseline(SignalSnapshot("saved-before", "시험실", "입실 전", at, at, true, emptyList(), listOf(channel)))
            }
            compose.onNodeWithContentDescription("Wi-Fi 채널").performClick()
            compose.onNodeWithTag("wifi-view-networks").assertIsSelected()
            compose.onNodeWithTag("wifi-view-comparison").performClick()
            compose.waitUntil(10_000) { vm.wifiChannelAnalysis.value.comparisonState == WifiChannelComparisonState.TARGET_UNKNOWN }
            assertFalse(vm.wifiChannelAnalysis.value.targetChannelDataAvailable)
            compose.onNodeWithText("대상에 주파수 기록이 없습니다.", substring = true).assertExists()
        } finally { vm.shutdown() }
    }

    @Test fun hiddenChannelAlertOpensLiveComparisonAndSurvivesSavedHistoryReload() {
        compose.setContent { AppRfTheme { App() } }
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        var restored: AppViewModel? = null
        try {
            startWithBaseline(vm)
            saveTarget(vm, "입실 후")
            val frozen = vm.comparisonSnapshot.value!!
            compose.onNodeWithContentDescription("Wi-Fi 채널").performClick()
            compose.onNodeWithTag("wifi-view-networks").assertIsSelected()
            compose.waitUntil(10_000) { vm.wifiChannelAnalysis.value.canCompare }
            compose.runOnIdle { vm.simAddWifiDevice(hidden = true) }
            advanceSimulation()
            compose.waitUntil(10_000) { vm.latestNewWifiChannel.value != null }
            assertTrue(vm.latestNewWifiChannel.value!!.contains("숨김 SSID"))
            assertEquals(frozen.id, vm.comparisonSnapshot.value!!.id)
            assertFalse(vm.wifiChannelAnalysis.value.channels.any { it.frequencyMhz == 2462 })

            compose.onNodeWithText("채널 확인").performClick()
            compose.onNodeWithContentDescription("Wi-Fi 채널").assertIsSelected()
            compose.onNodeWithTag("wifi-view-comparison").assertIsSelected()
            compose.waitUntil(10_000) { vm.wifiChannelAnalysis.value.addedHiddenAPCount == 1 }
            assertNull(vm.comparisonSnapshot.value)
            val added = vm.wifiChannelAnalysis.value.channels.single { it.frequencyMhz == 2462 }
            assertEquals(true, added.isNewChannel)
            assertEquals(1, added.addedAPCount)
            assertTrue(added.accessPoints.single().observation.hidden)

            saveTarget(vm, "숨김 AP 포착")
            val targetId = vm.comparisonSnapshot.value!!.id
            compose.runOnIdle { vm.sim.devices.removeAll { it.key.startsWith("sim-extra-wifi-") } }
            advanceSimulation()
            compose.runOnIdle { vm.stopScan() }
            compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); !vm.checkpointBusy.value }
            val checkpoint = CheckpointStore(File(compose.activity.filesDir, "checkpoints")).load()
                .first { it.id == "auto-${vm.scanSession.value!!.id}" }
            assertTrue(checkpoint.wifiChannels!!.any { it.frequencyMhz == 2462 && it.everHidden })
            assertTrue(SnapshotStore(compose.activity.filesDir).load().first { it.id == targetId }
                .wifiChannels!!.any { it.frequencyMhz == 2462 && it.hidden })

            compose.runOnIdle {
                vm.shutdown()
                restored = AppViewModel(compose.activity.applicationContext)
            }
            compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); restored?.snapshotBusy?.value == false }
            assertTrue(restored!!.savedSnapshots.value.first { it.id == targetId }.wifiChannels!!
                .any { it.frequencyMhz == 2462 && it.everHidden })
        } finally { restored?.shutdown(); vm.shutdown() }
    }

    @Test fun existingApChangingChannelTriggersChannelAlertWithoutNewDeviceCount() {
        compose.setContent { AppRfTheme { App() } }
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        try {
            startWithBaseline(vm)
            compose.runOnIdle {
                val index = vm.sim.devices.indexOfFirst { it.key == "sim-hotspot" }
                val previous = vm.sim.devices[index]
                vm.sim.devices[index] = RadioSimulator.VDev(previous.key, previous.name, previous.source,
                    previous.hidden, previous.x, previous.y, frequencyMhz = 2462)
            }
            advanceSimulation()
            compose.waitUntil(10_000) { vm.latestNewWifiChannel.value != null }
            assertEquals(0, vm.newCount.value)
            compose.onNodeWithText("채널 확인").performClick()
            compose.waitUntil(10_000) { vm.wifiChannelAnalysis.value.channels.any { it.frequencyMhz == 2462 } }
            val analysis = vm.wifiChannelAnalysis.value
            val moved = analysis.channels.single { it.frequencyMhz == 2462 }
            assertEquals(1, moved.movedAPCount)
            assertEquals(0, moved.addedAPCount)
            assertEquals(0, analysis.channels.single { it.frequencyMhz == 2437 }.recentAPCount)
            assertEquals(1, analysis.channels.single { it.frequencyMhz == 2437 }.observedAPCount)
        } finally { vm.shutdown() }
    }

    private fun startWithBaseline(vm: AppViewModel) {
        compose.waitUntil(10_000) { !vm.snapshotBusy.value }
        compose.runOnIdle { vm.setSim(true); vm.startScan() }
        compose.waitUntil(10_000) { vm.devices.value.flatMap { it.wifiChannels }.size == 2 }
        compose.runOnIdle { vm.saveSnapshot("시험실", "입실 전", asBaseline = true) }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); !vm.snapshotBusy.value }
        assertNotNull("Baseline save failed: ${vm.snapshotError.value}; scanning=${vm.scanning.value}", vm.baselineSnapshot.value)
        assertEquals(2, vm.baselineSnapshot.value!!.wifiChannels!!.size)
    }

    private fun saveTarget(vm: AppViewModel, label: String) {
        compose.runOnIdle { vm.saveSnapshot("시험실", label) }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); !vm.snapshotBusy.value }
        assertEquals("Target save failed: ${vm.snapshotError.value}", label, vm.comparisonSnapshot.value?.label)
    }

    private fun advanceSimulation() {
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)) }
    }
}
