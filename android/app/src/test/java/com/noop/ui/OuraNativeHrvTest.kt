package com.noop.ui

import com.noop.data.EventRow
import com.noop.data.OuraStreamMapping
import com.noop.data.SleepSession
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class OuraNativeHrvTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun `aggregates native buckets onto the sleep session wake day`() {
        val sleep = SleepSession(
            deviceId = "oura-ring-noop",
            startTs = epoch("2026-08-08T23:00:00Z"),
            endTs = epoch("2026-08-09T07:00:00Z"),
        )
        val events = listOf(
            hrv("2026-08-08T23:30:00Z", 42),
            hrv("2026-08-09T06:30:00Z", 58),
        )

        val reading = OuraNativeHrv.aggregate(events, listOf(sleep), utc).single()

        assertEquals("2026-08-09", reading.day)
        assertEquals(50.0, reading.valueMs, 1e-9)
        assertEquals(2, reading.bucketCount)
        assertEquals(epoch("2026-08-08T23:30:00Z"), reading.windowStartTs)
        assertEquals(epoch("2026-08-09T06:35:00Z"), reading.windowEndTs)
    }

    @Test
    fun `ignores malformed invalid non hrv and retains out of sleep resting events`() {
        val sleep = SleepSession(
            deviceId = "oura-ring-noop",
            startTs = epoch("2026-08-09T00:00:00Z"),
            endTs = epoch("2026-08-09T08:00:00Z"),
        )
        val events = listOf(
            hrv("2026-08-09T01:00:00Z", 47),
            hrv("2026-08-09T02:00:00Z", 0),
            EventRow("oura-ring", epoch("2026-08-09T03:00:00Z"), OuraStreamMapping.EVENT_HRV, "not-json"),
            EventRow("oura-ring", epoch("2026-08-09T04:00:00Z"), "OTHER", "{\"rmssd_ms\":99}"),
            hrv("2026-08-09T12:00:00Z", 88),
        )

        val readings = OuraNativeHrv.aggregate(events, listOf(sleep), utc)
        val reading = readings.first { it.day == "2026-08-09" }

        assertEquals(47.0, reading.valueMs, 1e-9)
        assertEquals(1, reading.bucketCount)
        assertEquals(88.0, readings.first { it.day == "2026-08-10" }.valueMs, 1e-9)
    }

    @Test
    fun `uses an edited onset and keeps separate wake days`() {
        val first = SleepSession(
            deviceId = "oura-ring-noop",
            startTs = epoch("2026-08-07T23:30:00Z"),
            startTsAdjusted = epoch("2026-08-07T22:30:00Z"),
            endTs = epoch("2026-08-08T07:00:00Z"),
        )
        val second = SleepSession(
            deviceId = "oura-ring-noop",
            startTs = epoch("2026-08-08T23:00:00Z"),
            endTs = epoch("2026-08-09T07:00:00Z"),
        )

        val readings = OuraNativeHrv.aggregate(
            listOf(
                hrv("2026-08-07T22:45:00Z", 40),
                hrv("2026-08-09T06:00:00Z", 60),
            ),
            listOf(first, second),
            utc,
        )

        assertEquals(listOf("2026-08-08", "2026-08-09"), readings.map { it.day })
        assertEquals(listOf(40.0, 60.0), readings.map { it.valueMs })
    }

    @Test
    fun `wake day uses the phone local zone rather than UTC`() {
        val sleep = SleepSession(
            deviceId = "oura-ring-noop",
            startTs = epoch("2026-08-08T21:00:00Z"),
            // 01:00 UTC on Aug 9 is still Aug 8 in New York.
            endTs = epoch("2026-08-09T01:00:00Z"),
        )

        val reading = OuraNativeHrv.aggregate(
            listOf(hrv("2026-08-09T00:30:00Z", 55)),
            listOf(sleep),
            ZoneId.of("America/New_York"),
        ).single()

        assertEquals("2026-08-08", reading.day)
    }

    @Test
    fun `ring resting buckets use noon to noon wake days when sleep sessions are unavailable`() {
        val readings = OuraNativeHrv.aggregate(
            listOf(
                hrv("2026-08-08T23:30:00Z", 40),
                hrv("2026-08-09T06:30:00Z", 60),
                hrv("2026-08-09T12:30:00Z", 80),
            ),
            emptyList(),
            utc,
        )

        assertEquals(listOf("2026-08-09", "2026-08-10"), readings.map { it.day })
        assertEquals(listOf(50.0, 80.0), readings.map { it.valueMs })
        assertEquals(2, readings.first().bucketCount)
    }

    @Test
    fun `no-session wake day fallback uses phone local noon`() {
        val reading = OuraNativeHrv.aggregate(
            // 15:30 UTC is 11:30 in New York, so this still belongs to Aug 9's wake day.
            listOf(hrv("2026-08-09T15:30:00Z", 55)),
            emptyList(),
            ZoneId.of("America/New_York"),
        ).single()

        assertEquals("2026-08-09", reading.day)
    }

    private fun hrv(iso: String, rmssd: Int) = EventRow(
        deviceId = "oura-ring",
        ts = epoch(iso),
        kind = OuraStreamMapping.EVENT_HRV,
        payloadJSON = "{\"rmssd_ms\":$rmssd}",
    )

    private fun epoch(iso: String): Long = java.time.Instant.parse(iso).epochSecond
}
