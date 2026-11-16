package com.classroom.rfdetector.scanner

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.ScanSource
import org.junit.After
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ClassicBluetoothScannerTest.ControlledBluetoothAdapter::class])
class ClassicBluetoothScannerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val adapterShadow = Shadow.extract<ControlledBluetoothAdapter>(adapter)
    private val scanner = ClassicBluetoothScanner(app)
    private val observations = mutableListOf<RfObservation>()
    private var completions = 0

    @Before fun enableBluetooth() { adapterShadow.setEnabled(true) }
    @After fun close() { scanner.stop() }

    private fun start() = scanner.start({ observations.add(it) }, { completions++ })
    private fun send(intent: Intent) {
        app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun started() = send(Intent(BluetoothAdapter.ACTION_DISCOVERY_STARTED))
    private fun finish() {
        adapter.cancelDiscovery() // Platform state changes before DISCOVERY_FINISHED is delivered.
        send(Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
    }
    private fun found(suffix: String = "55", name: String? = "Headphones", rssi: Short? = -62) =
        Intent(BluetoothDevice.ACTION_FOUND).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, adapter.getRemoteDevice("00:11:22:33:44:$suffix"))
            name?.let { putExtra(BluetoothDevice.EXTRA_NAME, it) }
            rssi?.let { putExtra(BluetoothDevice.EXTRA_RSSI, it) }
        }

    @Test fun foundDeviceKeepsDiscoveryFactsWithoutPairingOrInventedManufacturer() {
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        shadowOf(device).setName("Previously cached name")
        val deviceClass = ReflectionHelpers.callConstructor(
            BluetoothClass::class.java, ClassParameter.from(Int::class.javaPrimitiveType!!, 0x0404),
        )
        assertTrue(scanner.hasHardware())
        assertTrue(scanner.isUsable())
        assertTrue(start())
        started()
        val before = System.currentTimeMillis()
        send(found().putExtra(BluetoothDevice.EXTRA_CLASS, deviceClass))
        val after = System.currentTimeMillis()
        val observation = observations.single()
        assertEquals(ScanSource.CLASSIC, observation.source)
        assertEquals("00:11:22:33:44:55", observation.key)
        assertEquals("Headphones", observation.name)
        assertEquals(-62, observation.rssi)
        assertEquals(0x0404, observation.radioMetadata?.classicDeviceClass)
        assertTrue(observation.timestampMs in before..after)
        assertEquals(observation.timestampMs, scanner.lastReceivedAtMs)
        assertNull(observation.txPower)
        assertNull(observation.frequencyMhz)
        assertTrue(observation.manufacturerIds.isEmpty())
        assertFalse(observation.hidden)
        assertEquals(BluetoothDevice.BOND_NONE, device.bondState)
        assertTrue(shadowOf(device).bluetoothGatts.isEmpty())
        assertEquals(0, shadowOf(device).fetchUuidsWithSdpCount)
        assertEquals(0, adapterShadow.bondedReads)
    }

    @Test fun duplicateAddressCountsOncePerRoundAndCanReappearInTheNextRound() {
        assertTrue(start())
        started()
        send(found())
        send(found(name = "Repeated result", rssi = -40))
        assertEquals(1, observations.size)
        finish()
        assertEquals(1, completions)
        assertFalse(scanner.active)
        assertTrue(start())
        assertNull(scanner.lastReceivedAtMs)
        started()
        send(found(rssi = -65))
        assertEquals(2, observations.size)
        assertEquals(-65, observations.last().rssi)
    }

    @Test fun resultsAndOldFinishBeforeStartedCannotContaminateAnAcceptedRound() {
        assertTrue(start())
        send(found())
        send(Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
        assertTrue(observations.isEmpty())
        assertTrue(scanner.active)
        assertEquals(0, completions)
        started()
        // A late finish must also be ignored after STARTED while the adapter is still discovering.
        send(Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
        assertTrue(scanner.active)
        send(found())
        assertEquals(1, observations.size)
        finish()
        assertEquals(1, completions)
    }

    @Test fun queuedFoundAndFinishedForAnOldReceiverAreIgnoredAcrossStopAndRestart() {
        assertTrue(start())
        started()
        app.sendBroadcast(found())
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
        scanner.stop()
        assertFalse(adapter.isDiscovering)
        assertTrue(start())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(observations.isEmpty())
        assertTrue(scanner.active)
        assertEquals(0, completions)
        started()
        send(found(suffix = "66"))
        assertEquals(listOf("00:11:22:33:44:66"), observations.map { it.key })
        scanner.stop()
        send(found(suffix = "77"))
        assertEquals(1, observations.size)
        assertEquals(0, completions)
    }

    @Test fun completionCleansUpBeforeCallbackAndRepeatedFinishedCannotStopCallbackRestart() {
        assertTrue(scanner.start({ observations.add(it) }) {
            completions++
            assertFalse(scanner.active)
            assertTrue(start())
        })
        started()
        finish()
        assertEquals(1, completions)
        assertTrue(scanner.active)
        started()
        send(Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
        assertEquals(1, completions)
        assertTrue(scanner.active)
        send(found())
        assertEquals(1, observations.size)
    }

    @Test fun existingDiscoveryIsNeitherAdoptedNorCancelled() {
        assertTrue(adapter.startDiscovery())
        val callsBefore = adapterShadow.startCalls
        assertFalse(start())
        assertEquals(callsBefore, adapterShadow.startCalls)
        assertTrue(scanner.lastError!!.contains("다른 Bluetooth 검색"))
        assertFalse(scanner.active)
        started()
        send(found())
        assertTrue(observations.isEmpty())
        scanner.stop()
        assertTrue(adapter.isDiscovering)
        assertEquals(0, adapterShadow.cancelCalls)
        assertEquals(0, completions)
    }

    @Test fun refusedStartReleasesReceiverAndCannotCancelAnotherAppsLaterDiscovery() {
        adapterShadow.acceptStart = false
        assertFalse(start())
        assertNotNull(scanner.lastError)
        assertFalse(scanner.active)
        adapterShadow.acceptStart = true
        assertTrue(adapter.startDiscovery())
        started()
        send(found())
        scanner.stop()
        assertTrue(adapter.isDiscovering)
        assertTrue(observations.isEmpty())
        assertEquals(0, adapterShadow.cancelCalls)
        assertEquals(0, completions)
    }

    @Test fun deniedStartPermissionIsReportedAndASecondStartHasFreshHealth() {
        adapterShadow.denyStart = true
        assertFalse(start())
        assertFalse(scanner.active)
        assertTrue(scanner.lastError!!.contains("권한"))
        assertEquals(0, adapterShadow.cancelCalls)
        assertNull(scanner.lastReceivedAtMs)
        adapterShadow.denyStart = false
        assertTrue(start())
        assertNull(scanner.lastError)
        assertNull(scanner.lastReceivedAtMs)
        started()
        send(found())
        assertEquals(1, observations.size)
    }

    @Test fun missingAndInvalidRssiStayUnknownAndCachedNamesAreNotDiscoveryEvidence() {
        assertTrue(start())
        started()
        shadowOf(adapter.getRemoteDevice("00:11:22:33:44:55")).setName("Cached AirPods")
        send(found(name = null, rssi = null))
        send(found("56", "  ", Short.MIN_VALUE))
        send(found("57", rssi = 127))
        send(found("58", rssi = -128))
        send(found("59", rssi = 21))
        send(found("60", rssi = null).putExtra(BluetoothDevice.EXTRA_RSSI, -50)) // Must be a short.
        assertEquals(6, observations.size)
        assertTrue(observations.all { it.rssi == null })
        assertNull(observations[0].name)
        assertNull(observations[1].name)
        send(found("61", rssi = -127))
        send(found("62", rssi = 20))
        assertEquals(listOf(-127, 20), observations.takeLast(2).map { it.rssi })
    }

    @Test fun missingDeviceIsIgnoredAndBluetoothTurningOffCompletesExactlyOnce() {
        assertTrue(start())
        started()
        send(Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_NAME, "No address"))
        assertTrue(observations.isEmpty())
        assertNull(scanner.lastReceivedAtMs)
        send(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_TURNING_OFF))
        assertFalse(scanner.active)
        assertEquals(1, completions)
        assertNotNull(scanner.lastError)
        send(Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
        send(found())
        assertEquals(1, completions)
        assertTrue(observations.isEmpty())
    }

    @Test fun disabledBluetoothDoesNotRegisterOrStartDiscovery() {
        adapterShadow.setEnabled(false)
        assertFalse(scanner.isUsable())
        assertFalse(start())
        assertFalse(scanner.active)
        assertEquals(0, adapterShadow.startCalls)
        assertNotNull(scanner.lastError)
        assertEquals(0, completions)
    }

    @Test fun failedCancellationKeepsThePlatformBusyWithoutRepeatedlyCancellingAnUnverifiableRound() {
        assertTrue(start())
        started()
        adapterShadow.acceptCancel = false
        scanner.stop()
        assertFalse(scanner.active)
        assertTrue(adapter.isDiscovering)
        assertFalse(scanner.isDiscoveryIdle())
        assertTrue(scanner.lastError!!.contains("종료"))
        send(found())
        assertTrue(observations.isEmpty())
        adapterShadow.acceptCancel = true
        scanner.stop()
        assertFalse(scanner.isDiscoveryIdle())
        assertEquals(1, adapterShadow.cancelCalls)
        adapterShadow.completePlatformDiscovery()
        assertTrue(scanner.isDiscoveryIdle())
        assertFalse(adapter.isDiscovering)
        assertEquals(1, adapterShadow.cancelCalls)
        assertEquals(0, completions)
    }

    @Test fun acceptedCancellationStillNeedsActualSystemCompletionBeforeBeingIdle() {
        assertTrue(start())
        started()
        adapterShadow.finishCancelImmediately = false
        scanner.stop()
        assertFalse(scanner.isDiscoveryIdle())
        assertTrue(adapter.isDiscovering)
        scanner.stop()
        assertEquals(1, adapterShadow.cancelCalls)
        adapterShadow.completePlatformDiscovery()
        assertTrue(scanner.isDiscoveryIdle())
        assertEquals(1, adapterShadow.cancelCalls)
        assertEquals(0, completions)
    }

    @Test fun unknownDiscoveryStateAfterPermissionLossDoesNotBecomeAnIdleAssertion() {
        adapterShadow.denyDiscoveryState = true
        assertFalse(scanner.isDiscoveryIdle())
        assertTrue(scanner.lastError!!.contains("권한"))
        assertEquals(0, adapterShadow.cancelCalls)
        adapterShadow.denyDiscoveryState = false
        assertTrue(scanner.isDiscoveryIdle())
    }

    @Implements(BluetoothAdapter::class)
    class ControlledBluetoothAdapter : ShadowBluetoothAdapter() {
        var acceptStart = true
        var denyStart = false
        var startCalls = 0
        var cancelCalls = 0
        var bondedReads = 0
        var acceptCancel = true
        var finishCancelImmediately = true
        var denyDiscoveryState = false

        @Implementation public override fun startDiscovery(): Boolean {
            startCalls++
            if (denyStart) throw SecurityException("Test scan permission revoked")
            return acceptStart && super.startDiscovery()
        }

        @Implementation public override fun cancelDiscovery(): Boolean {
            cancelCalls++
            if (!acceptCancel) return false
            return if (finishCancelImmediately) super.cancelDiscovery() else true
        }

        @Implementation public override fun isDiscovering(): Boolean {
            if (denyDiscoveryState) throw SecurityException("Discovery state permission revoked")
            return super.isDiscovering()
        }

        fun completePlatformDiscovery() { super.cancelDiscovery() }

        @Implementation public override fun getBondedDevices(): MutableSet<BluetoothDevice> {
            bondedReads++
            return super.getBondedDevices()?.toMutableSet() ?: mutableSetOf()
        }
    }
}
