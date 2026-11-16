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
import com.classroom.rfdetector.core.BleAdvertisementEvent
import com.classroom.rfdetector.core.BleRecordDisposition
import com.classroom.rfdetector.core.ScanSource
import com.classroom.rfdetector.core.identifyBeaconFrames

/** 저지연 연속 탐색을 요청한다. OS가 전달하는 결과가 주변 전파 전체의 수신을 보장하지는 않는다. */
class BleScanner(context: Context) {
    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var callback: ScanCallback? = null
    private var registeredScanner: BluetoothLeScanner? = null
    @Volatile var active: Boolean = false
        private set
    @Volatile var lastReceivedAtMs: Long? = null
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastErrorCode: Int? = null
        private set
    /** Suggested minimum wait for the session owner; never restarts a scan by itself. */
    val retryDelayMs: Long?
        get() = when (lastErrorCode) {
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED,
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR,
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> 5_000L
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> 30_000L
            else -> null
        }
    @Volatile var usesExtendedAdvertisements: Boolean = false
        private set
    @Volatile var scanModeNotice: String? = null
        private set

    fun hasHardware(): Boolean = adapter != null

    fun isUsable(): Boolean = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }

    var onError: ((String) -> Unit)? = null
    var onAdvertisement: ((BleAdvertisementEvent) -> Unit)? = null

    fun start(onObs: (RfObservation) -> Unit): Boolean {
        stop()
        lastReceivedAtMs = null
        lastError = null
        lastErrorCode = null
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
                val (event, observation) = decodeResult(result, startedAtElapsedNanos)
                onAdvertisement?.invoke(event)
                // A consumer may stop or replace this session while recording the event.
                if (callback !== this || !active || observation == null) return
                lastReceivedAtMs = maxOf(lastReceivedAtMs ?: observation.timestampMs, observation.timestampMs)
                lastError = null
                lastErrorCode = null
                onObs(observation)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (callback !== this) return
                stop()
                if (extended && errorCode == SCAN_FAILED_FEATURE_UNSUPPORTED) {
                    scanModeNotice = "확장 광고 탐색 실패 ($errorCode) · 기본 BLE 광고로 전환했습니다"
                    launchScan(scanner, startedAtElapsedNanos, false, onObs)
                } else failScan(errorCode)
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
            registeredScanner = scanner
            active = true
            usesExtendedAdvertisements = extended
            // No identity, service, company, RSSI, or connectable filter: unnamed beacons count too.
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

    private fun decodeResult(result: ScanResult, startedAtElapsedNanos: Long): Pair<BleAdvertisementEvent, RfObservation?> {
        val receivedAtMs = System.currentTimeMillis()
        val receivedElapsedNanos = SystemClock.elapsedRealtimeNanos()
        var event = BleAdvertisementEvent(
            receivedAtMs = receivedAtMs, observedAtMs = null, timestampNanos = 0,
            address = null, name = null, rssi = null, rawRecordHex = null,
            dataStatus = null, primaryPhy = null, secondaryPhy = null, advertisingSid = null,
            disposition = BleRecordDisposition.DECODE_ERROR,
        )
        val observation = try {
            event = event.copy(timestampNanos = result.timestampNanos)
            val record = result.scanRecord
            // Preserve the OS-provided bytes before parsing optional identity metadata.
            event = event.copy(rawRecordHex = record?.bytes?.toHex())
            event = event.copy(address = result.device.address, rssi = result.rssi)
            event = event.copy(
                name = record?.deviceName?.takeIf { it.isNotBlank() },
                dataStatus = result.dataStatus, primaryPhy = result.primaryPhy,
                secondaryPhy = result.secondaryPhy, advertisingSid = result.advertisingSid,
            )
            val manufacturerData = record?.manufacturerSpecificData?.let { data ->
                (0 until data.size()).associate { data.keyAt(it) to data.valueAt(it) }
            } ?: emptyMap()
            val serviceData = record?.serviceData?.mapKeys { it.key.uuid.toString() } ?: emptyMap()
            val metadata = BleRadioMetadata(
                serviceUuids = record?.serviceUuids?.map { it.uuid.toString() }?.distinct()?.sorted() ?: emptyList(),
                connectable = result.isConnectable, legacy = result.isLegacy,
                primaryPhy = event.primaryPhy, secondaryPhy = event.secondaryPhy,
                beaconFormats = identifyBeaconFrames(manufacturerData, serviceData).map { it.format }.distinct(),
            )
            // Unverifiable or pre-session times stay in the raw journal, never in room comparison.
            val disposition = when {
                event.timestampNanos <= 0 || event.timestampNanos > receivedElapsedNanos -> BleRecordDisposition.INVALID_TIMESTAMP
                event.timestampNanos < startedAtElapsedNanos -> BleRecordDisposition.BEFORE_SCAN
                else -> BleRecordDisposition.ACCEPTED
            }
            val observedAtMs = if (disposition == BleRecordDisposition.ACCEPTED) {
                receivedAtMs - (receivedElapsedNanos - event.timestampNanos) / 1_000_000
            } else null
            event = event.copy(observedAtMs = observedAtMs, disposition = disposition)
            observedAtMs?.let { observedAt ->
                RfObservation(
                    key = requireNotNull(event.address), source = ScanSource.BLE,
                    // Preserve invalid RSSI in the raw event, but never turn it into a distance.
                    name = event.name, rssi = event.rssi?.takeIf { it in -127..126 },
                    // AD Tx Power is radiated packet power, not calibrated RSSI at 1m.
                    txPower = null, hidden = false, timestampMs = observedAt,
                    manufacturerIds = manufacturerData.keys,
                    radioMetadata = RadioMetadata(ble = metadata),
                )
            }
        } catch (_: RuntimeException) {
            // One malformed/unsupported result must not abort the rest of an OS batch.
            event = event.copy(observedAtMs = null, disposition = BleRecordDisposition.DECODE_ERROR)
            null
        }
        return event to observation
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = digits[value ushr 4]
            output[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(output)
    }

    fun stop() {
        active = false
        usesExtendedAdvertisements = false
        val cb = callback
        val scanner = registeredScanner
        callback = null
        registeredScanner = null
        if (cb == null || scanner == null) return
        try {
            scanner.stopScan(cb)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun failScan(errorCode: Int): Boolean {
        val reason = when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "이미 진행 중인 탐색이 있습니다 · 중지 후 다시 시작하세요"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android에 탐색을 등록하지 못했습니다 · 잠시 후 다시 시도하세요"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth 내부 오류입니다 · 잠시 후 다시 시도하세요"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "이 기기가 기본 BLE 탐색 설정을 지원하지 않습니다"
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Bluetooth 탐색 자원이 부족합니다 · 잠시 후 다시 시도하세요"
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Android가 잦은 탐색 시작을 제한했습니다 · 30초 이상 기다려 주세요"
            else -> "원인을 확인하지 못했습니다 · Bluetooth 상태를 확인하세요"
        }
        return fail("BLE 탐색 오류 ($errorCode) · $reason", errorCode)
    }

    private fun fail(message: String, errorCode: Int? = null): Boolean {
        active = false
        lastError = message
        lastErrorCode = errorCode
        onError?.invoke(message)
        return false
    }
}
