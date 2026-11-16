package com.classroom.rfdetector.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellEnvironmentTest {
    @Test fun snapshotRoundTripPreservesCellTimesUnknownFieldsAndCacheStatus() {
        val source = CellEnvironmentSnapshot(10_000, listOf(
            CellEnvironmentReading("LTE", "LTE|mcc=450|mnc=05|tac=10|ci=123", 1300, -95, true, 5_000, true),
            CellEnvironmentReading("CDMA", "CDMA|sid=?|nid=?|bsid=?", null, null, false, 4_000, true),
        ), CellEnvironmentStatus.CACHED, "모뎀 캐시", null)
        val json = JSONObject(source.toJson().toString())
        assertEquals(source, cellEnvironmentSnapshotFromJson(json))
        assertFalse(json.toString().contains("subscriber"))
        assertFalse(json.toString().contains("phoneNumber"))
        assertFalse(json.toString().contains("ScanSource"))
    }

    @Test fun missingAndUnavailableMeasurementsStayNullInsteadOfBecomingZero() {
        val rows = JSONArray().put(JSONObject().apply {
            put("technology", "NR"); put("identity", "NR|nci=?"); put("observedAtMs", 1_000)
            put("channel", Int.MAX_VALUE); put("dbm", Int.MAX_VALUE)
        })
        val restored = cellEnvironmentSnapshotFromJson(JSONObject().put("capturedAtMs", 2_000).put("readings", rows))!!
        assertNull(restored.readings.single().channel)
        assertNull(restored.readings.single().dbm)
        assertFalse(restored.readings.single().registered)
        assertEquals(CellEnvironmentStatus.IDLE, restored.status)
    }

    @Test fun invalidSnapshotsAndImpossibleObservationTimesDoNotCreateCellEvidence() {
        assertNull(cellEnvironmentSnapshotFromJson(JSONObject()))
        val valid = CellEnvironmentReading("LTE", "LTE|ci=1", 1, -80, false, 100)
        val json = CellEnvironmentSnapshot(1_000, listOf(valid)).toJson()
        val rows = json.getJSONArray("readings")
        rows.put(JSONObject(rows.getJSONObject(0).toString()).put("observedAtMs", 1_001))
        rows.put(JSONObject(rows.getJSONObject(0).toString()).put("observedAtMs", 0))
        rows.put(JSONObject(rows.getJSONObject(0).toString()).put("technology", "PHONE"))
        rows.put(JSONObject(rows.getJSONObject(0).toString()).put("identity", ""))
        assertEquals(listOf(valid), cellEnvironmentSnapshotFromJson(json)!!.readings)
    }

    @Test fun errorSnapshotRetainsLastReadingTimeAndDoesNotClaimFreshData() {
        val snapshot = CellEnvironmentSnapshot(50_000,
            listOf(CellEnvironmentReading("GSM", "GSM|cid=7", 100, -75, false, 2_000, true)),
            CellEnvironmentStatus.TIMEOUT, "마지막 성공 결과", "응답 시간 초과")
        assertEquals(snapshot, cellEnvironmentSnapshotFromJson(JSONObject(snapshot.toJson().toString())))
    }
}
