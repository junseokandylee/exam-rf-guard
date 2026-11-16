package com.classroom.rfdetector.scanner

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.RadioMetadata
import com.classroom.rfdetector.core.BleRadioMetadata
import com.classroom.rfdetector.core.ScanSource

/** BLE 광고 연속 스캔. LOW_LATENCY 로 사실상 연속 수신한다 */
class BleScanner(context: Context) {
    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var callback: ScanCallback? = null
    @Volatile var active: Boolean = false
        private set
    @Volatile var lastReceivedAtMs: Long? = null
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var usesExtendedAdvertisements: Boolean = false
        private set
    @Volatile var scanModeNotice: String? = null
        private set

    fun hasHardware(): Boolean = adapter != null

    fun isUsable(): Boolean = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }

    var onError: ((String) -> Unit)? = null

    fun start(onObs: (RfObservation) -> Unit): Boolean {
        stop()
        lastReceivedAtMs = null
        lastError = null
        scanModeNotice = null
        val ad = adapter ?: return fail("블루투스 기능이 없는 기기입니다")
        if (!isUsable()) return fail("블루투스가 꺼져 있거나 접근 권한이 없습니다")
        val scanner = try { ad.bluetoothLeScanner } catch (_: SecurityException) { null }
            ?: return fail("BLE 탐색을 열지 못했습니다 · 블루투스와 권한을 확인하세요")
        val startedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val extended = try { ad.isLeExtendedAdvertisingSupported } catch (_: SecurityException) {
            return fail("BLE 탐색 권한을 확인하세요")
        }
        if (!extended) scanModeNotice = "확장 광고 미지원 · 기본 BLE 광고를 수신합니다"
        return launchScan(scanner, startedAtElapsedNanos, extended, onObs)
    }

    private fun launchScan(scanner: BluetoothLeScanner, startedAtElapsedNanos: Long, extended: Boolean, onObs: (RfObservation) -> Unit): Boolean {
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (callback !== this || !active) return
                val observedElapsedNanos = result.timestampNanos
                val receivedElapsedNanos = SystemClock.elapsedRealtimeNanos()
                // Android defines this as observation time since boot, with no unknown/zero sentinel.
                // Do not assign an older room's packet or an unverifiable time to this session.
                if (observedElapsedNanos <= 0 || observedElapsedNanos < startedAtElapsedNanos || observedElapsedNanos > receivedElapsedNanos) return
                val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                val now = System.currentTimeMillis()
                val observedAt = now - (receivedElapsedNanos - observedElapsedNanos) / 1_000_000
                lastReceivedAtMs = maxOf(lastReceivedAtMs ?: observedAt, observedAt)
                lastError = null
                onObs(
                    RfObservation(
                        key = result.device.address,
                        source = ScanSource.BLE,
                        name = name,
                        rssi = result.rssi,
                        // AD Tx Power is radiated packet power, not calibrated RSSI at 1m.
                        txPower = null,
                        hidden = false,
                        timestampMs = observedAt,
                        manufacturerIds = result.scanRecord?.manufacturerSpecificData?.let { data ->
                            (0 until data.size()).map { data.keyAt(it) }.toSet()
                        } ?: emptySet(),
                        radioMetadata = RadioMetadata(ble = BleRadioMetadata(
                            serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }?.distinct()?.sorted() ?: emptyList(),
                            connectable = result.isConnectable,
                            legacy = result.isLegacy,
                            primaryPhy = result.primaryPhy,
                            secondaryPhy = result.secondaryPhy,
                        )),
                    )
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (callback !== this) return
                stop()
                if (extended) {
                    scanModeNotice = "확장 광고 탐색 실패 ($errorCode) · 기본 BLE 광고로 전환했습니다"
                    launchScan(scanner, startedAtElapsedNanos, false, onObs)
                } else fail("BLE 탐색 오류 ($errorCode) · 잠시 후 다시 시작하세요")
            }
        }
        return try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0L)
                .setLegacy(!extended)
                .apply { if (extended) setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }
                .build()
            callback = cb
            active = true
            usesExtendedAdvertisements = extended
            scanner.startScan(null, settings, cb)
            active
        } catch (e: SecurityException) {
            stop()
            fail("BLE 탐색 권한을 확인하세요")
        } catch (_: IllegalStateException) {
            stop()
            fail("블루투스 상태가 바뀌었습니다 · 다시 시작하세요")
        } catch (_: IllegalArgumentException) {
            stop()
            if (extended) {
                scanModeNotice = "확장 광고 설정 미지원 · 기본 BLE 광고로 전환했습니다"
                launchScan(scanner, startedAtElapsedNanos, false, onObs)
            } else fail("기본 BLE 광고 탐색 설정을 지원하지 않습니다")
        }
    }

    fun stop() {
        active = false
        usesExtendedAdvertisements = false
        val cb = callback ?: return
        callback = null
        val ad = adapter ?: return
        try {
            ad.bluetoothLeScanner?.stopScan(cb)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun fail(message: String): Boolean {
        active = false
        lastError = message
        onError?.invoke(message)
        return false
    }
}
