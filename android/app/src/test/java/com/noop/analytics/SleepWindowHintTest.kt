package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepWindowHintTest {
    @Test
    fun `explicit forty minute nap contributes sleep rest and vitals without motion`() {
        val start = 10_000L
        val end = start + 40L * 60L
        val hr = (start..end step 30L).map { HrSample("oura-ring", it, 58) }
        val rr = (start..end step 2L).mapIndexed { index, ts ->
            RrInterval("oura-ring", ts, if (index % 2 == 0) 980 else 1_020, seq = index)
        }
        val hint = SleepWindowHint(
            start = start,
            end = end,
            stages = listOf(StageSegment(start, end, "light")),
        )

        val result = AnalyticsEngine.analyzeDay(
            day = AnalyticsEngine.dayString(end),
            hr = hr,
            rr = rr,
            gravity = emptyList(),
            profile = UserProfile(),
            sleepWindowHints = listOf(hint),
        )

        assertEquals(1, result.sleepSessions.size)
        assertEquals(40.0, result.daily.totalSleepMin!!, 0.01)
        assertEquals(58, result.daily.restingHr)
        assertNotNull(result.daily.avgHrv)
        assertTrue(result.rest?.let { it > 0.0 } == true)
    }
}
