package com.classroom.rfdetector.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.classroom.rfdetector.AppViewModel
import com.classroom.rfdetector.core.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BeaconPresentationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun unnamedBeaconShowsTypeAndEvidence() {
        val vm = AppViewModel(compose.activity.applicationContext)
        val device = beaconTracker().snapshot(2000).single()
        try {
            compose.setContent { AppRfTheme { Surface(color = RfColors.bg) {
                DeviceListScreen(vm, listOf(device), baselineSet = true, newCount = 1)
            } } }
            scrollTo("유형: BLE 비콘 광고")
            compose.onNodeWithText("유형: BLE 비콘 광고").assertIsDisplayed()
            compose.onNodeWithText(BEACON_FORMAT_NOTE).assertDoesNotExist()
            compose.onNodeWithText(device.displayName).performClick()
            compose.onNodeWithText("무선 정보 자세히 ▾").performScrollTo().performClick()
            compose.onNodeWithText("iBeacon 호환 형식").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(BEACON_FORMAT_NOTE).performScrollTo().assertIsDisplayed()
        } finally { vm.shutdown() }
    }

    @Test fun reopenedBeaconRetainsTypeAndFormat() {
        val tracker = beaconTracker()
        val before = SignalSnapshot("before", "Room", "입실 전", 0, 0, true, emptyList())
        val after = SignalSnapshot.capture("after", "Room", "입실 후", 2000, 0, true, tracker.snapshot(2000))
        val store = SnapshotStore(File(compose.activity.cacheDir, "b").apply { mkdirs() })
        store.save(listOf(before, after))
        val restored = store.load()
        val vm = AppViewModel(compose.activity.applicationContext)
        try {
            compose.setContent { AppRfTheme { Surface(color = RfColors.bg) { SnapshotScreen(vm) } } }
            compose.waitUntil(10_000) { !vm.snapshotBusy.value }
            compose.runOnIdle {
                vm.setSim(true)
                vm.baselineSnapshot.value = restored[0]
                vm.comparisonSnapshot.value = restored[1]
                vm.savedSnapshots.value = restored
            }
            scrollTo("BLE 비콘 광고")
            compose.onNodeWithText("BLE 비콘 광고").assertIsDisplayed()
            compose.onNodeWithText("BLE 비콘 광고").performClick()
            compose.onNodeWithText("무선 정보 자세히 ▾").performScrollTo().performClick()
            compose.onNodeWithText("관측된 비콘 광고 형식").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("iBeacon 호환 형식").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(BEACON_FORMAT_NOTE).performScrollTo().assertIsDisplayed()
        } finally { vm.shutdown() }
    }

    private fun beaconTracker() = DeviceTracker().apply {
        update(RfObservation("AA:00:00:00:00:01", ScanSource.BLE, null, -72, null, false, 1000,
            radioMetadata = RadioMetadata(ble = BleRadioMetadata(beaconFormats = listOf(BeaconFormat.IBEACON_COMPATIBLE)))))
        update(RfObservation("AA:00:00:00:00:01", ScanSource.BLE, null, -74, null, false, 2000))
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
}
