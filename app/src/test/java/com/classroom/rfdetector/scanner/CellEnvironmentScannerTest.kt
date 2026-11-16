package com.classroom.rfdetector.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import android.telephony.*
import com.classroom.rfdetector.core.CellEnvironmentStatus
import java.time.Duration
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.robolectric.shadows.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [CellEnvironmentScannerTest.ControlledTelephony::class])
class CellEnvironmentScannerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val control = Shadow.extract<ControlledTelephony>(manager)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var scanner: CellEnvironmentScanner

    @Before fun setup() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)
        control.setSimState(TelephonyManager.SIM_STATE_READY)
        scanner = CellEnvironmentScanner(app, scope)
    }

    @After fun close() { scanner.stop(); scope.cancel() }

    private fun start() { scanner.start(); idle() }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun advance(seconds: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds)) }
    private fun deliver(vararg cells: CellInfo) {
        val (executor, callback) = control.requests.last()
        executor.execute { callback.onCellInfo(cells.toMutableList()) }
        idle()
    }

    private fun lte(timestamp: Long = SystemClock.elapsedRealtimeNanos(), ci: Int = 123): CellInfoLte = CellInfoLteBuilder.newBuilder()
        .setRegistered(true).setTimeStampNanos(timestamp)
        .setCellIdentity(CellIdentityLteBuilder.newBuilder().setMcc("450").setMnc("05").setTac(10).setCi(ci).setEarfcn(1300).build())
        .setCellSignalStrength(CellSignalStrengthLteBuilder.newBuilder().setRsrp(-95).build()).build()

    @Test fun fineLocationAloneAllowsDefaultSubscriptionCellRequestWithoutReadingPrivatePhoneIdentifiers() {
        start()
        deliver(lte())
        assertTrue(scanner.supported)
        assertTrue(scanner.active)
        assertEquals(CellEnvironmentStatus.AVAILABLE, scanner.status)
        val cell = scanner.readings.single()
        assertEquals("LTE", cell.technology)
        assertEquals("LTE|mcc=450|mnc=05|tac=10|ci=123", cell.identity)
        assertEquals(1300, cell.channel)
        assertEquals(-95, cell.dbm)
        assertTrue(cell.registered)
        assertFalse(cell.cached)
        assertNotNull(scanner.lastUpdatedMs)
        assertEquals(0, control.sensitiveReads)
        assertEquals(listOf(cell), scanner.snapshot().readings)
    }

    @Test @Config(sdk = [29]) fun minimumSupportedAndroidUsesTheSamePublicRequestAndNanosecondTimestamp() {
        start()
        deliver(lte())
        assertEquals(CellEnvironmentStatus.AVAILABLE, scanner.status)
        assertEquals("LTE", scanner.readings.single().technology)
        assertTrue(kotlin.math.abs(System.currentTimeMillis() - scanner.readings.single().observedAtMs) < 1000)
    }

    @Test fun oldModemTimestampIsRetainedAsCacheAndSixtySecondPollingDoesNotClaimFreshReception() {
        val old = lte(SystemClock.elapsedRealtimeNanos() - 10_000_000_000L)
        start()
        deliver(old)
        assertEquals(CellEnvironmentStatus.CACHED, scanner.status)
        assertTrue(scanner.readings.single().cached)
        assertTrue(System.currentTimeMillis() - scanner.readings.single().observedAtMs >= 10_000)
        assertTrue(scanner.note!!.contains("제한 가능"))
        advance(59)
        assertEquals(1, control.requests.size)
        advance(1)
        assertEquals(2, control.requests.size)
        deliver(old)
        assertEquals(CellEnvironmentStatus.CACHED, scanner.status)
        assertTrue(scanner.readings.single().cached)
    }

    @Test fun futureZeroAndUnavailableTimestampsDoNotBecomeFreshObservations() {
        start()
        deliver(lte(0), lte(Long.MAX_VALUE, 124), lte(SystemClock.elapsedRealtimeNanos() + 1_000_000_000L, 125))
        assertTrue(scanner.readings.isEmpty())
        assertEquals(CellEnvironmentStatus.EMPTY, scanner.status)
        assertNotNull(scanner.lastUpdatedMs)
    }

    @Test fun explicitSimAbsenceAndEmptyModemResponseAreDifferentStates() {
        control.setSimState(TelephonyManager.SIM_STATE_ABSENT)
        start()
        assertEquals(CellEnvironmentStatus.NO_SIM, scanner.status)
        assertTrue(control.requests.isEmpty())
        assertNull(scanner.lastUpdatedMs)
        control.setSimState(TelephonyManager.SIM_STATE_UNKNOWN)
        advance(60)
        assertEquals(1, control.requests.size)
        deliver()
        assertEquals(CellEnvironmentStatus.EMPTY, scanner.status)
        assertNull(scanner.lastError)
    }

    @Test fun unsupportedHardwareDoesNotRequestAndPlatformUnsupportedAlsoStops() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, false)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS, false)
        scanner = CellEnvironmentScanner(app, scope)
        start()
        assertFalse(scanner.supported)
        assertFalse(scanner.active)
        assertEquals(CellEnvironmentStatus.UNSUPPORTED, scanner.status)
        assertTrue(control.requests.isEmpty())
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        scanner = CellEnvironmentScanner(app, scope)
        control.unsupported = true
        start()
        assertFalse(scanner.supported)
        assertFalse(scanner.active)
        assertEquals(CellEnvironmentStatus.UNSUPPORTED, scanner.status)
    }

    @Test fun permissionDenialAndMidRequestRevocationStopWithoutPrivatePermissionFallback() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        start()
        assertFalse(scanner.active)
        assertEquals(CellEnvironmentStatus.PERMISSION_DENIED, scanner.status)
        assertTrue(control.requests.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        control.denyRequest = true
        start()
        assertFalse(scanner.active)
        assertEquals(CellEnvironmentStatus.PERMISSION_DENIED, scanner.status)
        assertEquals(0, control.sensitiveReads)
    }

    @Test fun missingCallbackTimesOutAndLateReplyCannotOverwriteLaterRequest() {
        start()
        val old = control.requests.single().second
        advance(30)
        assertEquals(CellEnvironmentStatus.TIMEOUT, scanner.status)
        assertNull(scanner.lastUpdatedMs)
        old.onCellInfo(mutableListOf<CellInfo>(lte()))
        assertTrue(scanner.readings.isEmpty())
        advance(30)
        assertEquals(2, control.requests.size)
        deliver(lte(ci = 777))
        old.onError(TelephonyManager.CellInfoCallback.ERROR_MODEM_ERROR, null)
        assertEquals(CellEnvironmentStatus.AVAILABLE, scanner.status)
        assertTrue(scanner.readings.single().identity.endsWith("ci=777"))
    }

    @Test fun platformTimeoutAndModemErrorAreDistinctAndPreserveOriginalReadingTime() {
        start()
        deliver(lte())
        val saved = scanner.readings
        val savedUpdate = scanner.lastUpdatedMs
        advance(60)
        control.requests.last().second.onError(TelephonyManager.CellInfoCallback.ERROR_TIMEOUT, null)
        assertEquals(CellEnvironmentStatus.TIMEOUT, scanner.status)
        assertEquals(saved, scanner.readings)
        assertEquals(savedUpdate, scanner.lastUpdatedMs)
        advance(60)
        control.requests.last().second.onError(TelephonyManager.CellInfoCallback.ERROR_MODEM_ERROR, null)
        assertEquals(CellEnvironmentStatus.MODEM_ERROR, scanner.status)
        assertEquals(saved, scanner.readings)
        assertTrue(scanner.note!!.contains("마지막 성공"))
    }

    @Test fun restartRejectsPreviousSessionCallbacksAndResetsPreviousRoomReadings() {
        start()
        val old = control.requests.single().second
        deliver(lte())
        scanner.stop()
        start()
        old.onCellInfo(mutableListOf<CellInfo>(lte(ci = 999)))
        old.onError(TelephonyManager.CellInfoCallback.ERROR_TIMEOUT, null)
        assertTrue(scanner.readings.isEmpty())
        assertNull(scanner.lastUpdatedMs)
        assertEquals(CellEnvironmentStatus.UPDATING, scanner.status)
        deliver(lte(ci = 555))
        assertTrue(scanner.readings.single().identity.endsWith("ci=555"))
    }

    @Test fun nrIdentityUsesLongCellIdAndUnavailableSignalDoesNotBecomeZero() {
        val identity = CellIdentityNrBuilder.newBuilder().setMcc("450").setMnc("08").setTac(123)
            .setNci(4_294_967_300L).setNrarfcn(640_000).build()
        val nr = CellInfoNrBuilder.newBuilder().setTimeStampNanos(90_000_000_000L)
            .setCellIdentity(identity).setCellSignalStrength(CellSignalStrengthNrBuilder.newBuilder().build()).build()
        val rows = cellEnvironmentReadings(listOf(nr), 1_000_000, 100_000_000_000L, 95_000_000_000L)
        assertEquals("NR|mcc=450|mnc=08|tac=123|nci=4294967300", rows.single().identity)
        assertEquals(640_000, rows.single().channel)
        assertNull(rows.single().dbm)
        assertEquals(990_000L, rows.single().observedAtMs)
        assertTrue(rows.single().cached)
        val later = cellEnvironmentReadings(listOf(nr), 1_060_000, 160_000_000_000L, 155_000_000_000L)
        assertEquals(rows.single().observedAtMs, later.single().observedAtMs)
    }

    @Test fun unavailablePublicCellIdentifiersRemainUnknownAndLegacyTechnologiesAreRecognized() {
        val legacy = listOf(
            ReflectionHelpers.newInstance(CellInfoGsm::class.java),
            ReflectionHelpers.newInstance(CellInfoWcdma::class.java),
            ReflectionHelpers.newInstance(CellInfoCdma::class.java),
        )
        legacy.forEach { ReflectionHelpers.setField(it, "mTimeStamp", 90_000_000_000L) }
        val rows = cellEnvironmentReadings(legacy, 1_000_000, 100_000_000_000L, 95_000_000_000L)
        assertEquals(setOf("GSM", "WCDMA", "CDMA"), rows.map { it.technology }.toSet())
        assertTrue(rows.all { it.channel == null && it.dbm == null && it.identity.contains("?") })
        assertFalse(rows.any { it.identity.contains(Int.MAX_VALUE.toString()) })
        assertFalse(rows.any { it.identity.contains("latitude") || it.identity.contains("longitude") })
    }

    @Implements(TelephonyManager::class)
    class ControlledTelephony : ShadowTelephonyManager() {
        var denyRequest = false
        var unsupported = false
        var sensitiveReads = 0
        val requests = mutableListOf<Pair<Executor, TelephonyManager.CellInfoCallback>>()

        @Implementation fun requestCellInfoUpdate(executor: Executor, callback: TelephonyManager.CellInfoCallback) {
            if (denyRequest) throw SecurityException("Location revoked")
            if (unsupported) throw UnsupportedOperationException("No radio access")
            requests.add(executor to callback)
        }

        @Implementation public override fun getSubscriberId(): String { sensitiveReads++; error("Subscriber IDs are not environment data") }
        @Implementation public override fun getDeviceId(): String { sensitiveReads++; error("Device IDs are not environment data") }
        @Implementation public override fun getLine1Number(): String { sensitiveReads++; error("Phone numbers are not environment data") }
    }
}
