package com.noop.ui

import com.noop.data.EventRow
import com.noop.data.OuraStreamMapping
import com.noop.data.SkinTempSample
import com.noop.data.SleepSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OuraOvernightReadingsTest {
    @Test fun everyRatioSampleKeepsItsRecordTimeIncludingPi255() {
        val rows = listOf(
            EventRow("oura-x", 1100, OuraStreamMapping.EVENT_SPO2_RPI,
                """{"r_x16384":[9551,10339,9733,9302],"pi":[255,255,255,185]}"""),
            EventRow("oura-x", 1102, OuraStreamMapping.EVENT_SPO2_RPI,
                """{"r_x16384":[12706,12932,13140,13014],"pi":[136,145,120,106]}"""),
            EventRow("oura-x", 2100, OuraStreamMapping.EVENT_SPO2_RPI,
                """{"r_x16384":[9551],"pi":[255]}"""),
        )
        val values = OuraOvernightReadings.ratios(rows, listOf(1000L..2000L))
        assertEquals(8, values.size)
        assertEquals(listOf(98.0, 97.0, 97.0, 98.0), values.take(4).map { it.value })
        assertEquals(listOf(1100L, 1100L, 1100L, 1100L, 1102L, 1102L, 1102L, 1102L), values.map { it.ts })
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3), values.map { it.index })
    }

    @Test fun temperaturesIncludeCapturedValuesWithoutTheNightlyWearGate() {
        val rows = listOf(SkinTempSample("oura-x", 1100, 3501), SkinTempSample("oura-x", 1200, 2800),
            SkinTempSample("oura-x", 2100, 3600))
        assertEquals(listOf(35.01, 28.0), OuraOvernightReadings.temperatures(rows, listOf(1000L..2000L)).map { it.value })
    }

    @Test fun editedSleepBoundsBelongToTheLocalWakeDayIncludingLateWakes() {
        val start = Instant.parse("2026-09-23T07:05:07Z").epochSecond
        val end = Instant.parse("2026-09-23T15:27:20Z").epochSecond
        val row = SleepSession(deviceId = "oura-x-noop", startTs = start, endTs = end,
            userEdited = true, startTsAdjusted = start + 600)
        val zone = ZoneId.of("America/New_York")
        assertEquals(listOf(start + 600..end), OuraOvernightReadings.windows(listOf(row), LocalDate.parse("2026-09-23"), zone))
        assertTrue(OuraOvernightReadings.windows(listOf(row), LocalDate.parse("2026-09-24"), zone).isEmpty())
    }

    @Test fun allPagesAreLoadedWithoutLosingBoundaryRecords() = runBlocking {
        val source = (0L..20_005L).toList()
        val cursors = mutableListOf<Long>()
        val rows = OuraOvernightReadings.readAll(0, 20_005, { it }) { from, to, limit ->
            cursors.add(from)
            source.filter { it in from..to }.take(limit)
        }
        assertEquals(source, rows)
        assertEquals(listOf(0L, 10_000L, 20_000L), cursors)
    }

    @Test fun chartThinningKeepsOrderBoundsAndEveryDip() {
        // A 20,000-sample night: 97 % with one brief 86 % dip and one 100 % peak.
        val values = List(20_000) { 97.0 }.toMutableList().apply { this[12_345] = 86.0; this[4_321] = 100.0 }
        val idx = OuraOvernightReadings.chartIndices(values)
        assertTrue(idx.size <= OuraOvernightReadings.CHART_MAX_POINTS)
        assertEquals(idx.sorted(), idx)
        assertEquals(idx.distinct(), idx)
        assertTrue(12_345 in idx)
        assertTrue(4_321 in idx)
        // Small nights are drawn in full.
        assertEquals((0 until 300).toList(), OuraOvernightReadings.chartIndices(List(300) { it.toDouble() }))
    }

    @Test fun malformedAndOtherSourceRecordsDoNotBecomeReadings() {
        val rows = listOf(
            EventRow("oura-x", 1100, OuraStreamMapping.EVENT_HRV, "{}"),
            EventRow("oura-x", 1101, OuraStreamMapping.EVENT_SPO2_RPI, "bad json"),
            EventRow("oura-x", 1102, OuraStreamMapping.EVENT_SPO2_RPI, """{"r_x16384":[9500],"pi":[]}"""),
        )
        assertTrue(OuraOvernightReadings.ratios(rows, listOf(1000L..2000L)).isEmpty())
    }
}
