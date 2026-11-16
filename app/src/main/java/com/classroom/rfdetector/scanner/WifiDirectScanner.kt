package com.classroom.rfdetector.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.ScanSource
import com.classroom.rfdetector.core.radioKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Discovers Wi-Fi Direct peer addresses without connecting, creating a group or using sockets.
 * ACCESS/CHANGE_WIFI_STATE and the OS-version-specific nearby/location permissions are required;
 * INTERNET is not needed for these discovery/list APIs. Location mode may also be required.
 *
 * Peer lists can be cached and have no RF packet timestamp or RSSI. An observation means only that
 * an address first appeared in a returned list during this start/stop session, at the list-check
 * time. Repeated entries, changed names and disappearance/reappearance in this cache do not create
 * extra observations. A P2P address must not be merged with an AP BSSID as one physical device.
 */
class WifiDirectScanner(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    @Volatile var supported: Boolean = manager != null &&
        app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        private set
    /** True while this scanner is registered and scheduling discovery; not proof of reception. */
    @Volatile var active: Boolean = false
        private set
    /** Wall-clock time of the latest returned peer list, including an empty or unchanged list. */
    @Volatile var lastUpdatedMs: Long? = null
        private set
    @Volatile var lastError: String? = null
        private set
    var onError: ((String) -> Unit)? = null

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var job: Job? = null
    private var generation = 0L
    private var discoveryAccepted = false
    private val seenAddresses = mutableSetOf<String>()

    @MainThread
    fun start(onObs: (RfObservation) -> Unit) {
        stop()
        lastUpdatedMs = null
        lastError = null
        seenAddresses.clear()
        if (!supported) {
            reportError("Wi-Fi Direct 탐색을 지원하지 않는 기기입니다")
            return
        }
        val token = generation
        try {
            val opened = manager?.initialize(app, Looper.getMainLooper()) {
                if (generation == token) fail("Wi-Fi Direct 시스템 채널이 끊겼습니다 · 탐색을 다시 시작하세요")
            }
            if (opened == null) {
                fail("Wi-Fi Direct 탐색을 열지 못했습니다 · Wi-Fi 설정을 확인하세요")
                return
            }
            channel = opened
            val listener = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!isCurrent(token) || receiver !== this) return
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers(token, onObs)
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                WifiP2pManager.WIFI_P2P_STATE_DISABLED
                            ) fail("Wi-Fi Direct가 꺼졌습니다 · Wi-Fi를 켠 뒤 탐색을 다시 시작하세요")
                        }
                    }
                }
            }
            receiver = listener
            ContextCompat.registerReceiver(app, listener, IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_EXPORTED)
            active = true
            job = scope.launch {
                while (isActive && isCurrent(token)) {
                    discover(token, onObs)
                    if (!isCurrent(token)) break
                    delay(DISCOVERY_INTERVAL_MS)
                }
            }
        } catch (_: SecurityException) {
            fail(PERMISSION_ERROR)
        } catch (_: RuntimeException) {
            fail("Wi-Fi Direct 탐색을 시작하지 못했습니다 · 기기의 Wi-Fi 상태를 확인하세요")
        }
    }

    @MainThread
    fun stop() {
        generation++
        active = false
        job?.cancel()
        job = null
        val oldReceiver = receiver
        receiver = null
        if (oldReceiver != null) {
            try { app.unregisterReceiver(oldReceiver) } catch (_: IllegalArgumentException) { }
        }
        val oldChannel = channel
        channel = null
        val shouldStopDiscovery = discoveryAccepted
        discoveryAccepted = false
        if (oldChannel != null) {
            // A BUSY/failed request can belong to another app's search. Never cancel that search.
            if (shouldStopDiscovery) {
                try { manager?.stopPeerDiscovery(oldChannel, null) } catch (_: RuntimeException) { }
            }
            // Closing releases this registration even if its pending action responds after stop().
            try { oldChannel.close() } catch (_: RuntimeException) { }
        }
    }

    private fun discover(token: Long, onObs: (RfObservation) -> Unit) {
        val currentChannel = channel ?: return
        try {
            manager?.discoverPeers(currentChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isCurrent(token)) return
                    discoveryAccepted = true
                    lastError = null
                    // Success means the request was accepted, not that any RF packet was received.
                    requestPeers(token, onObs)
                }

                override fun onFailure(reason: Int) {
                    if (!isCurrent(token)) return
                    when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> {
                            supported = false
                            fail("Wi-Fi Direct 탐색을 지원하지 않는 기기입니다")
                        }
                        WifiP2pManager.BUSY -> reportError("Wi-Fi Direct 사용 중 · 60초 간격으로 다시 시도합니다")
                        else -> reportError("Wi-Fi Direct 갱신 대기 · Wi-Fi·위치 설정과 권한을 확인하세요")
                    }
                }
            })
        } catch (_: SecurityException) {
            fail(PERMISSION_ERROR)
        } catch (_: RuntimeException) {
            fail("Wi-Fi Direct 탐색 요청이 중단됐습니다 · 탐색을 다시 시작하세요")
        }
    }

    private fun requestPeers(token: Long, onObs: (RfObservation) -> Unit) {
        val currentChannel = channel ?: return
        try {
            manager?.requestPeers(currentChannel) peerList@{ peers ->
                if (!isCurrent(token)) return@peerList
                val checkedAt = System.currentTimeMillis()
                lastUpdatedMs = checkedAt
                // List retrieval can succeed even while discovery is BUSY; keep that warning.
                peers.deviceList.forEach { peer ->
                    if (!isCurrent(token)) return@forEach
                    val address = peer.deviceAddress?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                    if (address == "02:00:00:00:00:00" || address == "00:00:00:00:00:00") return@forEach
                    if (!seenAddresses.add(radioKey(ScanSource.P2P, address))) return@forEach
                    onObs(RfObservation(
                        key = address,
                        source = ScanSource.P2P,
                        name = peer.deviceName?.takeIf { it.isNotBlank() },
                        rssi = null,
                        txPower = null,
                        hidden = false,
                        timestampMs = checkedAt,
                    ))
                }
            }
        } catch (_: SecurityException) {
            fail(PERMISSION_ERROR)
        } catch (_: RuntimeException) {
            fail("Wi-Fi Direct 목록을 확인하지 못했습니다 · 탐색을 다시 시작하세요")
        }
    }

    private fun isCurrent(token: Long): Boolean = active && generation == token

    private fun fail(message: String) {
        stop()
        reportError(message)
    }

    private fun reportError(message: String) {
        lastError = message
        onError?.invoke(message)
    }

    companion object {
        internal const val DISCOVERY_INTERVAL_MS = 60_000L
        private const val PERMISSION_ERROR = "Wi-Fi Direct 권한을 확인하세요 · 주변 Wi-Fi 기기와 정확한 위치 권한이 필요합니다"
    }
}
