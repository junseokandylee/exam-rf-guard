package com.classroom.rfdetector.core

import java.util.concurrent.ConcurrentHashMap

enum class ObservationUpdate { FIRST_SEEN, CURRENT, LATE, REAPPEARED }

/** Keep each radio/address independent, including its name, strength and history. */
class DeviceTracker {
    private class Track(val key: String, val source: ScanSource) {
        val trackingKey: String get() = radioKey(source, key)
        var name: String? = null
        var hidden = false
        var everHidden = false
        var radioMetadata: RadioMetadata? = null
        val beaconFormats = LinkedHashSet<BeaconFormat>()
        val manufacturerIds = LinkedHashSet<Int>()
        var ema: Double? = null
        var tx: Double? = null
        var periods: List<ObservationPeriod> = emptyList()
        val history = ArrayDeque<RssiPoint>()
        var firstSeen = 0L
        var lastSeen = 0L
        var observationCount = 0
        var peakRssi: Int? = null
        val wifiChannels = mutableMapOf<Int, WifiChannelObservation>()
    }

    private val tracks = ConcurrentHashMap<String, Track>()

    /** null 이면 아직 기준점을 안 잡음. 잡힌 이후 등장한 key 가 NEW 경보 대상 */
    @Volatile
    var baselineKeys: Set<String>? = null

    @Synchronized
    fun update(obs: RfObservation): ObservationUpdate {
        val firstObservation = !tracks.containsKey(obs.trackingKey)
        val t = tracks.getOrPut(obs.trackingKey) {
            Track(obs.key, obs.source).also { it.firstSeen = obs.timestampMs }
        }
        val late = !firstObservation && obs.timestampMs < t.lastSeen
        t.firstSeen = minOf(t.firstSeen, obs.timestampMs)
        t.lastSeen = maxOf(t.lastSeen, obs.timestampMs)
        t.observationCount++
        val previousPeriodCount = t.periods.size
        t.periods = addToPeriods(t.periods, obs.timestampMs, obs.source.historyGapMs())
        t.manufacturerIds.addAll(obs.manufacturerIds)
        updateWifiChannel(t, obs)
        if (!late || t.name == null) obs.name?.takeIf { it.isNotBlank() }?.let { t.name = it }
        if (!late) t.hidden = obs.hidden
        if (!late) t.radioMetadata = obs.radioMetadata?.snapshotCopy()
        if (obs.source == ScanSource.BLE) {
            t.beaconFormats.addAll(obs.radioMetadata?.ble?.beaconFormats.orEmpty())
            if (t.beaconFormats.isNotEmpty()) {
                val latest = t.radioMetadata ?: RadioMetadata()
                t.radioMetadata = latest.copy(ble = (latest.ble ?: BleRadioMetadata()).copy(beaconFormats = t.beaconFormats.toList()))
            }
        }
        t.everHidden = t.everHidden || obs.hidden
        obs.rssi?.let { r ->
            if (!late) {
                t.ema = RssiFilter.ema(if (t.periods.size > previousPeriodCount) null else t.ema, r)
                t.tx = obs.txPower?.takeIf { it in -100..20 && it != 127 }?.toDouble() ?: t.tx
            }
            t.history.addLast(RssiPoint(obs.timestampMs, r))
            if (late) t.history.sortBy { it.tsMs }
            t.peakRssi = maxOf(t.peakRssi ?: r, r)
            while (t.history.size > 512 || (t.history.size > 1 && t.lastSeen - t.history.first().tsMs > 10_000)) t.history.removeFirst()
        }
        return when {
            firstObservation -> ObservationUpdate.FIRST_SEEN
            late -> ObservationUpdate.LATE
            t.periods.size > previousPeriodCount -> ObservationUpdate.REAPPEARED
            else -> ObservationUpdate.CURRENT
        }
    }

    @Synchronized
    fun snapshot(nowMs: Long, includeInactive: Boolean = false): List<TrackedDevice> {
        val base = baselineKeys
        return tracks.values.filter { includeInactive || nowMs - it.lastSeen <= 90_000L }.map { t ->
            val rssi = t.ema
            val dist = rssi?.let { RssiFilter.distanceM(it, t.tx ?: RssiFilter.DEFAULT_TX_DBM) }
            val ageMs = (nowMs - t.lastSeen).coerceAtLeast(0)
            val presence = signalPresence(t.source, t.lastSeen, nowMs)
            TrackedDevice(
                key = t.key,
                displayName = t.name ?: defaultName(t),
                source = t.source,
                hidden = t.hidden,
                rssiEma = rssi,
                trendDbPer10s = RssiFilter.trendDbPer10s(t.history.toList()),
                distanceM = dist,
                distanceClass = RssiFilter.classify(dist),
                firstSeenMs = t.firstSeen,
                lastSeenMs = t.lastSeen,
                isBaselineNew = base != null && t.trackingKey !in base,
                bearingDeg = null,
                bearingConfidence = 0.0,
                history = t.history.toList(),
                manufacturerIds = t.manufacturerIds.toSet(),
                presence = presence,
                lastSeenAgeSeconds = ageMs / 1000,
                observationCount = t.observationCount,
                peakRssi = t.peakRssi,
                periods = t.periods.toList(),
                referenceRssiAt1m = t.tx,
                wifiChannels = t.wifiChannels.values.sortedBy { it.frequencyMhz },
                everHidden = t.everHidden,
                radioMetadata = t.radioMetadata?.snapshotCopy(),
            )
        }.sortedWith(
            compareByDescending<TrackedDevice> { it.isBaselineNew }
                .thenBy { it.presence.ordinal }
                .thenByDescending { it.rssiEma ?: Double.NEGATIVE_INFINITY }
        )
    }

    private fun defaultName(t: Track): String = when {
        t.hidden -> "이름 없는 AP"
        t.source == ScanSource.WIFI -> "Wi-Fi 기기 " + t.key.takeLast(5)
        t.source == ScanSource.P2P -> "Wi-Fi Direct 목록 " + t.key.takeLast(5)
        t.source == ScanSource.CLASSIC -> "일반 Bluetooth 기기 " + t.key.takeLast(5)
        else -> "BLE 기기 " + t.key.takeLast(5)
    }

    private fun updateWifiChannel(track: Track, obs: RfObservation) {
        if (obs.source != ScanSource.WIFI) return
        val frequency = obs.frequencyMhz?.takeIf { it > 0 } ?: return
        val previous = track.wifiChannels[frequency]
        val lateForFrequency = previous != null && obs.timestampMs < previous.lastSeenMs
        val name = obs.name?.takeIf { it.isNotBlank() }
        val fallbackName = if (obs.hidden) "이름 없는 AP" else "Wi-Fi 기기 " + track.key.takeLast(5)
        track.wifiChannels[frequency] = WifiChannelObservation(
            address = track.key,
            name = if (lateForFrequency) previous!!.name else name ?: previous?.name ?: fallbackName,
            frequencyMhz = frequency,
            firstSeenMs = minOf(previous?.firstSeenMs ?: obs.timestampMs, obs.timestampMs),
            lastSeenMs = maxOf(previous?.lastSeenMs ?: obs.timestampMs, obs.timestampMs),
            observationCount = (previous?.observationCount ?: 0) + 1,
            lastRssi = if (lateForFrequency) previous!!.lastRssi else obs.rssi,
            peakRssi = listOfNotNull(previous?.peakRssi, obs.rssi).maxOrNull(),
            periods = addToPeriods(previous?.periods ?: emptyList(), obs.timestampMs, ScanSource.WIFI.historyGapMs()),
            hidden = if (lateForFrequency) previous!!.hidden else obs.hidden,
            everHidden = previous?.everHidden == true || obs.hidden,
            wifiMetadata = if (lateForFrequency) previous!!.wifiMetadata else obs.radioMetadata?.wifi,
        )
    }

    @Synchronized
    fun takeBaseline() {
        baselineKeys = tracks.values.map { it.trackingKey }.toSet()
    }

    @Synchronized
    fun setBaseline(keys: Set<String>) { baselineKeys = keys.toSet() }

    @Synchronized
    fun clearBaseline() { baselineKeys = null }

    @Synchronized
    fun clear() {
        tracks.clear()
        baselineKeys = null
    }

    fun size(): Int = tracks.size
    fun contains(trackingKey: String): Boolean = tracks.containsKey(trackingKey)
}

