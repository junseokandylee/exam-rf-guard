package com.classroom.rfdetector.core

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleAdvertisementJournalTest {
    private val context = RuntimeEnvironment.getApplication()
    // Robolectric isolates each test; short paths also keep sqlite4java's Windows WAL files usable.
    private val databaseName = "ble-test.db"
    private val journals = mutableListOf<BleAdvertisementJournal>()

    @After fun cleanup() {
        journals.forEach { it.close() }
        context.deleteDatabase(databaseName)
    }

    @Test fun repeatedRejectedAndNullRowsSurviveReopen() {
        val journal = open()
        journal.beginSession(session("one"))
        val repeated = event().copy(name = "이어폰 \"시험\"\n다음 줄", rawRecordHex = "020106ff00")
        assertTrue(journal.record("one", repeated))
        assertTrue(journal.record("one", repeated))
        assertTrue(journal.record("one", event().copy(address = null, name = null, rssi = null,
            rawRecordHex = null, observedAtMs = null, timestampNanos = -1,
            disposition = BleRecordDisposition.INVALID_TIMESTAMP)))
        assertTrue(journal.record("one", event().copy(rawRecordHex = "", disposition = BleRecordDisposition.BEFORE_SCAN)))
        assertTrue(journal.record("one", event().copy(disposition = BleRecordDisposition.DECODE_ERROR)))
        journal.endSession("one", 2000)
        journal.close()
        assertEquals(BleJournalStatus("one", 5, 5, 0, 0), journal.status.value)

        val rows = export(open())
        val advertisements = rows.ofType("advertisement")
        assertEquals(5, advertisements.size)
        assertEquals(repeated.name, advertisements[0].getString("name"))
        assertEquals(repeated.name, advertisements[1].getString("name"))
        assertNotEquals(advertisements[0].getLong("id"), advertisements[1].getLong("id"))
        assertTrue(advertisements[2].isNull("address"))
        assertTrue(advertisements[2].isNull("raw_record_hex"))
        assertTrue(advertisements[2].isNull("observed_at_ms"))
        assertEquals(-1, advertisements[2].getLong("timestamp_nanos"))
        assertEquals("", advertisements[3].getString("raw_record_hex"))
        assertEquals("BEFORE_SCAN", advertisements[3].getString("disposition"))
        assertEquals("DECODE_ERROR", advertisements[4].getString("disposition"))
        assertEquals("ended", rows.ofType("session").single().getString("state"))
        assertEquals(2000, rows.ofType("session").single().getLong("ended_at_ms"))
        assertEquals("elapsed_realtime_nanoseconds_since_boot", rows.first().getString("timestampNanosUnit"))
    }

    @Test fun oldSessionDoesNotPolluteNewCounters() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val journal = open(beforeWrite = {
            if (attempts.getAndIncrement() == 0) {
                writing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        })
        journal.beginSession(session("before"))
        journal.record("before", event())
        assertTrue(writing.await(5, TimeUnit.SECONDS))
        try {
            journal.endSession("before", 2000)
            journal.beginSession(session("after"))
            assertEquals(BleJournalStatus(sessionId = "after"), journal.status.value)
            journal.record("after", event())
            assertFalse(journal.record("before", event()))
            assertEquals(BleJournalStatus("after", 1, 0, 1, 0), journal.status.value)
        } finally { release.countDown() }
        val rows = export(journal)
        assertEquals(BleJournalStatus("after", 1, 1, 0, 0), journal.status.value)
        assertEquals(listOf("before", "after"), rows.ofType("advertisement").map { it.getString("session_id") })
        val old = rows.ofType("session").single { it.getString("id") == "before" }
        assertEquals(2, old.getLong("received"))
        assertEquals(1, old.getLong("failed"))
    }

    @Test fun queueOverflowIsCountedAndDrained() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val journal = open(capacity = 2, beforeWrite = { writing.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        journal.beginSession(session("one"))
        journal.record("one", event())
        assertTrue(writing.await(5, TimeUnit.SECONDS))
        try {
            assertTrue(journal.record("one", event()))
            assertTrue(journal.record("one", event()))
            assertFalse(journal.record("one", event()))
            assertEquals(4, journal.status.value.received)
            assertEquals(3, journal.status.value.pending)
            assertEquals(1, journal.status.value.failed)
            assertEquals(1, journal.status.value.overflow)
            assertTrue(journal.status.value.error!!.contains("대기열 초과"))
            journal.endSession("one", 2000)
        } finally { release.countDown() }
        journal.close()
        assertEquals(3, journal.status.value.written)
        assertEquals(0, journal.status.value.pending)
        val rows = export(open())
        assertEquals(3, rows.ofType("advertisement").size)
        assertEquals(1, rows.ofType("session").single().getLong("overflow"))
        assertEquals("ended", rows.ofType("session").single().getString("state"))
    }

    @Test fun failedWritesStayVisibleAndCanRecover() {
        val attempts = AtomicInteger()
        val journal = open(beforeWrite = { if (attempts.getAndIncrement() == 0) throw IOException("disk unavailable") })
        journal.beginSession(session("one"))
        journal.record("one", event())
        assertEquals(0, export(journal).ofType("advertisement").size)
        assertEquals(1, journal.status.value.failed)
        assertEquals(1, journal.status.value.writeFailed)
        assertEquals(0, journal.status.value.pending)
        assertEquals(0, journal.status.value.written)
        assertTrue(journal.status.value.error!!.contains("disk unavailable"))
        journal.record("one", event())
        val rows = export(journal)
        assertEquals(1, rows.ofType("advertisement").size)
        assertEquals(2, journal.status.value.received)
        assertEquals(1, journal.status.value.written)
        assertEquals(1, rows.ofType("session").single().getLong("write_failed"))
    }

    @Test fun reopenMarksUnfinishedSessionAndKeepsWal() {
        val journal = open()
        journal.beginSession(session("interrupted"))
        journal.record("interrupted", event())
        assertEquals("active", export(journal).ofType("session").single().getString("state"))
        journal.close()
        val reopened = open()
        val rows = export(reopened)
        assertEquals("interrupted", rows.ofType("session").single().getString("state"))
        assertTrue(rows.ofType("session").single().isNull("ended_at_ms"))
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("wal", cursor.getString(0).lowercase())
            }
        }
    }

    @Test fun scanStatesDoNotInflateAdvertisementCounts() {
        val journal = open()
        journal.beginSession(session("one"))
        journal.recordScanState("one", 1000, "BLE 요청")
        journal.recordScanState("one", 2000, "Classic 교대\nBLE 대기")
        journal.recordScanState("one", 3000, "오류: \"scan failed\"")
        journal.endSession("one", 4000)
        val rows = export(journal)
        assertTrue(rows.ofType("advertisement").isEmpty())
        assertEquals(3, rows.ofType("scan_state").size)
        assertEquals("Classic 교대\nBLE 대기", rows.ofType("scan_state")[1].getString("state"))
        assertEquals(BleJournalStatus(sessionId = "one"), journal.status.value)
        assertEquals("ended", rows.ofType("session").single().getString("state"))
    }

    @Test fun exportFailureDoesNotStopRecording() {
        val journal = open()
        journal.beginSession(session("one"))
        assertThrows(IOException::class.java) {
            journal.exportTo(object : OutputStream() {
                override fun write(value: Int) { throw IOException("destination unavailable") }
            })
        }
        journal.record("one", event())
        assertEquals(1, export(journal).ofType("advertisement").size)
    }

    @Test fun slowExportDoesNotBlockWritesOrBreakClose() {
        val journal = open()
        journal.beginSession(session("one"))
        journal.record("one", event())
        val exporting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captured = ByteArrayOutputStream()
        val slowOutput = object : OutputStream() {
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                exporting.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                captured.write(bytes, offset, length)
            }
        }
        val exportingTask = CompletableFuture.runAsync { journal.exportTo(slowOutput) }
        assertTrue(exporting.await(5, TimeUnit.SECONDS))
        var closeTask: CompletableFuture<Void>? = null
        try {
            assertTrue(journal.record("one", event().copy(name = "after export boundary")))
            assertEquals(2, export(journal).ofType("advertisement").size)
            assertEquals(2, journal.status.value.written)
            assertFalse(exportingTask.isDone)
            journal.endSession("one", 2000)
            val closing = CountDownLatch(1)
            closeTask = CompletableFuture.runAsync { closing.countDown(); journal.close() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse(closeTask.isDone)
        } finally { release.countDown() }
        exportingTask.get(5, TimeUnit.SECONDS)
        closeTask!!.get(5, TimeUnit.SECONDS)
        val rows = captured.toString("UTF-8").lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
        assertEquals(1, rows.ofType("advertisement").size)
        assertEquals(1, rows.first().getLong("lastAdvertisementId"))
    }

    @Test fun unsavedSessionLossesRemainInExport() {
        val setup = open()
        export(setup)
        setup.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("CREATE TRIGGER reject_session BEFORE INSERT ON sessions BEGIN SELECT RAISE(FAIL, 'session write unavailable'); END")
        }
        val journal = open()
        journal.beginSession(session("unsaved"))
        journal.record("unsaved", event())
        val rows = export(journal)
        val failed = rows.ofType("session").single()
        assertEquals("storage_failed", failed.getString("state"))
        assertEquals("unsaved", failed.getString("id"))
        assertEquals(1, failed.getLong("received"))
        assertEquals(1, failed.getLong("failed"))
        assertEquals(0, failed.getLong("written"))
        assertEquals(0, failed.getLong("pending"))
        assertFalse(failed.getBoolean("originalRowsAvailable"))
        assertFalse(failed.isNull("last_error"))
        assertTrue(rows.ofType("advertisement").isEmpty())
    }

    @Test fun futureSchemaIsPreserved() {
        val path = context.getDatabasePath(databaseName)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            database.execSQL("CREATE TABLE future_record (value TEXT)")
            database.execSQL("INSERT INTO future_record VALUES ('keep')")
            database.version = 99
        }
        val journal = open()
        journal.beginSession(session("one"))
        journal.record("one", event())
        assertThrows(IOException::class.java) { export(journal) }
        assertEquals(1, journal.status.value.failed)
        assertEquals(0, journal.status.value.written)
        assertNotNull(journal.status.value.error)
        journal.close()
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertEquals(99, database.version)
            database.rawQuery("SELECT value FROM future_record", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("keep", cursor.getString(0))
            }
        }
    }

    private fun open(capacity: Int = 4096, beforeWrite: (() -> Unit)? = null) =
        BleAdvertisementJournal(context, databaseName, capacity, beforeWrite).also { journals += it }

    private fun export(journal: BleAdvertisementJournal): List<JSONObject> {
        val output = ByteArrayOutputStream()
        journal.exportTo(output)
        return output.toString("UTF-8").lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
    }

    private fun List<JSONObject>.ofType(type: String) = filter { it.getString("type") == type }
    private fun session(id: String) = ScanSession(id, 1000, "3층 시험실", true)
    private fun event() = BleAdvertisementEvent(1500, 1490, 123_456_789, "AA:BB:CC:DD:EE:FF", "이어폰",
        -65, "020106", 0, 1, 0, 255, BleRecordDisposition.ACCEPTED)
}
