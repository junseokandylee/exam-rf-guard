package com.classroom.rfdetector.scanner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class BluetoothSweepPhase { STOPPED, BLE, CLASSIC, WAITING, RETRYING }

/** Main-thread owner: Classic discovery and BLE reception never run together. */
class BluetoothSweepCoordinator(
    private val scope: CoroutineScope,
    private val startBle: () -> Unit,
    private val stopBle: () -> Unit,
    private val startClassic: (() -> Unit) -> Boolean,
    private val stopClassic: () -> Unit,
    private val isClassicDiscoveryIdle: () -> Boolean,
    private val onPhase: (BluetoothSweepPhase) -> Unit,
    private val isBleActive: () -> Boolean = { true },
    private val bleRetryDelayMs: () -> Long? = { null },
) {
    private var job: Job? = null
    private var generation = 0L

    fun start(expanded: Boolean) {
        stop()
        val token = generation
        job = scope.launch {
            try {
                while (isActive && token == generation) {
                    if (!awaitClassicIdle(token)) {
                        // Do not spin, block the main thread, or start BLE over unfinished inquiry.
                        delay(5_000)
                        continue
                    }
                    receiveBle(token, expanded)
                    stopBle()
                    onPhase(BluetoothSweepPhase.CLASSIC)
                    val finished = CompletableDeferred<Unit>()
                    if (startClassic { finished.complete(Unit) }) withTimeoutOrNull(16_000) { finished.await() }
                    stopClassic()
                }
            } finally {
                if (token == generation) {
                    stopClassic()
                    stopBle()
                    onPhase(BluetoothSweepPhase.STOPPED)
                }
            }
        }
    }

    private suspend fun receiveBle(token: Long, expanded: Boolean) = coroutineScope {
        onPhase(BluetoothSweepPhase.BLE)
        startBle()
        val recovery = launch {
            while (isActive && token == generation) {
                delay(1_000)
                if (isBleActive()) continue
                val backoff = bleRetryDelayMs() ?: continue
                onPhase(BluetoothSweepPhase.RETRYING)
                delay(backoff.coerceAtLeast(5_000))
                while (!awaitClassicIdle(token)) delay(5_000)
                if (!isActive || token != generation) return@launch
                onPhase(BluetoothSweepPhase.BLE)
                startBle()
            }
        }
        try {
            if (expanded) delay(45_000) else awaitCancellation()
        } finally {
            recovery.cancel()
        }
    }

    private suspend fun awaitClassicIdle(token: Long): Boolean {
        if (isClassicDiscoveryIdle()) return true
        onPhase(BluetoothSweepPhase.WAITING)
        repeat(10) {
            delay(250)
            if (token != generation) return false
            if (isClassicDiscoveryIdle()) return true
        }
        // A failed/accepted cancellation can outlive our round. Poll without cancelling what may
        // now be another app's inquiry; startBle remains gated until the system confirms idle.
        return false
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        stopClassic()
        stopBle()
        onPhase(BluetoothSweepPhase.STOPPED)
    }
}
