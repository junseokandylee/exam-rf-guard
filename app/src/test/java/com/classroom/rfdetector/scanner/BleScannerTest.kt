package com.classroom.rfdetector.scanner

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import com.classroom.rfdetector.core.DeviceTracker
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.BleAdvertisementEvent
import com.classroom.rfdetector.core.BleRecordDisposition
import com.classroom.rfdetector.core.BeaconFormat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
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
        val events = mutableListOf<BleAdvertisementEvent>()
        val deliveryOrder = mutableListOf<String>()
        scanner.onAdvertisement = { events.add(it); deliveryOrder.add("raw") }
        assertTrue(scanner.start { observations.add(it); deliveryOrder.add("observation") })
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
        assertEquals(listOf("raw", "observation"), deliveryOrder)
        assertEquals("020106020a0005ff4c0001020c09416972506f64732050726f00", events.single().rawRecordHex)
        assertEquals(BleRecordDisposition.ACCEPTED, events.single().disposition)
        assertEquals(observation.timestampMs, events.single().observedAtMs)
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
        val events = mutableListOf<BleAdvertisementEvent>()
        scanner.onAdvertisement = { events.add(it) }
        scanner.start { observations.add(it) }
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        val capturedAtNanos = android.os.SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
        val result = ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), null, -80, capturedAtNanos)
        callback.onBatchScanResults(mutableListOf(result))
        assertEquals(1, observations.size)
        assertEquals(1, events.size)
        assertTrue(System.currentTimeMillis() - observations.single().timestampMs in 2_000..2_500)
        scanner.stop()
        callback.onScanResult(1, result)
        assertEquals(1, observations.size)
        scanner.start { observations.add(it) }
        callback.onBatchScanResults(mutableListOf(result))
        assertEquals(1, observations.size)
        assertEquals(1, events.size)
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
        assertEquals(2, scanner.lastErrorCode)
        assertEquals(5_000L, scanner.retryDelayMs)
        failedCallback.onScanResult(1, ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), null, -60, SystemClock.elapsedRealtimeNanos()))
        assertTrue(observations.isEmpty())
        assertNull(scanner.lastReceivedAtMs)

        assertTrue(scanner.start { observations.add(it) })
        assertTrue(scanner.active)
        assertNull(scanner.lastError)
        assertNull(scanner.lastErrorCode)
        assertNull(scanner.retryDelayMs)
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
        val events = mutableListOf<BleAdvertisementEvent>()
        scanner.onAdvertisement = { events.add(it) }
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
        assertEquals(listOf(BleRecordDisposition.BEFORE_SCAN, BleRecordDisposition.INVALID_TIMESTAMP,
            BleRecordDisposition.INVALID_TIMESTAMP, BleRecordDisposition.INVALID_TIMESTAMP), events.map { it.disposition })
        assertTrue(events.all { it.observedAtMs == null })
        assertEquals(listOf(oldTime, SystemClock.elapsedRealtimeNanos() + 1_000_000_000, 0L, -1L), events.map { it.timestampNanos })
        assertNull(scanner.lastReceivedAtMs)
        assertTrue(scanner.active)

        callback.onScanResult(1, ScanResult(device, null, -60, SystemClock.elapsedRealtimeNanos()))
        assertEquals(1, observations.size)
        assertEquals(5, events.size)
        assertEquals(BleRecordDisposition.ACCEPTED, events.last().disposition)
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

    @Test fun allModesRequestEveryAdvertisementWithoutFiltersOrBatchDelay() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        for (extended in listOf(false, true)) {
            shadowOf(adapter).setIsLeExtendedAdvertisingSupported(extended)
            assertTrue(scanner.start {})
            val scan = shadowOf(adapter.bluetoothLeScanner).activeScans.single()
            val settings = scan.scanSettings()!!
            assertTrue(scan.scanFilters().isNullOrEmpty())
            assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, settings.scanMode)
            assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, settings.callbackType)
            assertEquals(0L, settings.reportDelayMillis)
            assertEquals(!extended, settings.legacy)
            if (extended) assertEquals(ScanSettings.PHY_LE_ALL_SUPPORTED, settings.phy)
            scanner.stop()
        }
    }

    @Test fun unnamedWeakNonConnectableAdvertisementsAndMissingRecordsAreDelivered() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setIsLeExtendedAdvertisingSupported(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        assertTrue(scanner.start { observations.add(it) })
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val device = adapter.getRemoteDevice("F0:11:22:33:44:55")
        // No connectable bit, name, manufacturer, service, or scan record is required.
        val extended = ScanResult(device, 0, BluetoothDevice.PHY_LE_1M, 0, 0, 127, -127,
            0, null, SystemClock.elapsedRealtimeNanos())
        val legacy = ScanResult(device, 16, BluetoothDevice.PHY_LE_1M, 0, 255, 127, -100,
            0, null, SystemClock.elapsedRealtimeNanos())
        callback.onBatchScanResults(mutableListOf(extended, legacy))
        assertEquals(2, observations.size)
        assertEquals(listOf(-127, -100), observations.map { it.rssi })
        assertTrue(observations.all { it.name == null && it.manufacturerIds.isEmpty() })
        assertTrue(observations.all { it.radioMetadata!!.ble!!.serviceUuids.isEmpty() })
        assertTrue(observations.all { it.radioMetadata!!.ble!!.connectable == false })
        assertEquals(listOf(false, true), observations.map { it.radioMetadata!!.ble!!.legacy })
        scanner.stop()
    }

    @Test fun repeatedAdvertisementsOfOneAddressReachHistoryFromSingleAndBatchCallbacks() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        val tracker = DeviceTracker()
        scanner.start { observations.add(it); tracker.update(it) }
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val first = ScanResult(device, null, -95, SystemClock.elapsedRealtimeNanos())
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        val second = ScanResult(device, null, -92, SystemClock.elapsedRealtimeNanos())
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        val third = ScanResult(device, null, -93, SystemClock.elapsedRealtimeNanos())
        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, first)
        callback.onBatchScanResults(mutableListOf(second, third))
        assertEquals(3, observations.size)
        assertEquals(3, tracker.snapshot(System.currentTimeMillis()).single().observationCount)
        assertTrue(observations.zipWithNext().all { (before, after) -> before.timestampMs <= after.timestampMs })
        scanner.stop()
    }

    @Test fun stoppingFromObservationConsumerPreventsRemainingBatchFromLeakingIntoNextSession() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val firstSession = mutableListOf<RfObservation>()
        val nextSession = mutableListOf<RfObservation>()
        scanner.start {
            firstSession.add(it)
            scanner.start { nextSession.add(it) }
        }
        val oldCallback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val packet = ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), null, -80, SystemClock.elapsedRealtimeNanos())
        oldCallback.onBatchScanResults(mutableListOf(packet, packet, packet))
        assertEquals(1, firstSession.size)
        assertTrue(nextSession.isEmpty())
        assertTrue(scanner.active)
        shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single().onScanResult(1, packet)
        assertEquals(1, nextSession.size)
        scanner.stop()
    }

    @Test fun transientAndThrottlingFailuresDoNotPretendLegacyFallbackWillSolveThem() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setIsLeExtendedAdvertisingSupported(true)
        val scanner = BleScanner(context)
        val errorMessages = mutableListOf<String>()
        scanner.onError = { errorMessages.add(it) }
        val retryDelays = mapOf(2 to 5_000L, 3 to 5_000L, 5 to 5_000L, 6 to 30_000L)
        for ((code, retryDelay) in retryDelays) {
            assertTrue(scanner.start {})
            val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
            callback.onScanFailed(code)
            assertFalse(scanner.active)
            assertEquals(code, scanner.lastErrorCode)
            assertEquals(retryDelay, scanner.retryDelayMs)
            assertTrue(scanner.lastError!!.contains("($code)"))
            assertNull(scanner.scanModeNotice)
            assertTrue(shadowOf(adapter.bluetoothLeScanner).scanCallbacks.isEmpty())
            callback.onScanFailed(4)
            assertFalse(scanner.active)
            assertEquals(code, scanner.lastErrorCode)
        }
        assertEquals(retryDelays.size, errorMessages.size)
        scanner.stop()
    }

    @Test fun fallbackRetainsSessionTimeBoundaryAndDoesNotResetAlreadyReceivedHealth() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setIsLeExtendedAdvertisingSupported(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        scanner.start { observations.add(it) }
        val extended = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        val packet = ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), null, -90, SystemClock.elapsedRealtimeNanos())
        extended.onScanResult(1, packet)
        val lastReceived = scanner.lastReceivedAtMs
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        extended.onScanFailed(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
        assertEquals(lastReceived, scanner.lastReceivedAtMs)
        val fallback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        fallback.onBatchScanResults(mutableListOf(packet))
        assertEquals(2, observations.size)
        assertNull(scanner.lastErrorCode)
        assertNull(scanner.retryDelayMs)
        scanner.stop()
    }

    @Test fun rawJournalKeepsNullEmptyAndTruncatedScanRecordsWithoutInventingBytes() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val events = mutableListOf<BleAdvertisementEvent>()
        val observations = mutableListOf<RfObservation>()
        scanner.onAdvertisement = { events.add(it) }
        scanner.start { observations.add(it) }
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val emptyRecord = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java,
            "parseFromBytes", ClassParameter.from(ByteArray::class.java, byteArrayOf()))
        val rawBytes = byteArrayOf(2, 1, 6, 0, -1, -128)
        val truncatedRecord = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java,
            "parseFromBytes", ClassParameter.from(ByteArray::class.java, rawBytes))
        callback.onBatchScanResults(mutableListOf(
            ScanResult(device, null, -80, SystemClock.elapsedRealtimeNanos()),
            ScanResult(device, emptyRecord, -81, SystemClock.elapsedRealtimeNanos()),
            ScanResult(device, ScanResult.DATA_TRUNCATED shl 5, BluetoothDevice.PHY_LE_CODED,
                BluetoothDevice.PHY_LE_2M, 7, 127, -82, 0, truncatedRecord, SystemClock.elapsedRealtimeNanos()),
        ))
        assertEquals(3, events.size)
        assertEquals(3, observations.size)
        assertNull(events[0].rawRecordHex)
        assertEquals("", events[1].rawRecordHex)
        assertEquals("02010600ff80", events[2].rawRecordHex)
        assertEquals(ScanResult.DATA_TRUNCATED, events[2].dataStatus)
        assertEquals(7, events[2].advertisingSid)
        assertEquals(BluetoothDevice.PHY_LE_CODED, events[2].primaryPhy)
        assertEquals(BluetoothDevice.PHY_LE_2M, events[2].secondaryPhy)
        assertTrue(events.all { it.disposition == BleRecordDisposition.ACCEPTED })
        scanner.stop()
    }

    @Test
    @Config(shadows = [ThrowingMetadataResult::class])
    fun oneMetadataDecodeFailureIsRecordedAndDoesNotDiscardRemainingBatch() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val events = mutableListOf<BleAdvertisementEvent>()
        val observations = mutableListOf<RfObservation>()
        scanner.onAdvertisement = { events.add(it) }
        scanner.start { observations.add(it) }
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val record = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java,
            "parseFromBytes", ClassParameter.from(ByteArray::class.java, byteArrayOf(2, 1, 6, 0)))
        callback.onBatchScanResults(mutableListOf(
            ScanResult(device, record, -126, SystemClock.elapsedRealtimeNanos()),
            ScanResult(device, record, -80, SystemClock.elapsedRealtimeNanos()),
        ))
        assertEquals(listOf(BleRecordDisposition.DECODE_ERROR, BleRecordDisposition.ACCEPTED), events.map { it.disposition })
        assertEquals("02010600", events.first().rawRecordHex)
        assertEquals(device.address, events.first().address)
        assertNull(events.first().observedAtMs)
        assertEquals(-80, observations.single().rssi)
        assertTrue(scanner.active)
        scanner.stop()
    }

    @Test fun stoppingFromRawConsumerPreventsObservationAndRemainingBatchFromEnteringClosedSession() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val events = mutableListOf<BleAdvertisementEvent>()
        val observations = mutableListOf<RfObservation>()
        scanner.onAdvertisement = { events.add(it); scanner.stop() }
        scanner.start { observations.add(it) }
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val packet = ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), null, -80, SystemClock.elapsedRealtimeNanos())
        callback.onBatchScanResults(mutableListOf(packet, packet))
        assertEquals(1, events.size)
        assertTrue(observations.isEmpty())
        assertFalse(scanner.active)
        assertNull(scanner.lastReceivedAtMs)
    }

    @Test fun manufacturerAndServiceBeaconFramesAreHintsWithoutFilteringOrDistanceCalibration() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        scanner.start { observations.add(it) }
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val iBeaconPayload = byteArrayOf(2, 21) + ByteArray(20) + byteArrayOf(-59)
        val uidPayload = byteArrayOf(0, -20) + ByteArray(18)
        val bytes = listOf(
            byteArrayOf(26, -1, 76, 0) + iBeaconPayload,
            byteArrayOf(23, 22, -86, -2) + uidPayload,
        )
        bytes.forEachIndexed { index, raw ->
            val record = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java,
                "parseFromBytes", ClassParameter.from(ByteArray::class.java, raw))
            callback.onScanResult(1, ScanResult(adapter.getRemoteDevice("00:11:22:33:44:5$index"),
                record, -100, SystemClock.elapsedRealtimeNanos()))
        }
        assertEquals(listOf(listOf(BeaconFormat.IBEACON_COMPATIBLE), listOf(BeaconFormat.EDDYSTONE_UID)),
            observations.map { it.radioMetadata!!.ble!!.beaconFormats })
        assertTrue(observations.all { it.name == null && it.txPower == null })
        scanner.stop()
    }

    @Test fun mergedManifestDoesNotOptIntoBeaconFilteringWithNeverForLocation() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val scanPermissionIndex = packageInfo.requestedPermissions!!.indexOf(Manifest.permission.BLUETOOTH_SCAN)
        assertTrue("Merged app must request Bluetooth scan permission", scanPermissionIndex >= 0)
        assertEquals(0, packageInfo.requestedPermissionsFlags!![scanPermissionIndex] and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION)
        assertTrue(packageInfo.requestedPermissions!!.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test fun unavailableRssiKeepsAnonymousAdvertisementAndPresenceWithoutInventingDistance() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val events = mutableListOf<BleAdvertisementEvent>()
        val observations = mutableListOf<RfObservation>()
        val tracker = DeviceTracker()
        scanner.onAdvertisement = { events.add(it) }
        scanner.start { observations.add(it); tracker.update(it) }
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
            ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), null, 127, SystemClock.elapsedRealtimeNanos()))
        assertEquals(127, events.single().rssi)
        assertEquals(BleRecordDisposition.ACCEPTED, events.single().disposition)
        assertNull(observations.single().name)
        assertNull(observations.single().rssi)
        val device = tracker.snapshot(System.currentTimeMillis(), true).single()
        assertEquals(1, device.observationCount)
        assertNull(device.rssiEma)
        assertNull(device.distanceM)
        scanner.stop()
    }

    @Implements(ScanResult::class)
    class ThrowingMetadataResult {
        @RealObject private lateinit var result: ScanResult
        @Implementation fun getPrimaryPhy(): Int {
            if (result.rssi == -126) throw IllegalStateException("Injected metadata decode failure")
            return BluetoothDevice.PHY_LE_1M
        }
    }
}
