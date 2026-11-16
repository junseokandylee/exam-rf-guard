package com.classroom.rfdetector.scanner

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.RadioMetadata
import com.classroom.rfdetector.core.ScanSource
import java.util.Locale

/** Discovery only: never reads bonded devices, pairs, connects, or opens a socket. */
class ClassicBluetoothScanner(context: Context) {
    private val app = context.applicationContext
    private val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var receiver: BroadcastReceiver? = null
    private var sessionToken = 0L
    private var ownsDiscovery = false
    private var cancellationPending = false

    @Volatile var active: Boolean = false
        private set
    @Volatile var lastReceivedAtMs: Long? = null
        private set
    @Volatile var lastError: String? = null
        private set
    var onError: ((String) -> Unit)? = null

    fun hasHardware(): Boolean = adapter != null

    fun isUsable(): Boolean = try {
        adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    /** False also means unknown after permission loss; BLE must wait rather than assume idle. */
    @MainThread
    fun isDiscoveryIdle(): Boolean = try {
        if (adapter?.isDiscovering == true) {
            reportStopWarning("일반 Bluetooth 검색 종료 확인 중 · BLE 수신은 대기합니다")
            false
        } else {
            ownsDiscovery = false
            cancellationPending = false
            true
        }
    } catch (_: SecurityException) {
        reportStopWarning("Bluetooth 권한 변경으로 검색 종료를 확인하지 못했습니다")
        false
    } catch (_: IllegalStateException) {
        reportStopWarning("Bluetooth 상태 변경으로 검색 종료를 확인하지 못했습니다")
        false
    }

    /** onFinished runs once for a completed accepted round, never for stop() or a failed start. */
    @MainThread
    fun start(onObs: (RfObservation) -> Unit, onFinished: () -> Unit): Boolean {
        stop()
        lastReceivedAtMs = null
        lastError = null
        val ad = adapter ?: return fail("블루투스 기능이 없는 기기입니다")
        if (!isUsable()) return fail("블루투스가 꺼져 있거나 접근 권한이 없습니다")
        try {
            if (ad.isDiscovering) return fail(if (cancellationPending) "이전 Bluetooth 검색 종료를 기다리고 있습니다"
                else "다른 Bluetooth 검색이 진행 중입니다 · 끝난 뒤 다시 시도합니다")
        } catch (_: SecurityException) {
            return fail("Bluetooth 탐색 권한을 확인하세요")
        }

        val token = sessionToken
        val seenAddresses = mutableSetOf<String>()
        var discoveryStarted = false
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (token != sessionToken || receiver !== this || !active || !ownsDiscovery) return
                var received: RfObservation? = null
                var finished = false
                var error: String? = null
                try {
                    when (intent.action) {
                        BluetoothAdapter.ACTION_DISCOVERY_STARTED -> discoveryStarted = true
                        BluetoothDevice.ACTION_FOUND -> {
                            // A previous round has no packet timestamp or round ID in Android's broadcast.
                            // Wait for this receiver's STARTED event and reject queued old receivers by token.
                            if (!discoveryStarted) return
                            val observation = observationFrom(intent) ?: return
                            if (!seenAddresses.add(observation.key)) return
                            lastReceivedAtMs = observation.timestampMs
                            lastError = null
                            received = observation
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            // A late FINISHED from cancellation must not terminate a newly running round.
                            if (!discoveryStarted || ad.isDiscovering) return
                            release(cancelOwnedDiscovery = false)
                            finished = true
                        }
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                            if (state != BluetoothAdapter.STATE_OFF && state != BluetoothAdapter.STATE_TURNING_OFF) return
                            release(cancelOwnedDiscovery = false)
                            error = "Bluetooth가 꺼져 일반 Bluetooth 탐색이 끝났습니다"
                            finished = true
                        }
                    }
                } catch (_: SecurityException) {
                    release(cancelOwnedDiscovery = true)
                    error = "Bluetooth 탐색 권한이 변경되었습니다 · 권한을 확인하세요"
                    finished = true
                }
                error?.let { fail(it) }
                received?.let(onObs)
                if (finished) onFinished()
            }
        }
        receiver = listener
        return try {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_FOUND)
            }
            // Bluetooth's privileged process has a distinct UID; NOT_EXPORTED can drop these events.
            ContextCompat.registerReceiver(app, listener, filter, ContextCompat.RECEIVER_EXPORTED)
            if (!ad.startDiscovery()) {
                release(cancelOwnedDiscovery = false)
                fail("일반 Bluetooth 탐색을 시작하지 못했습니다 · 잠시 후 다시 시도합니다")
            } else {
                ownsDiscovery = true
                cancellationPending = false
                active = true
                true
            }
        } catch (_: SecurityException) {
            release(cancelOwnedDiscovery = false)
            fail("Bluetooth 탐색 권한을 확인하세요")
        } catch (_: IllegalStateException) {
            release(cancelOwnedDiscovery = false)
            fail("Bluetooth 상태가 바뀌었습니다 · 다시 시작하세요")
        }
    }

    @MainThread
    fun stop() = release(cancelOwnedDiscovery = true)

    private fun observationFrom(intent: Intent): RfObservation? {
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            ?: return null
        val address = device.address?.uppercase(Locale.ROOT)?.takeIf(BluetoothAdapter::checkBluetoothAddress)
            ?: return null
        val rawRssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
        return RfObservation(
            key = address,
            source = ScanSource.CLASSIC,
            // Only the name carried with this discovery, not a cached paired-device name or alias.
            name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)?.takeIf { it.isNotBlank() },
            rssi = rawRssi.takeIf { it in -127..20 },
            txPower = null,
            hidden = false,
            // ACTION_FOUND has no original packet timestamp; this is callback receipt time.
            timestampMs = System.currentTimeMillis(),
            radioMetadata = RadioMetadata(classicDeviceClass = IntentCompat.getParcelableExtra(
                intent, BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java,
            )?.deviceClass),
        )
    }

    private fun release(cancelOwnedDiscovery: Boolean) {
        val shouldCancel = cancelOwnedDiscovery && ownsDiscovery
        val oldReceiver = receiver
        sessionToken++
        receiver = null
        ownsDiscovery = false
        if (!cancelOwnedDiscovery) cancellationPending = false
        active = false
        if (oldReceiver != null) {
            try {
                app.unregisterReceiver(oldReceiver)
            } catch (_: IllegalArgumentException) {
                // Registration may have failed before a receiver was installed.
            }
        }
        if (!shouldCancel) return
        try {
            if (adapter?.isDiscovering != true) {
                cancellationPending = false
            } else {
                // Discovery has no round ID. After this single cancellation attempt, a later
                // isDiscovering=true could belong to another app: observe it, never re-cancel it.
                cancellationPending = true
                if (!adapter.cancelDiscovery()) {
                    reportStopWarning("일반 Bluetooth 검색 취소를 확인하지 못했습니다 · 종료될 때까지 BLE가 대기합니다")
                } else if (adapter.isDiscovering) {
                    reportStopWarning("일반 Bluetooth 검색 종료 확인 중 · BLE 수신은 대기합니다")
                } else {
                    cancellationPending = false
                }
            }
        } catch (_: SecurityException) {
            reportStopWarning("Bluetooth 권한 변경으로 검색 종료를 확인하지 못했습니다")
        } catch (_: IllegalStateException) {
            reportStopWarning("Bluetooth 상태 변경으로 검색 종료를 확인하지 못했습니다")
        }
    }

    private fun reportStopWarning(message: String) {
        if (lastError == message) return
        lastError = message
        onError?.invoke(message)
    }

    private fun fail(message: String): Boolean {
        active = false
        lastError = message
        onError?.invoke(message)
        return false
    }
}
