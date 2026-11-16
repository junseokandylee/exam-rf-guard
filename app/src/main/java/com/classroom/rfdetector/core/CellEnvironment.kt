package com.classroom.rfdetector.core

import org.json.JSONArray
import org.json.JSONObject

/** The scanner phone's modem environment. These are cells, never nearby participant phones. */
data class CellEnvironmentReading(
    val technology: String,
    /** Public cell identity components only; '?' means unavailable, not a unique identifier. */
    val identity: String,
    val channel: Int?,
    val dbm: Int?,
    val registered: Boolean,
    /** Approximate modem observation time, not callback delivery time. */
    val observedAtMs: Long,
    /** The modem timestamp predates this update request. Does not prove a rate limit. */
    val cached: Boolean = false,
)

enum class CellEnvironmentStatus(val label: String) {
    IDLE("대기"),
    UPDATING("확인 중"),
    AVAILABLE("기지국 정보 확인"),
    CACHED("캐시 정보"),
    EMPTY("정보 없음"),
    UNSUPPORTED("지원하지 않음"),
    NO_SIM("SIM 없음"),
    PERMISSION_DENIED("위치 권한 필요"),
    TIMEOUT("응답 시간 초과"),
    MODEM_ERROR("모뎀 응답 오류"),
}

data class CellEnvironmentSnapshot(
    val capturedAtMs: Long,
    val readings: List<CellEnvironmentReading>,
    val status: CellEnvironmentStatus = CellEnvironmentStatus.AVAILABLE,
    val note: String? = null,
    val error: String? = null,
)

fun CellEnvironmentSnapshot.toJson(): JSONObject = JSONObject().apply {
    put("capturedAtMs", capturedAtMs)
    put("status", status.name)
    put("note", note ?: JSONObject.NULL)
    put("error", error ?: JSONObject.NULL)
    put("readings", JSONArray().apply {
        readings.forEach { reading ->
            put(JSONObject().apply {
                put("technology", reading.technology)
                put("identity", reading.identity)
                put("channel", reading.channel ?: JSONObject.NULL)
                put("dbm", reading.dbm ?: JSONObject.NULL)
                put("registered", reading.registered)
                put("observedAtMs", reading.observedAtMs)
                put("cached", reading.cached)
            })
        }
    })
}

fun cellEnvironmentSnapshotFromJson(json: JSONObject): CellEnvironmentSnapshot? {
    val capturedAt = json.optLong("capturedAtMs", -1L).takeIf { it >= 0 } ?: return null
    val rows = json.optJSONArray("readings") ?: JSONArray()
    val readings = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val technology = row.optString("technology").takeIf { it in setOf("LTE", "NR", "WCDMA", "GSM", "CDMA") }
            ?: return@mapNotNull null
        val identity = row.optString("identity").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        val observedAt = row.optLong("observedAtMs", -1).takeIf { it in 1..capturedAt } ?: return@mapNotNull null
        CellEnvironmentReading(
            technology = technology,
            identity = identity,
            channel = row.nullableInt("channel")?.takeIf { it >= 0 && it != Int.MAX_VALUE },
            dbm = row.nullableInt("dbm")?.takeIf { it in -200..-1 },
            registered = row.optBoolean("registered", false),
            observedAtMs = observedAt,
            cached = row.optBoolean("cached", false),
        )
    }
    return CellEnvironmentSnapshot(
        capturedAtMs = capturedAt,
        readings = readings,
        status = CellEnvironmentStatus.entries.firstOrNull { it.name == json.optString("status") } ?: CellEnvironmentStatus.IDLE,
        note = json.nullableString("note"),
        error = json.nullableString("error"),
    )
}

private fun JSONObject.nullableInt(key: String): Int? = if (has(key) && !isNull(key)) optInt(key, Int.MAX_VALUE) else null
private fun JSONObject.nullableString(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
