package com.classroom.rfdetector.scanner

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.ScanSource
import java.time.Duration
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
import org.robolectric.shadows.ShadowWifiP2pManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [WifiDirectScannerTest.ControlledP2pManager::class, WifiDirectScannerTest.ControlledChannel::class])
class WifiDirectScannerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val control = Shadow.extract<ControlledP2pManager>(manager)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var scanner: WifiDirectScanner
    private val observations = mutableListOf<RfObservation>()
    private val errors = mutableListOf<String>()

    @Before fun setup() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_DIRECT, true)
        scanner = WifiDirectScanner(app, scope).also { it.onError = errors::add }
    }

    @After fun close() { scanner.stop(); scope.cancel() }

    private fun start() {
        scanner.start { observations.add(it) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun changed() {
        app.sendBroadcast(Intent(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun peer(address: String = "AA:BB:CC:00:00:01", name: String? = "Nearby peer") = WifiP2pDevice().apply {
        deviceAddress = address
        deviceName = name
    }

    private fun peers(vararg devices: WifiP2pDevice) = WifiP2pDeviceList().also { list ->
        devices.forEach { device ->
            ReflectionHelpers.callInstanceMethod<Void>(list, "update", ClassParameter.from(WifiP2pDevice::class.java, device))
        }
    }

    private fun returnPeers(vararg devices: WifiP2pDevice) {
        control.peerRequests.last().onPeersAvailable(peers(*devices))
    }

    @Test fun firstListEntryHasOnlyListCheckTimeAndNoInventedSignalMeasurements() {
        start()
        assertTrue(scanner.supported)
        assertTrue(scanner.active)
        assertNull(scanner.lastUpdatedMs)
        control.actions.single().onSuccess()
        returnPeers(peer())
        val result = observations.single()
        assertEquals(ScanSource.P2P, result.source)
        assertEquals("AA:BB:CC:00:00:01", result.key)
        assertEquals("Nearby peer", result.name)
        assertNull(result.rssi)
        assertNull(result.txPower)
        assertNull(result.frequencyMhz)
        assertFalse(result.hidden)
        assertEquals(scanner.lastUpdatedMs, result.timestampMs)
        assertTrue(kotlin.math.abs(System.currentTimeMillis() - result.timestampMs) < 1000)
    }

    @Test fun cachedListsNameChangesAndCacheReappearanceDoNotInventReceptionOrReappearanceSamples() {
        start()
        control.actions.single().onSuccess()
        returnPeers(peer())
        changed()
        returnPeers(peer("aa:bb:cc:00:00:01", "A changed cached name"))
        changed()
        returnPeers()
        changed()
        returnPeers(peer(), peer("AA:BB:CC:00:00:02", "Another peer"))
        assertEquals(2, observations.size)
        assertEquals("Nearby peer", observations.first().name)
        assertNotNull(scanner.lastUpdatedMs)
    }

    @Test fun emptyListsAndRedactedAddressesAreNotObservations() {
        start()
        control.actions.single().onSuccess()
        returnPeers()
        assertNotNull(scanner.lastUpdatedMs)
        assertTrue(observations.isEmpty())
        changed()
        returnPeers(peer(" "), peer("02:00:00:00:00:00"), peer("00:00:00:00:00:00"), peer("AA:BB:CC:00:00:02", "  "))
        assertEquals(1, observations.size)
        assertNull(observations.single().name)
    }

    @Test fun unsupportedHardwareDoesNotInitializeOrScheduleDiscovery() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_DIRECT, false)
        scanner = WifiDirectScanner(app, scope)
        start()
        assertFalse(scanner.supported)
        assertFalse(scanner.active)
        assertEquals(0, control.initializeCalls)
        assertTrue(control.actions.isEmpty())
        assertTrue(scanner.lastError!!.contains("지원하지"))
    }

    @Test fun busyRetriesOnlyAfterSixtySecondsAndListReadDoesNotHideDiscoveryFailure() {
        start()
        control.actions.single().onFailure(WifiP2pManager.BUSY)
        assertTrue(scanner.active)
        assertTrue(scanner.lastError!!.contains("60초"))
        changed()
        returnPeers()
        assertTrue(scanner.lastError!!.contains("60초"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
        assertEquals(1, control.actions.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, control.actions.size)
        control.actions.last().onSuccess()
        assertNull(scanner.lastError)
        returnPeers(peer())
        assertEquals(1, observations.size)
    }

    @Test fun unsupportedActionStopsAndDoesNotRetry() {
        start()
        control.actions.single().onFailure(WifiP2pManager.P2P_UNSUPPORTED)
        assertFalse(scanner.supported)
        assertFalse(scanner.active)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertEquals(1, control.actions.size)
        assertEquals(0, control.stopCalls)
    }

    @Test fun initializationPermissionDenialIsVisibleAndRestartCanRecover() {
        control.denyInitialize = true
        start()
        assertFalse(scanner.active)
        assertTrue(scanner.lastError!!.contains("권한"))
        assertTrue(control.actions.isEmpty())
        control.denyInitialize = false
        start()
        assertTrue(scanner.active)
        assertNull(scanner.lastError)
        assertNull(scanner.lastUpdatedMs)
    }

    @Test fun discoveryPermissionRevocationStopsWithoutRepeatedRequests() {
        control.denyDiscovery = true
        start()
        assertFalse(scanner.active)
        assertTrue(scanner.lastError!!.contains("권한"))
        assertEquals(0, control.stopCalls)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertEquals(1, control.discoveryCalls)
        assertTrue(errors.isNotEmpty())
    }

    @Test fun listPermissionRevocationStopsAndDoesNotMarkAnUpdate() {
        start()
        control.denyPeers = true
        control.actions.single().onSuccess()
        assertFalse(scanner.active)
        assertTrue(scanner.lastError!!.contains("권한"))
        assertNull(scanner.lastUpdatedMs)
        assertTrue(observations.isEmpty())
    }

    @Test fun stoppedAndRestartedSessionRejectsOldActionsListsChannelDisconnectAndQueuedBroadcasts() {
        start()
        val firstAction = control.actions.single()
        firstAction.onSuccess()
        val oldPeers = control.peerRequests.single()
        val oldDisconnect = control.disconnects.single()
        app.sendBroadcast(Intent(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION))
        scanner.stop()
        val closed = Shadow.extract<ControlledChannel>(control.channels.single())
        assertEquals(1, closed.closeCalls)
        start()
        val currentPeerRequestCount = control.peerRequests.size
        firstAction.onSuccess()
        firstAction.onFailure(WifiP2pManager.P2P_UNSUPPORTED)
        oldPeers.onPeersAvailable(peers(peer()))
        oldDisconnect.onChannelDisconnected()
        assertTrue(scanner.active)
        assertTrue(scanner.supported)
        assertNull(scanner.lastError)
        assertNull(scanner.lastUpdatedMs)
        assertTrue(observations.isEmpty())
        assertEquals(currentPeerRequestCount, control.peerRequests.size)
        control.actions.last().onSuccess()
        returnPeers(peer())
        assertEquals(1, observations.size)
    }

    @Test fun channelDisconnectionStopsAndKeepsExistingListHistory() {
        start()
        control.actions.single().onSuccess()
        returnPeers(peer())
        val lastChecked = scanner.lastUpdatedMs
        control.disconnects.single().onChannelDisconnected()
        assertFalse(scanner.active)
        assertTrue(scanner.lastError!!.contains("채널"))
        assertEquals(lastChecked, scanner.lastUpdatedMs)
        changed()
        returnPeers(peer("AA:BB:CC:00:00:02"))
        assertEquals(1, observations.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertEquals(1, control.actions.size)
    }

    @Test fun wifiDirectDisabledStopsAndRejectsLaterListCallback() {
        start()
        control.actions.single().onSuccess()
        app.sendBroadcast(Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            .putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED))
        shadowOf(Looper.getMainLooper()).idle()
        returnPeers(peer())
        assertFalse(scanner.active)
        assertTrue(scanner.lastError!!.contains("꺼졌"))
        assertNull(scanner.lastUpdatedMs)
        assertTrue(observations.isEmpty())
    }

    @Test fun busyOrPendingDiscoveryIsClosedWithoutCancellingAnotherAppsDiscovery() {
        start()
        control.actions.single().onFailure(WifiP2pManager.BUSY)
        scanner.stop()
        assertEquals(0, control.stopCalls)
        assertEquals(1, Shadow.extract<ControlledChannel>(control.channels.single()).closeCalls)
        start()
        val pendingAction = control.actions.last()
        val pendingChannel = control.channels.last()
        scanner.stop()
        pendingAction.onSuccess()
        assertEquals(0, control.stopCalls)
        assertEquals(1, Shadow.extract<ControlledChannel>(pendingChannel).closeCalls)
        assertFalse(scanner.active)
        assertTrue(control.peerRequests.isEmpty())
    }

    @Test fun onlyAcceptedCurrentGenerationDiscoveryIsCancelledOnce() {
        start()
        val oldAction = control.actions.single()
        scanner.stop()
        start()
        oldAction.onSuccess()
        scanner.stop()
        assertEquals(0, control.stopCalls)
        start()
        control.actions.last().onSuccess()
        scanner.stop()
        scanner.stop()
        assertEquals(1, control.stopCalls)
    }

    @Implements(WifiP2pManager::class)
    class ControlledP2pManager : ShadowWifiP2pManager() {
        var denyInitialize = false
        var denyDiscovery = false
        var denyPeers = false
        var initializeCalls = 0
        var discoveryCalls = 0
        var stopCalls = 0
        val actions = mutableListOf<WifiP2pManager.ActionListener>()
        val peerRequests = mutableListOf<WifiP2pManager.PeerListListener>()
        val disconnects = mutableListOf<WifiP2pManager.ChannelListener>()
        val channels = mutableListOf<WifiP2pManager.Channel>()

        @Implementation public override fun initialize(context: Context, looper: Looper, listener: WifiP2pManager.ChannelListener): WifiP2pManager.Channel {
            initializeCalls++
            if (denyInitialize) throw SecurityException("P2P initialization permission revoked")
            disconnects.add(listener)
            return super.initialize(context, looper, listener).also(channels::add)
        }

        @Implementation fun discoverPeers(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener) {
            discoveryCalls++
            if (denyDiscovery) throw SecurityException("P2P discovery permission revoked")
            actions.add(listener)
        }

        @Implementation fun requestPeers(channel: WifiP2pManager.Channel, listener: WifiP2pManager.PeerListListener) {
            if (denyPeers) throw SecurityException("P2P list permission revoked")
            peerRequests.add(listener)
        }

        @Implementation fun stopPeerDiscovery(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener?) {
            stopCalls++
        }
    }

    @Implements(WifiP2pManager.Channel::class)
    class ControlledChannel {
        var closeCalls = 0
        @Implementation fun close() { closeCalls++ }
    }
}
