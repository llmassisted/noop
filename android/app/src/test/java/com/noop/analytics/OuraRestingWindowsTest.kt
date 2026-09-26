package com.noop.analytics

import com.noop.data.EventRow
import com.noop.data.OuraStreamMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OuraRestingWindowsTest {
    private fun event(ts: Long, hrv: Int = 40, hr: Int = 58) = EventRow(
        deviceId = "oura-ring",
        ts = ts,
        kind = OuraStreamMapping.EVENT_HRV,
        payloadJSON = "{\"hr_bpm\":$hr,\"rmssd_ms\":$hrv}",
    )

    @Test
    fun `eight bucket awake rest is retained but not promoted to sleep`() {
        val events = (0 until 8).map { event(10_000L + it * 300L) }

        val all = OuraRestingWindows.windows(events)

        assertEquals(1, all.size)
        assertEquals(8, all.single().bucketCount)
        assertEquals(40L * 60L, all.single().durationSeconds)
        assertTrue(OuraRestingWindows.windows(events).filter(OuraRestingWindows::isSleepCandidate).isEmpty())
    }

    @Test
    fun `long continuous late sleep becomes a candidate`() {
        val events = (0 until 105).map { event(30_000L + it * 300L, hrv = 42) }

        val candidate = OuraRestingWindows.windows(events).filter(OuraRestingWindows::isSleepCandidate).single()

        assertEquals(105, candidate.bucketCount)
        assertEquals(8L * 60L * 60L + 45L * 60L, candidate.durationSeconds)
        assertEquals(42.0, candidate.avgHrvMs, 0.0)
    }

    @Test
    fun `gap separates windows without dropping the short one`() {
        val events = (0 until 4).map { event(1_000L + it * 300L) } +
            (0 until 3).map { event(8_000L + it * 300L) }

        val windows = OuraRestingWindows.windows(events)

        assertEquals(listOf(4, 3), windows.map { it.bucketCount })
    }

    @Test
    fun `sparse resting scatter is retained but not promoted`() {
        val events = (0 until 18).map { event(20_000L + it * 15L * 60L) }

        assertEquals(1, OuraRestingWindows.windows(events).size)
        assertTrue(OuraRestingWindows.windows(events).filter(OuraRestingWindows::isSleepCandidate).isEmpty())
    }
}
