package com.classroom.rfdetector

import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import com.classroom.rfdetector.core.BleAdvertisementJournal
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleJournalIntegrationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun anonymousBurstAndInvalidTimestampAreSavedSeparatelyAndSurviveReopening() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setIsLeExtendedAdvertisingSupported(false)
        val vm = AppViewModel(app)
        try {
            vm.setSim(false)
            vm.startScan()
            val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
            val radio = adapter.getRemoteDevice("00:11:22:33:44:55")
            val observed = SystemClock.elapsedRealtimeNanos()
            callback.onBatchScanResults(MutableList(300) { ScanResult(radio, null, -96, observed) })
            callback.onScanResult(1, ScanResult(radio, null, -96, 0))
            assertEquals(300, vm.tracker.snapshot(System.currentTimeMillis(), true).single().observationCount)
            assertEquals(301L, vm.bleJournalStatus.value.received)
            vm.stopScan()
            callback.onScanResult(1, ScanResult(radio, null, -96, observed))

            val output = ByteArrayOutputStream()
            val uri = Uri.parse("content://rf-test/ble-journal.jsonl")
            shadowOf(app.contentResolver).registerOutputStream(uri, output)
            vm.exportBleJournal(uri)
            await { !vm.bleJournalExportBusy.value }
            assertEquals("BLE 광고 기록 파일을 저장했습니다", vm.bleJournalExportMessage.value)
            assertEquals(301L, vm.bleJournalStatus.value.written)
            assertEquals(0L, vm.bleJournalStatus.value.pending)
            assertEquals(0L, vm.bleJournalStatus.value.failed)
            val rows = output.toString("UTF-8").lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
            val ads = rows.filter { it.getString("type") == "advertisement" }
            assertEquals(301, ads.size)
            assertEquals(300, ads.count { it.getString("disposition") == "ACCEPTED" })
            assertEquals(1, ads.count { it.getString("disposition") == "INVALID_TIMESTAMP" })
            assertTrue(ads.all { it.isNull("raw_record_hex") })
            assertTrue(rows.any { it.getString("type") == "scan_state" && it.getString("state") == "BLE" })

            BleAdvertisementJournal(app).use { restored ->
                val reopened = ByteArrayOutputStream()
                restored.exportTo(reopened)
                assertEquals(301, reopened.toString("UTF-8").lineSequence().filter { it.isNotBlank() }
                    .map(::JSONObject).count { it.getString("type") == "advertisement" })
            }
        } finally { vm.shutdown() }
    }

    @Test fun failedBleRetryLeavesStateHistoryAndSimulationDoesNotCreateRealAdvertisementRows() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setIsLeExtendedAdvertisingSupported(false)
        val vm = AppViewModel(app)
        try {
            vm.setSim(false)
            vm.startScan()
            shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single().onScanFailed(2)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            assertTrue(vm.ble.active)
            vm.stopScan()
            vm.setSim(true)
            vm.startScan()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            vm.stopScan()
            val output = ByteArrayOutputStream()
            val uri = Uri.parse("content://rf-test/ble-recovery.jsonl")
            shadowOf(app.contentResolver).registerOutputStream(uri, output)
            vm.exportBleJournal(uri)
            await { !vm.bleJournalExportBusy.value }
            val rows = output.toString("UTF-8").lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
            assertEquals(1, rows.count { it.getString("type") == "session" })
            assertEquals(0, rows.count { it.getString("type") == "advertisement" })
            val states = rows.filter { it.getString("type") == "scan_state" }.map { it.getString("state") }
            assertTrue(states.contains("RETRYING"))
            assertEquals(2, states.count { it == "BLE" })
            assertTrue(states.any { it.startsWith("BLE_ERROR:") })
        } finally { vm.shutdown() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("BLE journal operation did not complete")
    }
}
