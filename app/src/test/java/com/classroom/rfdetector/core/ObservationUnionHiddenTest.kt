package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class ObservationUnionHiddenTest {
    @Test fun laterVisibleSessionKeepsPriorHiddenFactWithoutMergingSessionStatistics() {
        val old = signal(100, hidden = true, count = 4).copy(name = "이름 없는 AP")
        val latest = signal(400, hidden = false, count = 2).copy(name = "나중에 수신한 이름", firstSeenMs = 350,
            periods = listOf(ObservationPeriod(350, 400, 2)))
        val before = snapshot("before", 100, old)
        val after = snapshot("after", 400, latest)
        val union = observedUnion(before, after, listOf(before, before, after)).single()

        assertTrue(union.everHidden)
        assertFalse(union.hidden)
        assertEquals(latest.name, union.name)
        assertEquals(latest.firstSeenMs, union.firstSeenMs)
        assertEquals(latest.lastSeenMs, union.lastSeenMs)
        assertEquals(2, union.observationCount)
        assertEquals(latest.periods, union.periods)
        assertFalse(after.signals.single().everHidden)
        assertTrue(signalReport(before, after, listOf(before, after)).contains("이전에 이름 미표시 · 이력 유지"))
    }

    @Test fun unrelatedHiddenRecordsDoNotContaminateAVisibleComparison() {
        val before = snapshot("before", 100, signal(100, hidden = false))
        val after = snapshot("after", 400, signal(400, hidden = false))
        val hidden = snapshot("hidden", 300, signal(300, hidden = true))
        val unrelated = listOf(hidden.copy(area = "B실"), hidden.copy(simulation = true),
            hidden.copy(capturedAtMs = 401), hidden.copy(capturedAtMs = 99, signals = listOf(signal(99, hidden = true))))

        val union = observedUnion(before, after, unrelated).single()

        assertFalse(union.hidden)
        assertFalse(union.everHidden)
        assertEquals(1, union.observationCount)
    }

    private fun signal(at: Long, hidden: Boolean, count: Int = 1) = SnapshotSignal(
        "aa:bb", ScanSource.WIFI, "AP", -60.0, at, at, count, "미확인", null,
        periods = listOf(ObservationPeriod(at, at, count)), hidden = hidden, everHidden = hidden)

    private fun snapshot(id: String, at: Long, signal: SnapshotSignal) =
        SignalSnapshot(id, "A실", id, at, 0, false, listOf(signal))
}
