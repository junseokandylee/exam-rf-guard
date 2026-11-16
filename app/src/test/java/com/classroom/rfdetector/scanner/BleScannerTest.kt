package com.classroom.rfdetector.scanner

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import com.classroom.rfdetector.core.DeviceTracker
import com.classroom.rfdetector.core.RfObservation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleScannerTest {
    @Before fun clockHasStarted() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val adapter = (RuntimeEnvironment.getApplication().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setIsLeExtendedAdvertisingSupported(false)
    }

    @Test fun advertisementPreservesCompanyAndNameWithoutTreatingRadiatedPowerAsCalibration() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        assertTrue(scanner.start { observations.add(it) })
        val name = "AirPods Pro".toByteArray()
        val bytes = byteArrayOf(2, 1, 6, 2, 10, 0, 5, -1, 76, 0, 1, 2) +
            byteArrayOf((name.size + 1).toByte(), 9) + name + byteArrayOf(0)
        val record = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java, "parseFromBytes", ClassParameter.from(ByteArray::class.java, bytes))
        assertEquals(0, record.txPowerLevel)
        val result = ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), record, -59, SystemClock.elapsedRealtimeNanos())
        shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single().onScanResult(1, result)
        val observation = observations.single()
        assertEquals("AirPods Pro", observation.name)
        assertEquals(setOf(76), observation.manufacturerIds)
        assertNull(observation.txPower)
        val tracker = DeviceTracker()
        tracker.update(observation)
        assertEquals(1.0, tracker.snapshot(observation.timestampMs).single().distanceM!!, 0.001)
        scanner.stop()
        assertTrue(shadowOf(adapter.bluetoothLeScanner).scanCallbacks.isEmpty())
    }

    @Test fun batchResultsKeepCaptureTimeAndStoppedCallbacksAreIgnored() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(10))
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        scanner.start { observations.add(it) }
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        val capturedAtNanos = android.os.SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
        val result = ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), null, -80, capturedAtNanos)
        callback.onBatchScanResults(mutableListOf(result))
        assertEquals(1, observations.size)
        assertTrue(System.currentTimeMillis() - observations.single().timestampMs in 2_000..2_500)
        scanner.stop()
        callback.onScanResult(1, result)
        assertEquals(1, observations.size)
        scanner.start { observations.add(it) }
        callback.onBatchScanResults(mutableListOf(result))
        assertEquals(1, observations.size)
        scanner.stop()
    }

    @Test fun failedScanIsNotReportedActiveAndRestartHasFreshHealth() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        scanner.start { observations.add(it) }
        assertTrue(scanner.active)
        assertNull(scanner.lastReceivedAtMs)
        val failedCallback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        failedCallback.onScanFailed(2)
        assertFalse(scanner.active)
        assertTrue(scanner.lastError!!.contains("(2)"))
        failedCallback.onScanResult(1, ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), null, -60, SystemClock.elapsedRealtimeNanos()))
        assertTrue(observations.isEmpty())
        assertNull(scanner.lastReceivedAtMs)

        assertTrue(scanner.start { observations.add(it) })
        assertTrue(scanner.active)
        assertNull(scanner.lastError)
        failedCallback.onScanFailed(3)
        assertTrue(scanner.active)
        assertNull(scanner.lastError)
        shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single().onScanResult(
            1, ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), null, -60, SystemClock.elapsedRealtimeNanos()))
        assertEquals(observations.single().timestampMs, scanner.lastReceivedAtMs)
        scanner.stop()
        assertFalse(scanner.active)
    }

    @Test fun bluetoothDisabledReportsReasonWithoutPretendingToScan() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(false)
        val scanner = BleScanner(context)
        assertFalse(scanner.start { fail("Disabled bluetooth cannot receive") })
        assertFalse(scanner.active)
        assertNotNull(scanner.lastError)
        assertNull(scanner.lastReceivedAtMs)
    }

    @Test fun preSessionFutureAndMissingTimestampsDoNotBecomeFreshObservations() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        val oldTime = SystemClock.elapsedRealtimeNanos()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        scanner.start { observations.add(it) }
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        callback.onBatchScanResults(mutableListOf(
            ScanResult(device, null, -60, oldTime),
            ScanResult(device, null, -60, SystemClock.elapsedRealtimeNanos() + 1_000_000_000),
            ScanResult(device, null, -60, 0),
            ScanResult(device, null, -60, -1),
        ))
        assertTrue(observations.isEmpty())
        assertNull(scanner.lastReceivedAtMs)
        assertTrue(scanner.active)

        callback.onScanResult(1, ScanResult(device, null, -60, SystemClock.elapsedRealtimeNanos()))
        assertEquals(1, observations.size)
        assertEquals(observations.single().timestampMs, scanner.lastReceivedAtMs)
        scanner.stop()
    }

    @Test fun delayedCurrentSessionPacketKeepsItsTimeAndCannotRegressHealthFreshness() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        scanner.start { observations.add(it) }
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val earlierTime = SystemClock.elapsedRealtimeNanos()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        callback.onScanResult(1, ScanResult(device, null, -60, SystemClock.elapsedRealtimeNanos()))
        val latestTime = scanner.lastReceivedAtMs
        callback.onScanResult(1, ScanResult(device, null, -80, earlierTime))
        assertEquals(2, observations.size)
        assertTrue(System.currentTimeMillis() - observations.last().timestampMs in 20_000..20_500)
        assertEquals(latestTime, scanner.lastReceivedAtMs)
        scanner.stop()
    }

    @Test fun supportedExtendedScanRequestsAllSupportedPhysAndPreservesAdvertisementMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setIsLeExtendedAdvertisingSupported(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        assertTrue(scanner.start { observations.add(it) })
        assertTrue(scanner.usesExtendedAdvertisements)
        assertNull(scanner.scanModeNotice)
        val scan = shadowOf(adapter.bluetoothLeScanner).activeScans.single()
        assertFalse(scan.scanSettings()!!.legacy)
        assertEquals(ScanSettings.PHY_LE_ALL_SUPPORTED, scan.scanSettings()!!.phy)
        val bytes = byteArrayOf(3, 3, 15, 24, 2, 10, 0, 0) // Advertised Battery Service and uncalibrated Tx power.
        val record = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java, "parseFromBytes", ClassParameter.from(ByteArray::class.java, bytes))
        val result = ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), 1,
            BluetoothDevice.PHY_LE_CODED, BluetoothDevice.PHY_LE_2M, 1, 0, -70, 0, record, SystemClock.elapsedRealtimeNanos())
        scan.scanCallback()!!.onScanResult(1, result)
        val observation = observations.single()
        val metadata = observation.radioMetadata!!.ble!!
        assertEquals(listOf("0000180f-0000-1000-8000-00805f9b34fb"), metadata.serviceUuids)
        assertEquals(true, metadata.connectable)
        assertEquals(false, metadata.legacy)
        assertEquals(BluetoothDevice.PHY_LE_CODED, metadata.primaryPhy)
        assertEquals(BluetoothDevice.PHY_LE_2M, metadata.secondaryPhy)
        assertNull(observation.txPower)
        scanner.stop()
        assertFalse(scanner.usesExtendedAdvertisements)
    }

    @Test fun unsupportedExtendedHardwareUsesLegacySettingsWithVisibleReason() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        assertTrue(scanner.start {})
        assertFalse(scanner.usesExtendedAdvertisements)
        assertTrue(scanner.scanModeNotice!!.contains("미지원"))
        assertTrue(shadowOf(adapter.bluetoothLeScanner).activeScans.single().scanSettings()!!.legacy)
        assertNull(scanner.lastError)
        scanner.stop()
    }

    @Test fun extendedFailureRetriesLegacyOnceAndIgnoresOldCallbacksWithoutHidingFallback() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setIsLeExtendedAdvertisingSupported(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        assertTrue(scanner.start { observations.add(it) })
        val oldCallback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        oldCallback.onScanFailed(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
        assertTrue(scanner.active)
        assertFalse(scanner.usesExtendedAdvertisements)
        assertNull(scanner.lastError)
        assertTrue(scanner.scanModeNotice!!.contains("실패 (4)"))
        assertTrue(scanner.scanModeNotice!!.contains("기본 BLE 광고"))
        val fallback = shadowOf(adapter.bluetoothLeScanner).activeScans.single()
        assertTrue(fallback.scanSettings()!!.legacy)
        assertNotSame(oldCallback, fallback.scanCallback())
        val packet = ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), null, -60, SystemClock.elapsedRealtimeNanos())
        oldCallback.onScanResult(1, packet)
        oldCallback.onScanFailed(3)
        assertTrue(observations.isEmpty())
        fallback.scanCallback()!!.onScanResult(1, packet)
        assertEquals(1, observations.size)
        assertTrue(scanner.scanModeNotice!!.contains("실패"))
        fallback.scanCallback()!!.onScanFailed(2)
        assertFalse(scanner.active)
        assertTrue(scanner.lastError!!.contains("(2)"))
        assertTrue(shadowOf(adapter.bluetoothLeScanner).scanCallbacks.isEmpty())
        assertTrue(scanner.start { observations.add(it) })
        assertTrue(scanner.usesExtendedAdvertisements)
        assertNull(scanner.scanModeNotice)
        assertNull(scanner.lastError)
        scanner.stop()
    }
}
