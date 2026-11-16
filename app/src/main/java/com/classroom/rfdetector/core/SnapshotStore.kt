package com.classroom.rfdetector.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import org.json.JSONArray
import org.json.JSONObject

/** Local, versioned storage. Atomic writes keep the previous file intact on failure. */
class SnapshotStore(private val directory: File) {
    private val file = File(directory, "signal-snapshots.json")

    fun load(): List<SignalSnapshot> {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        require(root.getInt("version") in 1..3) { "지원하지 않는 스냅샷 파일 버전입니다." }
        val snapshots = root.getJSONArray("snapshots")
        return (0 until snapshots.length()).map { index -> decode(snapshots.getJSONObject(index)) }
    }

    fun save(snapshots: List<SignalSnapshot>) {
        val root = JSONObject().put("version", 3).put("snapshots", JSONArray().apply { snapshots.forEach { put(encode(it)) } })
        val temporary = File.createTempFile("signal-snapshots-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(root.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun encode(snapshot: SignalSnapshot) = JSONObject().apply {
        put("id", snapshot.id); put("area", snapshot.area); put("label", snapshot.label)
        put("capturedAtMs", snapshot.capturedAtMs); put("scanStartedAtMs", snapshot.scanStartedAtMs)
        put("simulation", snapshot.simulation)
        put("cellEnvironment", snapshot.cellEnvironment?.toJson() ?: JSONObject.NULL)
        put("discoverySources", snapshot.discoverySources?.let { JSONArray(it.map { source -> source.name }.sorted()) } ?: JSONObject.NULL)
        put("wifiChannels", snapshot.wifiChannels?.let { channels ->
            JSONArray().apply { channels.forEach { put(encodeChannel(it)) } }
        } ?: JSONObject.NULL)
        put("signals", JSONArray().apply { snapshot.signals.forEach { signal ->
            put(JSONObject().apply {
                put("address", signal.address); put("source", signal.source.name); put("name", signal.name)
                put("rssi", signal.rssi ?: JSONObject.NULL); put("firstSeenMs", signal.firstSeenMs); put("lastSeenMs", signal.lastSeenMs)
                put("observations", signal.observationCount); put("manufacturer", signal.manufacturer); put("model", signal.modelHint ?: JSONObject.NULL)
                put("presence", signal.presence.name)
                put("hidden", signal.hidden); put("everHidden", signal.everHidden)
                put("radioMetadata", signal.radioMetadata?.let(::encodeMetadata) ?: JSONObject.NULL)
                put("manufacturerIds", JSONArray(signal.manufacturerIds.sorted()))
                put("referenceRssiAt1m", signal.referenceRssiAt1m ?: JSONObject.NULL)
                put("periods", JSONArray().apply { signal.periods.forEach { period ->
                    put(JSONObject().put("first", period.firstSeenMs).put("last", period.lastSeenMs).put("count", period.count))
                } })
            })
        } })
    }

    private fun decode(json: JSONObject): SignalSnapshot {
        val rows = json.getJSONArray("signals")
        val signals = (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
            SnapshotSignal(row.getString("address"), ScanSource.valueOf(row.getString("source")), row.getString("name"),
                if (row.isNull("rssi")) null else row.getDouble("rssi"), row.getLong("firstSeenMs"), row.getLong("lastSeenMs"),
                row.getInt("observations"), row.getString("manufacturer"), if (row.isNull("model")) null else row.getString("model"),
                SignalPresence.valueOf(row.optString("presence", SignalPresence.RECENT.name)),
                row.optJSONArray("manufacturerIds")?.let { ids -> (0 until ids.length()).map { ids.getInt(it) }.toSet() } ?: emptySet(),
                row.optJSONArray("periods")?.let { periods -> (0 until periods.length()).map { index ->
                    val period = periods.getJSONObject(index)
                    ObservationPeriod(period.getLong("first"), period.getLong("last"), period.getInt("count"))
                } } ?: emptyList(),
                if (row.isNull("referenceRssiAt1m")) null else row.getDouble("referenceRssiAt1m"),
                hidden = row.optBoolean("hidden", false), everHidden = row.optBoolean("everHidden", row.optBoolean("hidden", false)),
                radioMetadata = row.optJSONObject("radioMetadata")?.let(::decodeMetadata))
        } }
        return SignalSnapshot(json.getString("id"), json.getString("area"), json.getString("label"), json.getLong("capturedAtMs"),
            json.getLong("scanStartedAtMs"), json.getBoolean("simulation"), signals,
            if (json.isNull("wifiChannels")) null else json.getJSONArray("wifiChannels").let { channels ->
                (0 until channels.length()).map { decodeChannel(channels.getJSONObject(it)) }
            },
            cellEnvironment = json.optJSONObject("cellEnvironment")?.let(::cellEnvironmentSnapshotFromJson),
            discoverySources = json.optJSONArray("discoverySources")?.let { sources ->
                (0 until sources.length()).mapNotNull { index -> ScanSource.entries.firstOrNull { it.name == sources.optString(index) } }.toSet()
            })
    }

    private fun encodeChannel(channel: WifiChannelObservation) = JSONObject().apply {
        put("address", channel.address); put("name", channel.name); put("frequencyMhz", channel.frequencyMhz)
        put("firstSeenMs", channel.firstSeenMs); put("lastSeenMs", channel.lastSeenMs)
        put("observations", channel.observationCount)
        put("hidden", channel.hidden); put("everHidden", channel.everHidden)
        put("wifiMetadata", channel.wifiMetadata?.let(::encodeWifiMetadata) ?: JSONObject.NULL)
        put("lastRssi", channel.lastRssi ?: JSONObject.NULL); put("peakRssi", channel.peakRssi ?: JSONObject.NULL)
        put("periods", JSONArray().apply { channel.periods.forEach { period ->
            put(JSONObject().put("first", period.firstSeenMs).put("last", period.lastSeenMs).put("count", period.count))
        } })
    }

    private fun decodeChannel(row: JSONObject) = WifiChannelObservation(
        address = row.getString("address"), name = row.getString("name"), frequencyMhz = row.getInt("frequencyMhz"),
        firstSeenMs = row.getLong("firstSeenMs"), lastSeenMs = row.getLong("lastSeenMs"),
        observationCount = row.getInt("observations"),
        lastRssi = if (row.isNull("lastRssi")) null else row.getInt("lastRssi"),
        peakRssi = if (row.isNull("peakRssi")) null else row.getInt("peakRssi"),
        periods = row.getJSONArray("periods").let { periods -> (0 until periods.length()).map { index ->
            val period = periods.getJSONObject(index)
            ObservationPeriod(period.getLong("first"), period.getLong("last"), period.getInt("count"))
        } },
        hidden = row.optBoolean("hidden", false),
        everHidden = row.optBoolean("everHidden", row.optBoolean("hidden", false)),
        wifiMetadata = row.optJSONObject("wifiMetadata")?.let(::decodeWifiMetadata),
    )

    private fun encodeMetadata(metadata: RadioMetadata) = JSONObject().apply {
        put("wifi", metadata.wifi?.let(::encodeWifiMetadata) ?: JSONObject.NULL)
        put("classicDeviceClass", metadata.classicDeviceClass ?: JSONObject.NULL)
        put("ble", metadata.ble?.let { ble -> JSONObject().apply {
            put("serviceUuids", JSONArray(ble.serviceUuids))
            put("connectable", ble.connectable ?: JSONObject.NULL); put("legacy", ble.legacy ?: JSONObject.NULL)
            put("primaryPhy", ble.primaryPhy ?: JSONObject.NULL); put("secondaryPhy", ble.secondaryPhy ?: JSONObject.NULL)
        } } ?: JSONObject.NULL)
    }

    private fun decodeMetadata(row: JSONObject) = RadioMetadata(
        wifi = row.optJSONObject("wifi")?.let(::decodeWifiMetadata),
        classicDeviceClass = row.nullableInt("classicDeviceClass"),
        ble = row.optJSONObject("ble")?.let { ble -> BleRadioMetadata(
            serviceUuids = ble.optJSONArray("serviceUuids")?.let { ids ->
                (0 until ids.length()).map { ids.getString(it) }
            } ?: emptyList(),
            connectable = if (ble.isNull("connectable")) null else ble.getBoolean("connectable"),
            legacy = if (ble.isNull("legacy")) null else ble.getBoolean("legacy"),
            primaryPhy = ble.nullableInt("primaryPhy"), secondaryPhy = ble.nullableInt("secondaryPhy"),
        ) },
    )

    private fun encodeWifiMetadata(wifi: WifiRadioMetadata) = JSONObject().apply {
        put("channelWidthCode", wifi.channelWidthCode ?: JSONObject.NULL)
        put("centerFrequency0Mhz", wifi.centerFrequency0Mhz ?: JSONObject.NULL)
        put("centerFrequency1Mhz", wifi.centerFrequency1Mhz ?: JSONObject.NULL)
        put("capabilities", wifi.capabilities ?: JSONObject.NULL)
    }

    private fun decodeWifiMetadata(row: JSONObject) = WifiRadioMetadata(
        channelWidthCode = row.nullableInt("channelWidthCode"),
        centerFrequency0Mhz = row.nullableInt("centerFrequency0Mhz"),
        centerFrequency1Mhz = row.nullableInt("centerFrequency1Mhz"),
        capabilities = if (row.isNull("capabilities")) null else row.getString("capabilities"),
    )

    private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key)) null else getInt(key)
}
