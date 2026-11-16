package com.classroom.rfdetector

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.net.Uri
import androidx.core.location.LocationManagerCompat
import com.classroom.rfdetector.core.DistanceClass
import com.classroom.rfdetector.core.DeviceIdentityResolver
import com.classroom.rfdetector.core.LocalizationEngine
import com.classroom.rfdetector.core.GeigerController
import com.classroom.rfdetector.core.MotionSensor
import com.classroom.rfdetector.core.DeviceTracker
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.RssiFilter
import com.classroom.rfdetector.core.TrackedDevice
import com.classroom.rfdetector.core.SignalPresence
import com.classroom.rfdetector.core.SignalSnapshot
import com.classroom.rfdetector.core.SnapshotStore
import com.classroom.rfdetector.core.ObservationUpdate
import com.classroom.rfdetector.core.ScanSession
import com.classroom.rfdetector.core.ScanSource
import com.classroom.rfdetector.core.CellEnvironmentSnapshot
import com.classroom.rfdetector.core.CellEnvironmentReading
import com.classroom.rfdetector.scanner.CellEnvironmentScanner
import com.classroom.rfdetector.core.analyzeWifiChannels
import com.classroom.rfdetector.core.wifiChannelLabel
import com.classroom.rfdetector.core.wifiChannelNumber
import com.classroom.rfdetector.scanner.BleScanner
import com.classroom.rfdetector.scanner.WifiScanner
import com.classroom.rfdetector.scanner.ClassicBluetoothScanner
import com.classroom.rfdetector.scanner.WifiDirectScanner
import com.classroom.rfdetector.scanner.BluetoothSweepCoordinator
import com.classroom.rfdetector.scanner.BluetoothSweepPhase
import com.classroom.rfdetector.sim.RadioSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.classroom.rfdetector.core.CheckpointStore
import com.classroom.rfdetector.core.BleAdvertisementJournal
import java.io.File
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Retains scan and comparison state across activity recreation; reception stops off-screen. */
class AppViewModel(private val app: Context) : ViewModel() {
    companion object {
        private val archiveDispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "rf-archive").apply { isDaemon = true } }.asCoroutineDispatcher()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Disk writes finish after the activity stops; scanning remains foreground-only.
    private val storageScope = CoroutineScope(SupervisorJob() + archiveDispatcher)
    private var closed = false

    val tracker = DeviceTracker()
    val engine = LocalizationEngine()
    val motion = MotionSensor(app)
    val geiger = GeigerController(app)
    val ble = BleScanner(app)
    private val bleJournal = BleAdvertisementJournal(app)
    val bleJournalStatus = bleJournal.status
    val bleJournalExportBusy = MutableStateFlow(false)
    val bleJournalExportMessage = MutableStateFlow<String?>(null)
    val wifi = WifiScanner(app, scope)
    val classic = ClassicBluetoothScanner(app)
    val wifiDirect = WifiDirectScanner(app, scope)
    val cellEnvironment = CellEnvironmentScanner(app, scope)
    private val _cellEnvironmentState = MutableStateFlow<CellEnvironmentSnapshot?>(null)
    val cellEnvironmentState: StateFlow<CellEnvironmentSnapshot?> = _cellEnvironmentState
    private val sessionSources = mutableSetOf<ScanSource>()
    val sim = RadioSimulator()
    val expandedDiscovery = MutableStateFlow(false)
    val bluetoothPhase = MutableStateFlow(BluetoothSweepPhase.STOPPED)
    private val bluetoothSweep = BluetoothSweepCoordinator(scope,
        startBle = { ble.start(::onObservation) }, stopBle = ble::stop,
        startClassic = { completed -> classic.start(::onObservation, completed) }, stopClassic = classic::stop,
        isClassicDiscoveryIdle = classic::isDiscoveryIdle,
        onPhase = ::recordBluetoothPhase,
        isBleActive = { ble.active }, bleRetryDelayMs = { ble.retryDelayMs })

    private val _devices = MutableStateFlow<List<TrackedDevice>>(emptyList())
    val devices: StateFlow<List<TrackedDevice>> = _devices

    val scanning = MutableStateFlow(false)
    val simMode = MutableStateFlow(false)
    val geigerOn = MutableStateFlow(false)
    val baselineSet = MutableStateFlow(false)
    val newCount = MutableStateFlow(0)
    val headingDeg = MutableStateFlow(0.0)
    val statusMsg = MutableStateFlow<String?>(null)
    val savedSnapshots = MutableStateFlow<List<SignalSnapshot>>(emptyList())
    val baselineSnapshot = MutableStateFlow<SignalSnapshot?>(null)
    val comparisonSnapshot = MutableStateFlow<SignalSnapshot?>(null)
    val snapshotBusy = MutableStateFlow(true)
    val snapshotError = MutableStateFlow<String?>(null)
    val latestNewSignal = MutableStateFlow<String?>(null)
    val latestNewWifiChannel = MutableStateFlow<String?>(null)
    private val announcedWifiFrequencies = mutableSetOf<Int>()
    val areaDraft = MutableStateFlow("시험실")
    val scanSession = MutableStateFlow<ScanSession?>(null)
    private val snapshotStore = SnapshotStore(app.filesDir)
    private var snapshotStorageReady = false
    val checkpoints = MutableStateFlow<List<SignalSnapshot>>(emptyList())
    val checkpointError = MutableStateFlow<String?>(null)
    val checkpointBusy = MutableStateFlow(false)
    private val checkpointStore = CheckpointStore(File(app.filesDir, "checkpoints").apply { mkdirs() })
    private val pendingCheckpoints = AtomicInteger(0)
    private var checkpointJob: Job? = null
    val healthTick = MutableStateFlow(0L)

    private val liveWifiChannels = devices.map { records ->
        records.filter { it.source == ScanSource.WIFI }.flatMap { it.wifiChannels }
    }.distinctUntilChanged()
    private val liveWifiSnapshot = combine(liveWifiChannels, scanSession, simMode,
        healthTick, areaDraft) { channels, session, simulation, tick, area ->
        val capturedAt = maxOf(tick, channels.maxOfOrNull { it.lastSeenMs } ?: 0, session?.startedAtMs ?: 0)
        SignalSnapshot("live-wifi", session?.area ?: area, "현재 채널 관측", capturedAt,
            session?.startedAtMs ?: capturedAt, simulation, emptyList(), wifiChannels = if (session == null) null else channels)
    }
    private val wifiArchive = combine(savedSnapshots, checkpoints) { manual, automatic -> manual + automatic }
    private val wifiComparisonTarget = combine(comparisonSnapshot, liveWifiSnapshot) { saved, live -> saved ?: live }.distinctUntilChanged()
    val wifiChannelAnalysis = combine(baselineSnapshot, wifiComparisonTarget, wifiArchive) { baseline, target, archive ->
        analyzeWifiChannels(baseline, target, archive)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000),
        analyzeWifiChannels(null, SignalSnapshot("live-wifi", "시험실", "현재 채널 관측", 0, 0, false, emptyList())))

    private var simJob: Job? = null
    private var refreshJob: Job? = null
    private var identityResolver = DeviceIdentityResolver(emptyMap(), emptyMap())

    init {
        scope.launch {
            try {
                val loaded = withContext(archiveDispatcher) { checkpointStore.load() }
                checkpoints.value = (loaded + checkpoints.value).groupBy { it.id }.values
                    .map { copies -> copies.maxBy { it.capturedAtMs } }.sortedByDescending { it.capturedAtMs }
            } catch (_: Exception) { checkpointError.value = "자동 보존 기록을 불러오지 못했습니다. 기존 파일을 유지합니다." }
        }
        scope.launch {
            try {
                savedSnapshots.value = withContext(archiveDispatcher) { snapshotStore.load() }
                snapshotStorageReady = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshotError.value = "저장 자료를 불러오지 못했습니다. 기존 파일은 변경하지 않습니다."
            } finally { snapshotBusy.value = false }
        }
        scope.launch {
            identityResolver = withContext(Dispatchers.IO) {
                fun assetRows(file: String) = app.assets.open(file).bufferedReader().useLines { lines ->
                    lines.filter { it.contains('\t') }.associate { line -> line.substringBefore('\t') to line.substringAfter('\t') }
                }
                DeviceIdentityResolver(assetRows("mac-vendors.tsv"), assetRows("bluetooth-companies.tsv").mapKeys { it.key.toInt() })
            }
        }
        if (!ble.hasHardware()) {
            simMode.value = true
            statusMsg.value = "이 기기에는 블루투스가 없어 시뮬레이션 모드로 시작합니다"
        }
        motion.onStep = {
            if (!simMode.value && scanning.value) engine.moveAll(0.0, motion.stepLengthM)
        }
        motion.onHeadingDelta = { d ->
            if (!simMode.value && scanning.value) engine.rotateAll(d)
        }
        wifi.onError = { msg -> statusMsg.value = msg }
        wifiDirect.onError = { msg -> statusMsg.value = msg }
        ble.onError = { msg ->
            statusMsg.value = msg
            scanSession.value?.takeIf { scanning.value && !simMode.value }?.let {
                bleJournal.recordScanState(it.id, System.currentTimeMillis(), "BLE_ERROR: $msg")
            }
        }
        ble.onAdvertisement = { event ->
            scanSession.value?.takeIf { scanning.value && !simMode.value }?.let {
                bleJournal.record(it.id, event)
            }
        }
        scope.launch {
            bleJournalStatus.map { it.error }.distinctUntilChanged().collect { error ->
                if (error != null) statusMsg.value = "BLE 광고 기록 확인 · $error"
            }
        }
        classic.onError = { msg -> statusMsg.value = msg }
    }

    /** API 레벨별로 런타임 요청이 필요한 권한 목록 */
    fun requiredPermissions(): List<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            p += "android.permission.BLUETOOTH_SCAN"
            p += "android.permission.BLUETOOTH_CONNECT"
        }
        p += "android.permission.ACCESS_COARSE_LOCATION"
        p += "android.permission.ACCESS_FINE_LOCATION"
        if (Build.VERSION.SDK_INT >= 33) p += "android.permission.NEARBY_WIFI_DEVICES"
        return p
    }

    /** WifiManager 스캔 결과에는 위치 서비스가 필요하다. */
    fun locationServicesOff(): Boolean {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return !LocationManagerCompat.isLocationEnabled(lm)
    }

    fun startScan() {
        if (scanning.value || closed) return
        tracker.clear()
        engine.clear()
        geiger.setClass(DistanceClass.UNKNOWN)
        _devices.value = emptyList()
        _cellEnvironmentState.value = null
        sessionSources.clear()
        sessionSources.addAll(requestedSources())
        newCount.value = 0
        statusMsg.value = null
        scanSession.value = ScanSession(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), areaDraft.value.trim().ifBlank { "시험실" })
        if (!simMode.value) {
            // The journal and summaries must agree on the room even before the first checkpoint.
            scanSession.value = scanSession.value!!.copy(areaLocked = true)
            try {
                bleJournal.beginSession(scanSession.value!!)
            } catch (_: IllegalStateException) {
                statusMsg.value = "BLE 기록 저장소를 시작하지 못했습니다 · 앱을 다시 열고 저장 공간을 확인하세요"
                return
            }
        }
        applyBaselineToSession()
        latestNewSignal.value = null
        latestNewWifiChannel.value = null
        announcedWifiFrequencies.clear()
        scanning.value = true
        if (simMode.value) {
            sim.reset()
            simJob = scope.launch {
                while (isActive) {
                    sim.tick().filter { expandedDiscovery.value || it.source != ScanSource.CLASSIC }.forEach { onObservation(it) }
                    delay(250)
                }
            }
            statusMsg.value = "시뮬레이션 모드: 가상 기기 5대 배치 — 조이스틱으로 걸어보세요"
        } else {
            if (!ble.isUsable()) {
                statusMsg.value = "블루투스 꺼짐/없음 — BLE 스캔 생략"
            }
            bluetoothSweep.start(expandedDiscovery.value)
            if (locationServicesOff()) {
                statusMsg.value = "위치 서비스가 꺼져 있어 Wi-Fi 결과가 안 보일 수 있음"
            }
            wifi.start(::onObservation)
            if (expandedDiscovery.value) {
                wifiDirect.start(::onObservation)
                cellEnvironment.start()
            }
        }
        motion.start()
        refreshJob = scope.launch {
            while (isActive) {
                refresh()
                delay(100)
            }
        }
        checkpointJob = scope.launch {
            while (isActive) {
                delay(5_000)
                preserveSession()
            }
        }
    }

    private fun onObservation(obs: RfObservation) {
        if (!scanning.value) return
        // Restarting optional discovery must not turn the same OS cache entry into RF reappearances.
        if (obs.source == ScanSource.P2P && tracker.contains(obs.trackingKey)) return
        val update = tracker.update(obs)
        announceWifiChannel(obs)
        if ((update == ObservationUpdate.FIRST_SEEN || update == ObservationUpdate.REAPPEARED) && baselineSet.value) {
            if (obs.trackingKey !in (tracker.baselineKeys ?: emptySet())) {
                val label = if (obs.source == ScanSource.WIFI && obs.hidden) "숨김 SSID · ${obs.key}" else obs.name ?: obs.key
                val kind = if (obs.source == ScanSource.P2P) "Wi-Fi Direct 목록 추가" else "새 신호 포착"
                latestNewSignal.value = "$kind · $label" + if (update == ObservationUpdate.REAPPEARED) " · 재등장" else ""
            }
        }
        if (update != ObservationUpdate.LATE) obs.rssi?.let { r ->
            val tx = obs.txPower?.toDouble() ?: RssiFilter.DEFAULT_TX_DBM
            engine.deviceFilter(obs.trackingKey).observeRssi(r.toDouble(), tx)
        }
    }

    private fun announceWifiChannel(observation: RfObservation) {
        if (observation.source != ScanSource.WIFI || !baselineSet.value) return
        val baseline = baselineSnapshot.value ?: return
        val frequencies = baseline.wifiChannels?.map { it.frequencyMhz }?.toSet() ?: return
        val frequency = observation.frequencyMhz ?: return
        if (observation.timestampMs <= baseline.capturedAtMs || wifiChannelNumber(frequency) == null || frequency in frequencies) return
        if (announcedWifiFrequencies.add(frequency)) latestNewWifiChannel.value = wifiChannelLabel(frequency) + if (observation.hidden) " · 숨김 SSID" else ""
    }

    fun stopScan() {
        if (!scanning.value) return
        refresh()
        preserveSession()
        scanning.value = false
        checkpointJob?.cancel(); checkpointJob = null
        simJob?.cancel(); simJob = null
        refreshJob?.cancel(); refreshJob = null
        bluetoothSweep.stop()
        wifi.stop()
        wifiDirect.stop()
        cellEnvironment.stop()
        if (!simMode.value) scanSession.value?.let { bleJournal.endSession(it.id, System.currentTimeMillis()) }
        motion.stop()
        if (geigerOn.value) setGeiger(false)
    }

    fun takeBaseline() {
        if (!scanning.value || devices.value.isEmpty()) return
        saveSnapshot(scanSession.value?.area ?: areaDraft.value, "기준점", asBaseline = true)
    }

    fun updateAreaDraft(value: String) {
        areaDraft.value = value.take(80)
        val session = scanSession.value ?: return
        if (!scanning.value || session.areaLocked || value.isBlank()) return
        scanSession.value = session.copy(area = areaDraft.value.trim())
        applyBaselineToSession()
        latestNewSignal.value = null
        latestNewWifiChannel.value = null
        announcedWifiFrequencies.clear()
        refresh()
    }

    private fun applyBaselineToSession() {
        val baseline = baselineSnapshot.value
        val session = scanSession.value
        val matches = baseline != null && session != null && baseline.simulation == simMode.value && baseline.area.equals(session.area, true)
        baselineSet.value = matches
        if (matches) tracker.setBaseline(baseline!!.signals.map { it.matchKey }.toSet()) else tracker.clearBaseline()
    }

    fun saveSnapshot(area: String, label: String, asBaseline: Boolean = false) {
        if (!scanning.value || snapshotBusy.value || !snapshotStorageReady) return
        if (area.isBlank() || label.isBlank()) { snapshotError.value = "공간 이름과 시점 이름을 입력해 주세요."; return }
        val current = scanSession.value ?: return
        val requestedArea = area.trim().take(80)
        if (current.areaLocked && !current.area.equals(requestedArea, true)) {
            snapshotError.value = "현재 탐색 공간은 ${current.area}입니다. 다른 공간을 기록하려면 탐색을 중지하고 새로 시작하세요."
            return
        }
        val session = current.copy(area = requestedArea, areaLocked = true)
        scanSession.value = session
        areaDraft.value = requestedArea
        applyBaselineToSession()
        refresh()
        val snapshot = SignalSnapshot.capture(java.util.UUID.randomUUID().toString(), area.trim().take(80), label.trim().take(80),
            System.currentTimeMillis(), session.startedAtMs, simMode.value, devices.value,
            includeNewHistorySinceMs = if (asBaseline || !baselineSet.value) null else baselineSnapshot.value?.capturedAtMs,
            includeAllHistory = asBaseline).withDiscoveryContext()
        val updated = listOf(snapshot) + savedSnapshots.value
        snapshotBusy.value = true
        snapshotError.value = null
        storageScope.launch {
            try {
                snapshotStore.save(updated)
                withContext(Dispatchers.Main.immediate) {
                    savedSnapshots.value = updated
                    if (!closed && scanSession.value?.id == session.id && snapshot.simulation == simMode.value) {
                        if (asBaseline) selectBaseline(snapshot) else comparisonSnapshot.value = snapshot
                    }
                    statusMsg.value = "스냅샷 저장됨 · ${snapshot.area} · ${snapshot.label} · ${snapshot.signals.size}개 신호"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshotError.value = "스냅샷을 저장하지 못했습니다. 기존 자료는 유지됩니다."
            } finally { snapshotBusy.value = false }
        }
    }

    fun selectBaseline(snapshot: SignalSnapshot) {
        if (snapshot.simulation != simMode.value) { snapshotError.value = "현재 탐색 모드와 같은 자료를 선택해 주세요."; return }
        baselineSnapshot.value = snapshot
        areaDraft.value = snapshot.area
        val session = scanSession.value
        if (scanning.value && session != null && !session.areaLocked) scanSession.value = session.copy(area = snapshot.area)
        applyBaselineToSession()
        snapshotError.value = if (scanSession.value?.area?.equals(snapshot.area, true) == false)
            "저장 기준은 ${snapshot.area}, 현재 수신 기록은 ${scanSession.value!!.area}입니다. 저장 자료끼리 비교하거나 새 공간에서 탐색을 시작하세요." else null
        latestNewSignal.value = null
        latestNewWifiChannel.value = null
        announcedWifiFrequencies.clear()
        refresh()
    }

    fun setGeiger(on: Boolean) {
        if (on && !scanning.value) return
        geigerOn.value = on
        if (on) {
            geiger.start()
        } else {
            geiger.stop()
        }
    }

    fun setSim(on: Boolean) {
        if (simMode.value == on) return
        if (scanning.value) stopScan()
        simMode.value = on
        tracker.clear()
        engine.clear()
        _devices.value = emptyList()
        baselineSet.value = false
        baselineSnapshot.value = null
        comparisonSnapshot.value = null
        scanSession.value = null
        _cellEnvironmentState.value = null
        sessionSources.clear()
        latestNewSignal.value = null
        latestNewWifiChannel.value = null
        announcedWifiFrequencies.clear()
        newCount.value = 0
        statusMsg.value = if (on) "연습 모드 · 가상 기기로 탐색 흐름을 체험하세요" else "실제 탐색 · BLE와 Wi-Fi 신호를 확인합니다"
    }

    fun setExpandedDiscovery(enabled: Boolean) {
        if (expandedDiscovery.value == enabled) return
        expandedDiscovery.value = enabled
        if (scanning.value) sessionSources.addAll(requestedSources())
        if (!scanning.value || simMode.value) return
        bluetoothSweep.start(enabled)
        if (enabled) {
            wifiDirect.start(::onObservation)
            cellEnvironment.start()
        } else {
            wifiDirect.stop()
            cellEnvironment.stop()
        }
    }

    fun simAddExtendedDevices() {
        if (!scanning.value || !simMode.value || !expandedDiscovery.value) return
        val now = System.currentTimeMillis()
        _cellEnvironmentState.value = CellEnvironmentSnapshot(now, listOf(
            CellEnvironmentReading("LTE", "가상 기지국 · 실제 수신 아님", 1650, -85, true, now)),
            note = "가상 모뎀 환경 · 주변 휴대전화 목록이 아닙니다")
        if (sim.devices.none { it.key == "sim-extra-classic" }) sim.devices.add(
            RadioSimulator.VDev("sim-extra-classic", "WH-1000XM5 · 일반 Bluetooth 가상", ScanSource.CLASSIC, false, sim.userX + 2.0, sim.userY))
        if (tracker.snapshot(System.currentTimeMillis(), true).none { it.key == "sim-extra-p2p" && it.source == ScanSource.P2P }) onObservation(RfObservation("sim-extra-p2p", ScanSource.P2P,
            "Wi-Fi Direct · 가상 목록", null, null, false, System.currentTimeMillis()))
    }

    /** 시뮬레이터 조이스틱: 정면/우측 미터 단위 이동 */
    fun simMove(forward: Double, right: Double) {
        if (!scanning.value || !simMode.value) return
        sim.moveUser(right, forward)
        engine.moveAll(right, forward)
    }

    fun simRotate(deltaDeg: Double) {
        if (!scanning.value || !simMode.value) return
        sim.rotateUser(deltaDeg)
        engine.rotateAll(deltaDeg)
    }

    fun simAddDevice() {
        if (scanning.value && simMode.value) sim.addDevice()
    }

    fun simAddWifiDevice(hidden: Boolean = false) {
        if (!scanning.value || !simMode.value) return
        val index = sim.devices.count { it.key.startsWith("sim-extra-wifi-") } + 1
        sim.devices.add(RadioSimulator.VDev("sim-extra-wifi-$index", "추가 핫스팟 · 가상 $index", ScanSource.WIFI,
            hidden, sim.userX + 1.5, sim.userY + 1.5, frequencyMhz = 2462))
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        healthTick.value = now
        if (!simMode.value && expandedDiscovery.value) {
            _cellEnvironmentState.value = cellEnvironment.snapshot(cellEnvironment.lastUpdatedMs ?: scanSession.value?.startedAtMs ?: now)
        }
        val list = tracker.snapshot(now, includeInactive = true).map { d ->
            val est = if (d.presence == SignalPresence.RECENT) engine.deviceFilter(d.trackingKey).estimate() else null
            if (d.presence == SignalPresence.HISTORY) engine.remove(d.trackingKey)
            d.copy(
                bearingDeg = est?.first,
                bearingConfidence = est?.second ?: 0.0,
                identity = identityResolver.resolve(d.key, d.displayName, setOf(d.source), d.manufacturerIds),
            )
        }
        devices.value.filter { old -> list.none { it.trackingKey == old.trackingKey } }.forEach { engine.remove(it.trackingKey) }
        _devices.value = list
        newCount.value = list.count { it.isBaselineNew }
        headingDeg.value = if (simMode.value) sim.userHeadingDeg else motion.headingDeg
        geiger.setClass(
            list.filter { it.distanceM != null && it.presence == SignalPresence.RECENT }
                .minByOrNull { it.distanceM!! }
                ?.distanceClass ?: DistanceClass.UNKNOWN
        )
    }

    fun shutdown() {
        if (closed) return
        stopScan()
        closed = true
        scope.cancel()
        // An outstanding cloud/file export may be slow; do not hold the shared snapshot writer.
        storageScope.launch(Dispatchers.IO) { bleJournal.close() }
    }

    override fun onCleared() { shutdown() }

    private fun recordBluetoothPhase(phase: BluetoothSweepPhase) {
        if (bluetoothPhase.value == phase) return
        bluetoothPhase.value = phase
        scanSession.value?.takeIf { scanning.value && !simMode.value }?.let {
            bleJournal.recordScanState(it.id, System.currentTimeMillis(), phase.name)
        }
    }

    fun exportBleJournal(uri: Uri?) {
        if (uri == null || bleJournalExportBusy.value || closed) return
        bleJournalExportBusy.value = true
        bleJournalExportMessage.value = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "wt")
                        ?: error("저장 위치를 열 수 없습니다")
                    output.use { bleJournal.exportTo(it) }
                }
                bleJournalExportMessage.value = "BLE 광고 기록 파일을 저장했습니다"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                bleJournalExportMessage.value = "광고 기록 내보내기 실패 · 저장 위치와 공간을 확인하세요. 불완전한 출력 파일이 남을 수 있습니다."
            } finally { bleJournalExportBusy.value = false }
        }
    }

    private fun preserveSession() {
        val session = scanSession.value ?: return
        scanSession.value = session.copy(areaLocked = true)
        val now = System.currentTimeMillis()
        val record = SignalSnapshot.capture("auto-${session.id}", session.area, "자동 보존 · 전체 수신 이력", now,
            session.startedAtMs, simMode.value, devices.value, includeAllHistory = true).withDiscoveryContext()
        checkpointBusy.value = true
        pendingCheckpoints.incrementAndGet()
        storageScope.launch {
            try {
                val stored = checkpointStore.upsert(record)
                withContext(Dispatchers.Main.immediate) {
                    checkpoints.value = stored
                    checkpointError.value = null
                }
            } catch (_: Exception) { withContext(Dispatchers.Main.immediate) { checkpointError.value = "자동 보존 실패 · 비교 화면에서 수동 저장을 시도하세요." } }
            finally { withContext(Dispatchers.Main.immediate) { checkpointBusy.value = pendingCheckpoints.decrementAndGet() > 0 } }
        }
    }

    private fun requestedSources(): Set<ScanSource> = if (expandedDiscovery.value)
        setOf(ScanSource.BLE, ScanSource.WIFI, ScanSource.CLASSIC, ScanSource.P2P)
        else setOf(ScanSource.BLE, ScanSource.WIFI)

    fun SignalSnapshot.withDiscoveryContext(): SignalSnapshot = copy(
        cellEnvironment = cellEnvironmentState.value,
        discoverySources = sessionSources.toSet(),
    )
}
