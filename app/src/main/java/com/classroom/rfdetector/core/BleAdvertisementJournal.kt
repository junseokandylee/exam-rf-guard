package com.classroom.rfdetector.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * App-private raw callback journal. Only the worker touches SQLite; scanner callbacks never wait
 * for disk. The bounded queue contains at most 4096 advertisements (plus one batch in flight).
 * close/exportTo wait for preceding work and must be called on an IO thread. A killed process can
 * lose callbacks still in memory; the export deliberately makes no completeness guarantee.
 */
class BleAdvertisementJournal internal constructor(
    context: Context,
    databaseName: String,
    private val capacity: Int = 4096,
    private val beforeWriteBatch: (() -> Unit)? = null,
) : AutoCloseable {
    constructor(context: Context) : this(context, "ble-advertisements.db")

    private val lock = Object()
    private val queue = ArrayDeque<Command>()
    private val sessions = linkedMapOf<String, SessionState>()
    private var queuedAdvertisements = 0
    private var closing = false
    private var activeExports = 0
    private var latestSessionId: String? = null
    private val stopped = CountDownLatch(1)
    private val helper = JournalDatabase(context.applicationContext, databaseName)
    private val mutableStatus = MutableStateFlow(BleJournalStatus())
    val status: StateFlow<BleJournalStatus> = mutableStatus.asStateFlow()
    private val worker: Thread

    init {
        require(capacity > 0)
        worker = Thread(::writeLoop, "ble-advertisement-journal").apply {
            isDaemon = true
            start()
        }
    }

    fun beginSession(session: ScanSession) = synchronized(lock) {
        check(!closing) { "BLE 기록 저장소가 닫혔습니다" }
        require(session.id.isNotBlank()) { "BLE 기록 세션 ID가 비어 있습니다" }
        require(session.id !in sessions) { "BLE 기록 세션 ID를 재사용할 수 없습니다" }
        sessions[session.id] = SessionState(session)
        latestSessionId = session.id
        publish(session.id)
        enqueue(Command.Begin(session.id))
    }

    /** True means queued, not yet durable. False is always reflected in this session's failures. */
    fun record(sessionId: String, event: BleAdvertisementEvent): Boolean = synchronized(lock) {
        val session = requireNotNull(sessions[sessionId]) { "beginSession 호출이 필요합니다: $sessionId" }
        session.received++
        when {
            closing || session.endRequestedAtMs != null -> {
                session.failed++
                session.error = "종료된 BLE 기록 세션에 콜백이 도착했습니다"
                if (!closing) enqueueStats(session)
                publish(sessionId)
                false
            }
            queuedAdvertisements >= capacity -> {
                session.failed++
                session.overflow++
                session.error = "BLE 기록 대기열 초과 · ${capacity}개 버퍼에 새 콜백을 넣지 못했습니다"
                enqueueStats(session)
                publish(sessionId)
                false
            }
            else -> {
                session.pending++
                queuedAdvertisements++
                enqueue(Command.Advertisement(sessionId, event))
                publish(sessionId)
                true
            }
        }
    }

    fun endSession(sessionId: String, endedAtMs: Long) = synchronized(lock) {
        val session = sessions[sessionId] ?: return@synchronized
        if (closing || session.endRequestedAtMs != null) return@synchronized
        session.endRequestedAtMs = endedAtMs
        enqueue(Command.End(sessionId))
    }

    /** Lifecycle/radio changes are separate evidence and never inflate advertisement counters. */
    fun recordScanState(sessionId: String, atMs: Long, state: String): Boolean = synchronized(lock) {
        require(sessionId in sessions) { "beginSession 호출이 필요합니다: $sessionId" }
        if (closing) {
            sessions.getValue(sessionId).error = "닫힌 BLE 기록 저장소에 탐색 상태가 도착했습니다"
            publish(sessionId)
            false
        } else {
            enqueue(Command.ScanState(sessionId, atMs, state))
            true
        }
    }

    /** JSONL UTF-8; does not close the caller's stream. Includes every durable duplicate row. */
    fun exportTo(output: OutputStream) {
        check(Thread.currentThread() !== worker) { "Cannot export on the journal writer" }
        val completion = CompletableFuture<ExportSnapshot>()
        synchronized(lock) {
            check(!closing) { "BLE 기록 저장소가 닫혔습니다. 새 인스턴스에서 내보내세요" }
            activeExports++
            enqueue(Command.Export(completion))
        }
        try {
            exportOnDisk(output, completion.get())
        } catch (exception: ExecutionException) {
            throw IOException("BLE 원시 기록 내보내기에 실패했습니다", exception.cause)
        } finally {
            synchronized(lock) {
                activeExports--
                lock.notifyAll()
            }
        }
    }

    /** Stops intake, drains all accepted callbacks, then closes SQLite. Idempotent. */
    override fun close() {
        check(Thread.currentThread() !== worker) { "Cannot close on the journal writer" }
        synchronized(lock) {
            if (!closing) {
                closing = true
                enqueue(Command.Close)
            }
        }
        stopped.await()
    }

    private fun enqueue(command: Command) {
        queue.addLast(command)
        lock.notifyAll()
    }

    private fun enqueueStats(session: SessionState) {
        if (!session.statsQueued) {
            session.statsQueued = true
            enqueue(Command.Stats(session.session.id))
        }
    }

    private fun publish(id: String) {
        if (id == latestSessionId) mutableStatus.value = sessions.getValue(id).status()
    }

    private fun writeLoop() {
        try {
            while (true) {
                val command = synchronized(lock) {
                    while (queue.isEmpty()) lock.wait()
                    queue.removeFirst()
                }
                when (command) {
                    is Command.Begin -> beginOnDisk(command.id)
                    is Command.Advertisement -> writeBatch(command)
                    is Command.End -> {
                        synchronized(lock) {
                            val session = sessions.getValue(command.id)
                            session.endedAtMs = session.endRequestedAtMs
                        }
                        persistStats(command.id)
                    }
                    is Command.ScanState -> writeScanState(command)
                    is Command.Stats -> {
                        synchronized(lock) { sessions.getValue(command.id).statsQueued = false }
                        persistStats(command.id)
                    }
                    is Command.Export -> {
                        try {
                            command.completion.complete(captureExportSnapshot())
                        } catch (exception: Exception) {
                            command.completion.completeExceptionally(exception)
                        }
                    }
                    Command.Close -> {
                        sessions.keys.toList().forEach(::persistStats)
                        break
                    }
                }
            }
        } catch (exception: Exception) {
            synchronized(lock) {
                closing = true
                sessions.values.forEach { session ->
                    session.writeFailed += session.pending
                    session.failed += session.pending
                    session.pending = 0
                    session.error = "BLE 기록 작업이 중단되었습니다: ${exception.message ?: exception.javaClass.simpleName}"
                    publish(session.session.id)
                }
                queue.filterIsInstance<Command.Export>().forEach { it.completion.completeExceptionally(exception) }
                queue.clear()
            }
        } finally {
            try {
                synchronized(lock) { while (activeExports > 0) lock.wait() }
                helper.close()
            } finally { stopped.countDown() }
        }
    }

    private fun beginOnDisk(id: String) {
        val state = synchronized(lock) { sessions.getValue(id).also { it.beginAttempted = true } }
        try {
            helper.writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
                put("id", state.session.id)
                put("started_at_ms", state.session.startedAtMs)
                put("area", state.session.area)
                put("area_locked", if (state.session.areaLocked) 1 else 0)
            })
            synchronized(lock) { state.opened = true }
        } catch (exception: Exception) {
            setError(id, "BLE 기록 세션 저장 실패", exception)
        }
    }

    private fun writeBatch(first: Command.Advertisement) {
        val batch = synchronized(lock) {
            val records = mutableListOf(first)
            while (records.size < 128 && queue.peekFirst() is Command.Advertisement) {
                records += queue.removeFirst() as Command.Advertisement
            }
            queuedAdvertisements -= records.size
            records
        }
        var failure: Exception? = null
        try {
            beforeWriteBatch?.invoke()
            check(batch.all { synchronized(lock) { sessions.getValue(it.id).opened } }) { "세션 정보를 저장하지 못했습니다" }
            val database = helper.writableDatabase
            database.beginTransactionNonExclusive()
            try {
                database.compileStatement(INSERT_EVENT).use { statement ->
                    batch.forEach { record ->
                        statement.clearBindings()
                        val event = record.event
                        val values = listOf(record.id, event.receivedAtMs, event.observedAtMs, event.timestampNanos,
                            event.address, event.name, event.rssi, event.rawRecordHex, event.dataStatus,
                            event.primaryPhy, event.secondaryPhy, event.advertisingSid, event.disposition.name)
                        values.forEachIndexed { index, value ->
                            when (value) {
                                null -> statement.bindNull(index + 1)
                                is Number -> statement.bindLong(index + 1, value.toLong())
                                else -> statement.bindString(index + 1, value.toString())
                            }
                        }
                        statement.executeInsert()
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } catch (exception: Exception) {
            failure = exception
        }
        val counts = batch.groupingBy { it.id }.eachCount()
        val batchFailure = failure
        synchronized(lock) {
            counts.forEach { (id, count) ->
                val session = sessions.getValue(id)
                session.pending -= count
                if (batchFailure == null) session.written += count else {
                    session.failed += count
                    session.writeFailed += count
                    session.error = "BLE 광고 ${count}행 저장 실패: ${batchFailure.message ?: batchFailure.javaClass.simpleName}"
                }
                publish(id)
            }
        }
        counts.keys.forEach(::persistStats)
    }

    private fun persistStats(id: String) {
        val values = synchronized(lock) {
            val session = sessions.getValue(id)
            if (!session.opened) return
            ContentValues().apply {
                put("received", session.received)
                put("written", session.written)
                put("pending_at_last_update", session.pending)
                put("failed", session.failed)
                put("overflow", session.overflow)
                put("write_failed", session.writeFailed)
                put("last_error", session.error)
                session.endedAtMs?.let { put("ended_at_ms", it) } ?: putNull("ended_at_ms")
            }
        }
        try {
            check(helper.writableDatabase.update("sessions", values, "id = ?", arrayOf(id)) == 1) { "세션 행이 없습니다" }
        } catch (exception: Exception) {
            setError(id, "BLE 기록 집계 저장 실패", exception)
        }
    }

    private fun setError(id: String, message: String, exception: Exception) = synchronized(lock) {
        sessions.getValue(id).error = "$message: ${exception.message ?: exception.javaClass.simpleName}"
        publish(id)
    }

    private fun writeScanState(command: Command.ScanState) {
        try {
            check(synchronized(lock) { sessions.getValue(command.id).opened }) { "세션 정보를 저장하지 못했습니다" }
            helper.writableDatabase.insertOrThrow("scan_states", null, ContentValues().apply {
                put("session_id", command.id)
                put("at_ms", command.atMs)
                put("state", command.state)
            })
        } catch (exception: Exception) {
            setError(command.id, "BLE 탐색 상태 저장 실패", exception)
            persistStats(command.id)
        }
    }

    /** The writer only fixes bounds/metadata; the IO caller streams rows while WAL writes continue. */
    private fun captureExportSnapshot(): ExportSnapshot {
        val database = helper.readableDatabase
        database.beginTransactionNonExclusive()
        try {
            val sessionRows = mutableListOf<JSONObject>()
            val memoryRows = synchronized(lock) {
                sessions.values.filter { it.beginAttempted }.associate { it.session.id to it.exportSummary() }
            }
            database.rawQuery("SELECT * FROM sessions ORDER BY started_at_ms, id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = cursor.jsonRow()
                    val memory = memoryRows[row.getString("id")]
                    val live = memory?.optBoolean("sessionHeaderSaved") == true
                    val state = when { !row.isNull("ended_at_ms") -> "ended"; live -> "active"; else -> "interrupted" }
                    sessionRows += row.put("type", "session").put("state", state)
                        .put("liveStatus", memory ?: JSONObject.NULL)
                        .put("counterNote", "Persisted counters are best effort; liveStatus is current process memory, not a durability guarantee")
                }
            }
            val storedIds = sessionRows.map { it.getString("id") }.toSet()
            memoryRows.filterKeys { it !in storedIds }.values.forEach { memory ->
                sessionRows += memory.put("type", "session").put("state", "storage_failed")
                    .put("originalRowsAvailable", false)
                    .put("counterNote", "Session header could not be saved; this summary comes only from process memory; no advertisement rows are available")
            }
            fun lastId(table: String): Long = database.rawQuery("SELECT MAX(id) FROM $table", null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0
            }
            val snapshot = ExportSnapshot(System.currentTimeMillis(), sessionRows, lastId("advertisements"), lastId("scan_states"))
            database.setTransactionSuccessful()
            return snapshot
        } finally {
            database.endTransaction()
        }
    }

    private fun exportOnDisk(output: OutputStream, snapshot: ExportSnapshot) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        fun emit(value: JSONObject) { writer.write(value.toString()); writer.write("\n") }
        val database = helper.readableDatabase
        emit(JSONObject().put("type", "metadata").put("schema", 1)
            .put("exportedAtMs", snapshot.atMs)
            .put("wallClockUnit", "unix_epoch_milliseconds").put("timestampNanosUnit", "elapsed_realtime_nanoseconds_since_boot")
            .put("rssiUnit", "dBm").put("rawRecordEncoding", "hexadecimal")
            .put("scope", "Android BLE advertisement callbacks; not all radio packets")
            .put("sessionScope", "All real scan sessions retained in this app-local journal")
            .put("lastAdvertisementId", snapshot.lastAdvertisementId).put("lastScanStateId", snapshot.lastScanStateId)
            .put("exportBoundary", "Rows through these inclusive IDs; scanning may continue after this boundary")
            .put("completeness", "Queued callbacks may be lost on process termination; failures and buffer overflow are reported when known")
            .put("identity", "Repeated or rotating addresses are not merged into physical devices"))
        snapshot.sessions.forEach(::emit)
        fun emitTable(table: String, type: String, lastId: Long) {
            var after = 0L
            while (after < lastId) {
                val page = mutableListOf<JSONObject>()
                database.rawQuery("SELECT * FROM $table WHERE id > ? AND id <= ? ORDER BY id LIMIT 256",
                    arrayOf(after.toString(), lastId.toString())).use { cursor ->
                    while (cursor.moveToNext()) page += cursor.jsonRow().put("type", type)
                }
                if (page.isEmpty()) break
                page.forEach(::emit)
                after = page.last().getLong("id")
            }
        }
        emitTable("advertisements", "advertisement", snapshot.lastAdvertisementId)
        emitTable("scan_states", "scan_state", snapshot.lastScanStateId)
        writer.flush()
    }

    private fun Cursor.jsonRow(): JSONObject = JSONObject().also { row ->
        columnNames.forEachIndexed { index, name ->
            row.put(name, when (getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                else -> getString(index)
            })
        }
    }

    private class SessionState(val session: ScanSession) {
        var received = 0L
        var written = 0L
        var pending = 0L
        var failed = 0L
        var overflow = 0L
        var writeFailed = 0L
        var error: String? = null
        var endRequestedAtMs: Long? = null
        var endedAtMs: Long? = null
        var beginAttempted = false
        var opened = false
        var statsQueued = false
        fun status() = BleJournalStatus(session.id, received, written, pending, failed, error, overflow, writeFailed)
        fun exportSummary(): JSONObject = JSONObject().put("id", session.id)
            .put("started_at_ms", session.startedAtMs).put("area", session.area).put("area_locked", session.areaLocked)
            .put("ended_at_ms", endedAtMs ?: JSONObject.NULL).put("received", received).put("written", written)
            .put("pending", pending).put("failed", failed).put("overflow", overflow).put("write_failed", writeFailed)
            .put("last_error", error ?: JSONObject.NULL).put("sessionHeaderSaved", opened)
    }

    private sealed interface Command {
        data class Begin(val id: String) : Command
        data class Advertisement(val id: String, val event: BleAdvertisementEvent) : Command
        data class End(val id: String) : Command
        data class Stats(val id: String) : Command
        data class ScanState(val id: String, val atMs: Long, val state: String) : Command
        data class Export(val completion: CompletableFuture<ExportSnapshot>) : Command
        data object Close : Command
    }

    private data class ExportSnapshot(
        val atMs: Long,
        val sessions: List<JSONObject>,
        val lastAdvertisementId: Long,
        val lastScanStateId: Long,
    )

    private class JournalDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE sessions (
                id TEXT PRIMARY KEY NOT NULL, started_at_ms INTEGER NOT NULL, area TEXT NOT NULL,
                area_locked INTEGER NOT NULL, ended_at_ms INTEGER,
                received INTEGER NOT NULL DEFAULT 0, written INTEGER NOT NULL DEFAULT 0,
                pending_at_last_update INTEGER NOT NULL DEFAULT 0,
                failed INTEGER NOT NULL DEFAULT 0, overflow INTEGER NOT NULL DEFAULT 0,
                write_failed INTEGER NOT NULL DEFAULT 0, last_error TEXT)""")
            db.execSQL("""CREATE TABLE advertisements (
                id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id),
                received_at_ms INTEGER NOT NULL, observed_at_ms INTEGER, timestamp_nanos INTEGER NOT NULL,
                address TEXT, name TEXT, rssi INTEGER, raw_record_hex TEXT, data_status INTEGER,
                primary_phy INTEGER, secondary_phy INTEGER, advertising_sid INTEGER, disposition TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX advertisements_session ON advertisements(session_id, id)")
            db.execSQL("""CREATE TABLE scan_states (
                id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id),
                at_ms INTEGER NOT NULL, state TEXT NOT NULL)""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("지원하지 않는 BLE 기록 DB 버전: $oldVersion → $newVersion")
        }
    }

    private companion object {
        const val INSERT_EVENT = """INSERT INTO advertisements
            (session_id,received_at_ms,observed_at_ms,timestamp_nanos,address,name,rssi,raw_record_hex,
             data_status,primary_phy,secondary_phy,advertising_sid,disposition) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"""
    }
}
