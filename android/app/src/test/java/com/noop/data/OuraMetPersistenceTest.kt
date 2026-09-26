package com.noop.data

import com.noop.oura.OuraActivityInfo
import com.noop.oura.OuraEvent
import com.noop.ui.OuraActivityDays
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable Oura MET path: the live mapping (0x50 → OURA_MET), the one-time sidecar import that must
 * write the IDENTICAL row so the (deviceId, ts, kind) PK dedups the two, and the Health card's per-day
 * aggregation.
 */
class OuraMetPersistenceTest {

    @Test
    fun liveMappingStoresMetAsExactTenths() {
        val info = OuraActivityInfo(ringTimestamp = 500L, state = 7, met = listOf(1.6, 0.9, 12.8, 3.0))
        val streams = OuraStreamMapping.streams(listOf(OuraEvent.ActivityInfo(info))) { 1_790_000_000 }
        val ev = streams.events.single()
        assertEquals(OuraStreamMapping.EVENT_MET, ev.kind)
        assertEquals(1_790_000_000, ev.ts)
        assertEquals(
            """{"met_x10":[16,9,128,30],"sec_per_sample":60,"state":7}""",
            StreamPersistence.encodePayload(ev.payload),
        )
        // Never a scored stream value.
        assertTrue(streams.hr.isEmpty())
    }

    @Test
    fun unanchoredRecordIsDroppedNotGuessed() {
        val info = OuraActivityInfo(ringTimestamp = 500L, state = 0, met = listOf(1.0))
        assertTrue(OuraStreamMapping.streams(listOf(OuraEvent.ActivityInfo(info))) { null }.events.isEmpty())
    }

    @Test
    fun sidecarLineImportsTheSameRowTheLivePathWrites() {
        // A real schema-1 line shape (OuraActivityDumpLine.encode).
        val line = """{"schema":1,"deviceId":"oura-x","ringTs":43273389,"utc":1790444000,""" +
            """"iso":"2026-09-26T17:33:20Z","state":7,"secPerSample":60,"met":[1.6,1.2,0.9,12.8]}"""
        val (utc, payload) = OuraMetBackfill.parseLine(line)!!
        assertEquals(1_790_444_000L, utc)
        val live = OuraStreamMapping.streams(
            listOf(OuraEvent.ActivityInfo(OuraActivityInfo(43273389L, 7, listOf(1.6, 1.2, 0.9, 12.8)))),
        ) { 1_790_444_000 }.events.single()
        assertEquals(StreamPersistence.encodePayload(live.payload), payload)
    }

    @Test
    fun malformedOrUnknownSidecarLinesAreSkipped() {
        assertNull(OuraMetBackfill.parseLine("not json"))
        assertNull(OuraMetBackfill.parseLine("""{"schema":2,"utc":1790444000,"met":[1.0]}"""))
        assertNull(OuraMetBackfill.parseLine("""{"schema":1,"utc":1790444000,"met":[]}"""))
        assertNull(OuraMetBackfill.parseLine("""{"schema":1,"met":[1.0]}"""))
    }

    @Test
    fun dailyAggregationLaysSamplesBackwardAndSplitsAtMidnight() {
        // A 4-sample record stamped 00:02 UTC on the 26th: samples at 23:59, 00:00, 00:01, 00:02.
        val midnight = 1_790_380_800L   // 2026-09-26T00:00:00Z
        val row = EventRow(
            deviceId = "oura-x",
            ts = midnight + 120,
            kind = OuraStreamMapping.EVENT_MET,
            payloadJSON = """{"met_x10":[40,40,10,50],"sec_per_sample":60,"state":0}""",
        )
        val days = OuraActivityDays.aggregate(listOf(row), ZoneOffset.UTC, bodyMassKg = 60.0)
        assertEquals(listOf("2026-09-25", "2026-09-26"), days.map { it.day })
        assertEquals(1, days[0].estimate.sampleCount)
        assertEquals(1.0, days[0].estimate.activeMinutes, 0.0)          // the 4.0 MET minute
        assertEquals(3, days[1].estimate.sampleCount)
        assertEquals(2.0, days[1].estimate.activeMinutes, 0.0)          // 4.0 and 5.0 clear 3.0
        assertEquals(3.0, days[0].estimate.estActiveKcal!!, 0.0)        // (4-1) MET × 60 kg × 1/60 h
    }

    @Test
    fun otherKindsAndUnexpectedCadencesAreIgnored() {
        val motion = EventRow("oura-x", 1_790_380_800L, OuraStreamMapping.EVENT_MOTION, """{"motion_seconds":5}""")
        val oddCadence = EventRow(
            "oura-x", 1_790_380_900L, OuraStreamMapping.EVENT_MET,
            """{"met_x10":[40],"sec_per_sample":30,"state":0}""",
        )
        assertTrue(OuraActivityDays.aggregate(listOf(motion, oddCadence), ZoneOffset.UTC).isEmpty())
    }
}
