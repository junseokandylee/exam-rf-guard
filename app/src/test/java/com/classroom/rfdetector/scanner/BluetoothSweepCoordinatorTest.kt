package com.classroom.rfdetector.scanner

import android.os.Looper
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothSweepCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ble = false
    private var classic = false
    private var classicAllowed = true
    private var cancellationFinishesImmediately = true
    private var completed: (() -> Unit)? = null
    private var phase = BluetoothSweepPhase.STOPPED
    private var bleStarts = 0
    private var retryDelayMs: Long? = null
    private val sweep = BluetoothSweepCoordinator(scope,
        startBle = { assertFalse(classic); ble = true; bleStarts++ }, stopBle = { ble = false },
        startClassic = { finish -> assertFalse(ble); completed = finish; classic = classicAllowed; classicAllowed },
        stopClassic = { if (cancellationFinishesImmediately) classic = false },
        isClassicDiscoveryIdle = { !classic }, onPhase = { phase = it },
        isBleActive = { ble }, bleRetryDelayMs = { retryDelayMs })

    @After fun close() { sweep.stop(); scope.cancel() }
    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test fun standardModeKeepsBleAndNeverStartsClassic() {
        sweep.start(false)
        advance(180)
        assertTrue(ble)
        assertNull(completed)
        assertEquals(1, bleStarts)
    }

    @Test fun expandedModeAlternatesAndNaturalCompletionRestoresBle() {
        sweep.start(true)
        advance(45)
        assertFalse(ble)
        assertTrue(classic)
        assertEquals(BluetoothSweepPhase.CLASSIC, phase)
        completed!!()
        advance(0)
        assertTrue(ble)
        assertFalse(classic)
        assertEquals(2, bleStarts)
    }

    @Test fun discoveryTimeoutAndFailureBothResumeBle() {
        sweep.start(true)
        advance(61)
        assertTrue(ble)
        assertFalse(classic)
        classicAllowed = false
        advance(45)
        assertTrue(ble)
        assertFalse(classic)
        assertEquals(3, bleStarts)
    }

    @Test fun stopOrRestartRejectsOldCompletionWithoutStoppingNewBleSession() {
        sweep.start(true)
        advance(45)
        val stale = completed!!
        sweep.stop()
        stale()
        advance(60)
        assertFalse(ble)
        assertFalse(classic)
        sweep.start(false)
        stale()
        advance(60)
        assertTrue(ble)
        assertEquals(BluetoothSweepPhase.BLE, phase)
    }

    @Test fun timeoutCannotRestartBleUntilDelayedPlatformCancellationActuallyFinishes() {
        sweep.start(true)
        advance(45)
        cancellationFinishesImmediately = false
        advance(16)
        assertTrue(classic)
        assertFalse(ble)
        assertEquals(BluetoothSweepPhase.WAITING, phase)
        advance(20) // Multiple bounded checks, never an unverified BLE start.
        assertEquals(1, bleStarts)
        assertFalse(ble)
        classic = false // The system, not stopClassic's return, confirms completion.
        advance(8)
        assertTrue(ble)
        assertEquals(2, bleStarts)
    }

    @Test fun waitingRestartAndStopInvalidateOldRoundAndPollingCallbacks() {
        sweep.start(true)
        advance(45)
        val stale = completed!!
        cancellationFinishesImmediately = false
        advance(16)
        assertEquals(BluetoothSweepPhase.WAITING, phase)
        sweep.start(false)
        stale()
        advance(10)
        assertFalse(ble)
        assertEquals(1, bleStarts)
        classic = false
        advance(8)
        assertTrue(ble)
        assertEquals(2, bleStarts)
        classic = true
        sweep.start(false)
        assertEquals(BluetoothSweepPhase.WAITING, phase)
        sweep.stop()
        classic = false
        advance(30)
        assertFalse(ble)
        assertEquals(2, bleStarts)
        assertEquals(BluetoothSweepPhase.STOPPED, phase)
    }

    @Test fun alreadyRunningSystemDiscoveryAlsoGatesStandardBleMode() {
        classic = true
        cancellationFinishesImmediately = false
        sweep.start(false)
        advance(10)
        assertFalse(ble)
        assertEquals(0, bleStarts)
        assertEquals(BluetoothSweepPhase.WAITING, phase)
        classic = false
        advance(8)
        assertTrue(ble)
        assertEquals(1, bleStarts)
        assertNull(completed)
    }

    @Test fun failedBleRecoversAfterBackoffWithoutCyclingHealthyReception() {
        sweep.start(false)
        ble = false
        retryDelayMs = 5_000
        advance(1)
        assertEquals(BluetoothSweepPhase.RETRYING, phase)
        advance(4)
        assertEquals(1, bleStarts)
        advance(1)
        assertEquals(2, bleStarts)
        assertTrue(ble)
        advance(60)
        assertEquals(2, bleStarts)
    }

    @Test fun tooFrequentFailureWaitsAndStopCancelsPendingRecovery() {
        sweep.start(false)
        ble = false
        retryDelayMs = 30_000
        advance(30)
        assertEquals(1, bleStarts)
        sweep.stop()
        advance(60)
        assertEquals(1, bleStarts)
        assertEquals(BluetoothSweepPhase.STOPPED, phase)
    }

    @Test fun unrecoverableFailureDoesNotSpinAndExternalInquiryGatesRecovery() {
        sweep.start(false)
        ble = false
        advance(60)
        assertEquals(1, bleStarts)
        retryDelayMs = 5_000
        classic = true
        cancellationFinishesImmediately = false
        advance(20)
        assertEquals(1, bleStarts)
        assertEquals(BluetoothSweepPhase.WAITING, phase)
        classic = false
        advance(8)
        assertEquals(2, bleStarts)
        assertTrue(ble)
    }

    @Test fun delayedRetryCannotOverlapTheNextClassicRound() {
        sweep.start(true)
        advance(40)
        ble = false
        retryDelayMs = 30_000
        advance(5)
        assertEquals(BluetoothSweepPhase.CLASSIC, phase)
        assertTrue(classic)
        advance(15)
        assertEquals(1, bleStarts)
        assertFalse(ble)
        advance(1)
        assertEquals(2, bleStarts)
        assertTrue(ble)
    }
}
