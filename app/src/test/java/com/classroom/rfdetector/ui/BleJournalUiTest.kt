package com.classroom.rfdetector.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.core.BleJournalStatus
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
class BleJournalUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun countsDistinguishDeliveryFromDurableStorageAndExportIsReachable() {
        var exported = false
        compose.setContent { AppRfTheme { Surface(color = RfColors.bg) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("가상 콜백 예시 · 실물 수신 검증 아님", color = RfColors.warning)
                BleJournalPanel(BleJournalStatus("demo", received = 1002, written = 1000, pending = 2), false, false, null) { exported = true }
            }
        } } }
        compose.onNodeWithText("앱에 전달 1002회 · 저장 완료 1000회").assertIsDisplayed()
        compose.onNodeWithText("저장 대기 2회 · 저장 실패 0회").assertIsDisplayed()
        compose.onNodeWithText("BLE 광고 기록 파일로 저장").performClick()
        assertTrue(exported)
        compose.onNodeWithText("어떤 광고를 기록하나요? ▾").performClick()
        compose.onNodeWithText("Android가 현재 탐색에 전달한 광고 결과", substring = true).assertExists()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/outputs/ui-captures/ble-advertisement-journal.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun storageFailureIsVisibleAndInProgressExportCannotRepeat() {
        compose.setContent { AppRfTheme { Surface {
            BleJournalPanel(BleJournalStatus("session", 8, 3, 0, 5, "저장 공간 부족"), false, true, null) {}
        } } }
        compose.onNodeWithText("기록 누락 가능 · 저장 공간 부족").assertIsDisplayed()
        compose.onNodeWithText("저장 대기 0회 · 저장 실패 5회").assertExists()
        compose.onNodeWithText("광고 기록 내보내는 중").assertIsNotEnabled()
    }
}
