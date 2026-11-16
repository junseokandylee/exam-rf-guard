package com.classroom.rfdetector.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.classroom.rfdetector.core.CellEnvironmentReading
import com.classroom.rfdetector.core.CellEnvironmentSnapshot
import com.classroom.rfdetector.core.CellEnvironmentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Public Android 29+ cell information from the default TelephonyManager subscription.
 * Requires ACCESS_FINE_LOCATION, not READ_PHONE_STATE. No subscriber/device identifiers, SIM
 * numbers, phone numbers, cell coordinates, connections or neighboring-phone discovery are used.
 * https://developer.android.com/reference/android/telephony/TelephonyManager#requestCellInfoUpdate(java.util.concurrent.Executor,android.telephony.TelephonyManager.CellInfoCallback)
 *
 * Android may return cached data or rate-limit updates. Its public error codes distinguish timeout
 * and modem failure, but not throttling; cached timestamps must never be called a fresh RF sample.
 */
class CellEnvironmentScanner(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    @Volatile var supported: Boolean = manager != null && (
        app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
            (Build.VERSION.SDK_INT >= 33 && app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)))
        private set
    @Volatile var active: Boolean = false
        private set
    /** Latest successful list callback time. Individual readings retain their modem timestamps. */
    @Volatile var lastUpdatedMs: Long? = null
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var readings: List<CellEnvironmentReading> = emptyList()
        private set
    @Volatile var status: CellEnvironmentStatus = CellEnvironmentStatus.IDLE
        private set
    @Volatile var note: String? = null
        private set
    var onError: ((String) -> Unit)? = null

    private var generation = 0L
    private var requestSerial = 0L
    private var pendingRequest: Long? = null
    private var job: Job? = null
    private var timeoutJob: Job? = null

    @MainThread
    fun start() {
        stop()
        readings = emptyList()
        lastUpdatedMs = null
        lastError = null
        note = null
        if (!supported) {
            reportFailure(CellEnvironmentStatus.UNSUPPORTED, "이 기기는 이동통신 기지국 정보 조회를 지원하지 않습니다")
            return
        }
        if (!hasPermission()) {
            reportFailure(CellEnvironmentStatus.PERMISSION_DENIED, PERMISSION_ERROR)
            return
        }
        active = true
        status = CellEnvironmentStatus.UPDATING
        val token = generation
        job = scope.launch {
            while (isActive && isCurrent(token)) {
                request(token)
                if (!isCurrent(token)) break
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    @MainThread
    fun stop() {
        generation++
        active = false
        pendingRequest = null
        job?.cancel()
        job = null
        timeoutJob?.cancel()
        timeoutJob = null
    }

    /** Keep this separate from device snapshots, unions, alerts, and physical-device counts. */
    fun snapshot(capturedAtMs: Long = System.currentTimeMillis()): CellEnvironmentSnapshot = CellEnvironmentSnapshot(
        capturedAtMs, readings.toList(), status, note, lastError,
    )

    private fun request(token: Long) {
        if (!hasPermission()) {
            stop()
            reportFailure(CellEnvironmentStatus.PERMISSION_DENIED, PERMISSION_ERROR)
            return
        }
        // SIM_STATE_ABSENT is an explicit platform fact; an empty cell list is not evidence of it.
        val simAbsent = try { manager?.simState == TelephonyManager.SIM_STATE_ABSENT } catch (_: RuntimeException) { false }
        if (simAbsent) {
            readings = emptyList()
            status = CellEnvironmentStatus.NO_SIM
            lastError = null
            note = "기본 SIM이 없습니다 · 주변 휴대전화 유무를 뜻하지 않습니다"
            return
        }
        status = CellEnvironmentStatus.UPDATING
        val requestId = ++requestSerial
        pendingRequest = requestId
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        timeoutJob = scope.launch {
            delay(RESPONSE_TIMEOUT_MS)
            if (isPending(token, requestId)) {
                finishRequest()
                reportFailure(CellEnvironmentStatus.TIMEOUT, "기지국 정보 응답 시간이 초과됐습니다 · 다음 주기에 다시 확인합니다")
            }
        }
        try {
            manager?.requestCellInfoUpdate(app.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    if (!isPending(token, requestId)) return
                    finishRequest()
                    val capturedAt = System.currentTimeMillis()
                    readings = cellEnvironmentReadings(cellInfo, capturedAt, SystemClock.elapsedRealtimeNanos(), requestedAt)
                    lastUpdatedMs = capturedAt
                    lastError = null
                    when {
                        readings.isEmpty() -> {
                            status = CellEnvironmentStatus.EMPTY
                            note = "사용 가능한 기지국 정보가 없습니다 · 주변 휴대전화가 없다는 뜻은 아닙니다"
                        }
                        readings.any { it.cached } -> {
                            status = CellEnvironmentStatus.CACHED
                            note = "요청 전 모뎀 관측값이 포함돼 있습니다 · Android 갱신 제한 가능, 원래 관측 시각 유지"
                        }
                        else -> {
                            status = CellEnvironmentStatus.AVAILABLE
                            note = "이 휴대전화가 관측한 기지국 정보입니다 · 주변 휴대전화 목록이 아닙니다"
                        }
                    }
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    if (!isPending(token, requestId)) return
                    finishRequest()
                    if (detail is SecurityException) {
                        stop()
                        reportFailure(CellEnvironmentStatus.PERMISSION_DENIED, PERMISSION_ERROR)
                    } else if (errorCode == TelephonyManager.CellInfoCallback.ERROR_TIMEOUT) {
                        reportFailure(CellEnvironmentStatus.TIMEOUT, "기지국 정보 응답 시간이 초과됐습니다 · 다음 주기에 다시 확인합니다")
                    } else {
                        reportFailure(CellEnvironmentStatus.MODEM_ERROR, "모뎀이 기지국 정보를 반환하지 못했습니다 · 다음 주기에 다시 확인합니다")
                    }
                }
            })
        } catch (_: SecurityException) {
            stop()
            reportFailure(CellEnvironmentStatus.PERMISSION_DENIED, PERMISSION_ERROR)
        } catch (_: UnsupportedOperationException) {
            supported = false
            stop()
            reportFailure(CellEnvironmentStatus.UNSUPPORTED, "이 기기는 이동통신 기지국 정보 조회를 지원하지 않습니다")
        } catch (_: RuntimeException) {
            finishRequest()
            reportFailure(CellEnvironmentStatus.MODEM_ERROR, "기지국 정보를 요청하지 못했습니다 · 다음 주기에 다시 확인합니다")
        }
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun isCurrent(token: Long): Boolean = active && generation == token
    private fun isPending(token: Long, requestId: Long): Boolean = isCurrent(token) && pendingRequest == requestId

    private fun finishRequest() {
        pendingRequest = null
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun reportFailure(state: CellEnvironmentStatus, message: String) {
        status = state
        lastError = message
        note = if (readings.isEmpty()) null else "마지막 성공한 조회 결과를 유지합니다 · 관측 시각을 확인하세요"
        onError?.invoke(message)
    }

    companion object {
        internal const val UPDATE_INTERVAL_MS = 60_000L
        internal const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val PERMISSION_ERROR = "기지국 정보를 확인하려면 정확한 위치 권한이 필요합니다"
    }
}

/** Timestamp conversion and public radio-field extraction; no TelephonyManager identity queries. */
@Suppress("DEPRECATION")
internal fun cellEnvironmentReadings(
    cells: List<CellInfo>,
    capturedAtMs: Long,
    elapsedRealtimeNanos: Long,
    requestedAtElapsedNanos: Long,
): List<CellEnvironmentReading> = cells.mapNotNull { cell ->
    val timestamp = cell.timeStamp
    if (timestamp <= 0 || timestamp > elapsedRealtimeNanos) return@mapNotNull null
    val observedAt = capturedAtMs - (elapsedRealtimeNanos - timestamp) / 1_000_000L
    if (observedAt <= 0) return@mapNotNull null
    val fields = when (cell) {
        is CellInfoLte -> cell.cellIdentity.let { id -> CellFields("LTE",
            "LTE|mcc=${id.mccString.orUnknown()}|mnc=${id.mncString.orUnknown()}|tac=${id.tac.known()}|ci=${id.ci.known()}",
            id.earfcn.available(), cell.cellSignalStrength.dbm.availableDbm()) }
        is CellInfoNr -> (cell.cellIdentity as? CellIdentityNr)?.let { id -> CellFields("NR",
            "NR|mcc=${id.mccString.orUnknown()}|mnc=${id.mncString.orUnknown()}|tac=${id.tac.known()}|nci=${id.nci.known()}",
            id.nrarfcn.available(), cell.cellSignalStrength.dbm.availableDbm()) }
        is CellInfoWcdma -> cell.cellIdentity.let { id -> CellFields("WCDMA",
            "WCDMA|mcc=${id.mccString.orUnknown()}|mnc=${id.mncString.orUnknown()}|lac=${id.lac.known()}|cid=${id.cid.known()}",
            id.uarfcn.available(), cell.cellSignalStrength.dbm.availableDbm()) }
        is CellInfoGsm -> cell.cellIdentity.let { id -> CellFields("GSM",
            "GSM|mcc=${id.mccString.orUnknown()}|mnc=${id.mncString.orUnknown()}|lac=${id.lac.known()}|cid=${id.cid.known()}",
            id.arfcn.available(), cell.cellSignalStrength.dbm.availableDbm()) }
        is CellInfoCdma -> cell.cellIdentity.let { id -> CellFields("CDMA",
            "CDMA|sid=${id.systemId.known()}|nid=${id.networkId.known()}|bsid=${id.basestationId.known()}",
            null, cell.cellSignalStrength.dbm.availableDbm()) }
        else -> null
    } ?: return@mapNotNull null
    CellEnvironmentReading(fields.technology, fields.identity, fields.channel, fields.dbm, cell.isRegistered,
        observedAt, cached = timestamp < requestedAtElapsedNanos)
}.distinct().sortedWith(compareByDescending<CellEnvironmentReading> { it.registered }.thenBy { it.technology }.thenBy { it.identity })

private data class CellFields(val technology: String, val identity: String, val channel: Int?, val dbm: Int?)
private fun Int.available(): Int? = takeIf { it >= 0 && it != CellInfo.UNAVAILABLE }
private fun Int.availableDbm(): Int? = takeIf { it in -200..-1 }
private fun Int.known(): String = available()?.toString() ?: "?"
private fun Long.known(): String = takeIf { it >= 0 && it != CellInfo.UNAVAILABLE_LONG }?.toString() ?: "?"
private fun String?.orUnknown(): String = this?.takeIf { it.isNotBlank() } ?: "?"
