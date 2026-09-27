package com.noop.analytics

import com.noop.data.EventRow
import com.noop.data.HrSample
import com.noop.data.OuraStreamMapping
import com.noop.data.RrInterval
import com.noop.data.SleepSession
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OuraSleepRestorationTest {
    private val day = "2026-09-25"
    private val start = Instant.parse("2026-09-24T23:00:00Z").epochSecond
    private val end = start + 8 * 3600
    private val hr = (start until end step 30).map { HrSample("oura-ring", it, 58) }
    private val rr = (start until end).mapIndexed { index, ts ->
        RrInterval("oura-ring", ts, if (index % 2 == 0) 980 else 1020)
    }
    private val events = (start until end step 300).map {
        EventRow("oura-ring", it, OuraStreamMapping.EVENT_HRV, "{\"hr_bpm\":58,\"rmssd_ms\":42}")
    }

    private fun evidence(
        oura: Boolean = true,
        rows: List<SleepSession> = emptyList(),
        edits: List<SleepSession> = emptyList(),
        native: List<EventRow> = events,
    ) = IntelligenceEngine.resolveSleepEvidence(
        day, 0, oura, true, 0, hr, rr, emptyList(), edits, rows, native, {},
    )

    @Test fun nativeRestingWindowReachesSleepAndVitalsWithoutGravity() {
        val evidence = evidence()
        assertEquals(listOf(start to end), evidence.hints.map { it.start to it.end })
        val result = AnalyticsEngine.analyzeDay(
            day = day, hr = hr, rr = rr, gravity = emptyList(), profile = UserProfile(),
            providedSleep = evidence.provided, sleepWindowHints = evidence.hints,
        )
        assertEquals(1, result.sleepSessions.size)
        assertEquals(start, result.sleepSessions.single().start)
        assertEquals(end, result.sleepSessions.single().end)
        assertNotNull(result.daily.totalSleepMin)
        assertNotNull(result.daily.restingHr)
        assertNotNull(result.daily.avgHrv)
        assertNotNull(result.rest)
        assertTrue(result.sleepSessions.single().hrOnly)
    }

    @Test fun activeOuraStoredHypnogramSurvivesImportNamespaceEquality() {
        val row = SleepSession(
            deviceId = "oura-ring", startTs = start, endTs = end,
            stagesJSON = "[{\"start\":$start,\"end\":$end,\"stage\":\"light\"}]",
        )
        val evidence = evidence(rows = listOf(row))
        assertEquals(1, evidence.provided.size)
        assertTrue(evidence.hints.isEmpty())
        assertEquals("light", evidence.provided.single().stages.single().stage)
        // The same imported row on WHOOP must keep the upstream import exclusion.
        assertTrue(evidence(oura = false, rows = listOf(row)).provided.isEmpty())
    }

    @Test fun manualBoundsOverrideOverlappingNativeRestWithoutLosingSeparateNap() {
        val manual = SleepSession(
            deviceId = "oura-ring-noop", startTs = start, endTs = end - 3600,
            userEdited = true,
        )
        val napStart = end + 3600
        val nap = SleepSession(
            deviceId = "oura-ring-noop", startTs = napStart, endTs = napStart + 2400,
            userEdited = true,
        )
        val evidence = evidence(edits = listOf(manual, nap))
        assertEquals(listOf(start to end - 3600, napStart to napStart + 2400),
            evidence.hints.map { it.start to it.end })
    }

    @Test fun shortRestDoesNotBecomeAutomaticSleep() {
        assertTrue(evidence(native = events.take(8)).hints.isEmpty())
        assertTrue(evidence(oura = false).hints.isEmpty())
    }
}
