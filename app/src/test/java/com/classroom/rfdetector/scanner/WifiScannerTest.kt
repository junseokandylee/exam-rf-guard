package com.classroom.rfdetector.scanner

import android.content.Context
import android.content.Intent
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.SystemClock
import com.classroom.rfdetector.core.RfObservation
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.After
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
class WifiScannerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scanner = WifiScanner(app, scope)
    private val observations = mutableListOf<RfObservation>()

    @After fun close() { scanner.stop(); scope.cancel() }

    private fun result(address: String, ssid: String?, ageMs: Long = 0) = ScanResult().apply {
        BSSID = address
        SSID = ssid
        level = -60
        frequency = 2437
        timestamp = (SystemClock.elapsedRealtime() - ageMs) * 1000
    }

    private fun complete(success: Boolean = true) {
        app.sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).putExtra(WifiManager.EXTRA_RESULTS_UPDATED, success))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun hiddenSsidCanBeNullAndKeepsActualObservationTime() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        shadowOf(manager).setScanResults(emptyList())
        scanner.start(onObs = { observations.add(it) })
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        shadowOf(manager).setScanResults(listOf(result("00:11:22:33:44:55", null, 5_000)))
        complete()
        val observation = observations.single()
        assertNull(observation.name)
        assertTrue(observation.hidden)
        assertEquals(2437, observation.frequencyMhz)
        val ageMs = System.currentTimeMillis() - observation.timestampMs
        assertTrue("Cached result must retain its five-second age, was $ageMs ms", ageMs in 5_000..5_500)
    }

    @Test fun expiredAndFutureScanEntriesAreExcluded() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        shadowOf(manager).setScanResults(listOf(result("fresh", "fresh"), result("expired", "expired", 90_001), result("future", "future", -1_000)))
        scanner.start(onObs = { observations.add(it) })
        complete()
        assertEquals(listOf("fresh"), observations.map { it.key })
        assertEquals(listOf("fresh"), scanner.lastResults.map { it.BSSID })
    }

    @Test fun failedCompletionDoesNotReprocessCachedResults() {
        shadowOf(manager).setScanResults(listOf(result("cached", "cached")))
        val errors = mutableListOf<String>()
        scanner.onError = { errors.add(it) }
        scanner.start(onObs = { observations.add(it) })
        complete(false)
        assertTrue(observations.isEmpty())
        assertNull(scanner.lastUpdatedMs)
        assertTrue(errors.isNotEmpty())
    }

    @Test fun stopUnregistersReceiver() {
        shadowOf(manager).setScanResults(emptyList())
        scanner.start(onObs = { observations.add(it) })
        complete()
        scanner.stop()
        shadowOf(manager).setScanResults(listOf(result("after-stop", "after-stop")))
        complete()
        assertTrue(observations.isEmpty())
    }

    @Test fun duplicateAndOlderNotificationsDoNotCountAsNewSignalSamples() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        val original = result("same-ap", "same-ap")
        shadowOf(manager).setScanResults(listOf(original))
        scanner.start(onObs = { observations.add(it) })
        complete()
        complete()
        assertEquals(1, observations.size)
        shadowOf(manager).setScanResults(listOf(result("same-ap", "same-ap", 10_000)))
        complete()
        assertEquals(1, observations.size)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        shadowOf(manager).setScanResults(listOf(result("same-ap", "same-ap")))
        complete()
        assertEquals(2, observations.size)
        // Robolectric's elapsed clock can advance independently of the JVM wall clock.
        assertTrue(kotlin.math.abs(System.currentTimeMillis() - observations.last().timestampMs) < 1000)
    }

    @Test fun cachedFramesFromBeforeThisSessionAreNotAssignedToTheNewRoom() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        shadowOf(manager).setScanResults(listOf(result("previous-room", "old AP", 1000)))
        scanner.start(onObs = { observations.add(it) })
        complete()
        assertTrue(observations.isEmpty())
        assertTrue(scanner.lastResults.isEmpty())
    }

    @Test fun queuedBroadcastForStoppedReceiverDoesNotRunInRestartedSession() {
        shadowOf(manager).setScanResults(emptyList())
        scanner.start(onObs = { observations.add(it) })
        app.sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true))
        scanner.stop()
        scanner.start(onObs = { observations.add(it) })
        shadowOf(manager).setScanResults(listOf(result("new-room", "new AP")))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(observations.isEmpty())
        complete()
        assertEquals(listOf("new-room"), observations.map { it.key })
    }

    @Test fun successfulEmptyCompletionClearsErrorButIsNotInventedDeviceReception() {
        shadowOf(manager).setScanResults(emptyList())
        scanner.start(onObs = { observations.add(it) })
        assertTrue(scanner.active)
        complete(false)
        assertNotNull(scanner.lastError)
        assertNull(scanner.lastUpdatedMs)
        complete()
        assertNull(scanner.lastError)
        assertNotNull(scanner.lastUpdatedMs)
        assertTrue(scanner.lastResults.isEmpty())
        assertTrue(observations.isEmpty())
        scanner.stop()
        assertFalse(scanner.active)
        scanner.start(onObs = { observations.add(it) })
        assertNull(scanner.lastUpdatedMs)
    }

    @Test fun channelChangesUseFreshSamplesAndCachedNotificationsCannotAddFalseChannels() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        val first = result("same-ap", "Room AP").apply { frequency = 2437 }
        shadowOf(manager).setScanResults(listOf(first))
        scanner.start(onObs = { observations.add(it) })
        complete()
        shadowOf(manager).setScanResults(listOf(result("same-ap", "Room AP").apply {
            timestamp = first.timestamp; frequency = 5180
        }))
        complete()
        assertEquals(listOf(2437), observations.map { it.frequencyMhz })
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        shadowOf(manager).setScanResults(listOf(result("same-ap", "Room AP").apply { frequency = 5180 }))
        complete()
        shadowOf(manager).setScanResults(listOf(first))
        complete()
        assertEquals(listOf(2437, 5180), observations.map { it.frequencyMhz })
    }

    @Test fun absentEmptyAndWhitespaceSsidsKeepBssidAndFrequencyAsHiddenObservations() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        scanner.start(onObs = { observations.add(it) })
        shadowOf(manager).setScanResults(listOf(
            result("null-ssid", null),
            result("empty-ssid", ""),
            result("blank-ssid", "  "),
        ))
        complete()
        assertEquals(setOf("null-ssid", "empty-ssid", "blank-ssid"), observations.map { it.key }.toSet())
        assertTrue(observations.all { it.hidden && it.name == null && it.frequencyMhz == 2437 })
    }

    @Test fun channelWidthSegmentsAndSecurityAdvertisementArePreservedWithoutInventedCenters() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        scanner.start(onObs = { observations.add(it) })
        shadowOf(manager).setScanResults(listOf(
            result("wide", null).apply {
                frequency = 5180; channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ
                centerFreq0 = 5210; centerFreq1 = 5530; capabilities = "[RSN-OWE-CCMP][ESS]"
            },
            result("narrow", "AP").apply {
                channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ
                centerFreq0 = 2437; centerFreq1 = 5530; capabilities = " "
            },
        ))
        complete()
        val wide = observations.first().radioMetadata!!.wifi!!
        assertEquals("80+80 MHz", wide.channelWidthLabel)
        assertEquals(5210, wide.centerFrequency0Mhz)
        assertEquals(5530, wide.centerFrequency1Mhz)
        assertEquals("[RSN-OWE-CCMP][ESS]", wide.capabilities)
        val narrow = observations.last().radioMetadata!!.wifi!!
        assertEquals(20, narrow.channelWidthMhz)
        assertNull(narrow.centerFrequency0Mhz)
        assertNull(narrow.centerFrequency1Mhz)
        assertNull(narrow.capabilities)
    }
}

