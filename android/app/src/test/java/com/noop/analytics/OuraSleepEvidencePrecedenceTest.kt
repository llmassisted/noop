package com.noop.analytics

import com.noop.data.EventRow
import com.noop.data.HrSample
import com.noop.data.OuraStreamMapping
import com.noop.data.RrInterval
import com.noop.data.SleepSession
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On an Oura wake-day the ring's own resting-window sleep and the upstream HR-only fallback must not BOTH
 * produce sessions. Field case (2026-09-24): the HR-only spine built a 934-min block across a whole
 * daytime while the ring's resting window supplied the real night, so the day scored 1,379 min
 * (`matched=2`) and the #899 overlap heal then deleted the adjacent real night on every pass.
 */
class OuraSleepEvidencePrecedenceTest {
    private val day = "2026-09-23"

    /** An HR/R-R series the HR-only detector is known to stage (see SleepStagerHrOnlySessionsTest). */
    private fun hrOnlyNight(): Pair<List<HrSample>, List<RrInterval>> {
        val t0 = 1_788_300_000L
        val hr = ArrayList<HrSample>()
        val rr = ArrayList<RrInterval>()
        for (i in 0 until 16 * 3600) {
            val bpm = 74 + (Math.sin(i / 500.0) * 11).toInt()
            hr.add(HrSample("d", t0 + i, bpm)); rr.add(RrInterval("d", t0 + i, 60000 / bpm))
        }
        for (j in 0 until 8 * 3600) {
            val bpm = 64 + (Math.sin(j / 900.0) * 5).toInt()
            val t = t0 + 16 * 3600L + j
            hr.add(HrSample("d", t, bpm)); rr.add(RrInterval("d", t, 60000 / bpm))
        }
        return hr to rr
    }

    /** A dense native-RMSSD resting block ending on [day]: an automatic sleep candidate. */
    private val ringRest: List<EventRow> = run {
        val start = Instant.parse("2026-09-22T23:00:00Z").epochSecond
        (start until start + 8 * 3600 step 300).map {
            EventRow("oura-ring", it, OuraStreamMapping.EVENT_HRV, "{\"hr_bpm\":58,\"rmssd_ms\":42}")
        }
    }

    private fun resolve(
        ownerIsOura: Boolean = true,
        nightEvents: List<EventRow> = ringRest,
        edits: List<SleepSession> = emptyList(),
        diag: (String) -> Unit = {},
    ): IntelligenceEngine.DetectorIndependentSleep {
        val (hr, rr) = hrOnlyNight()
        return IntelligenceEngine.resolveSleepEvidence(
            day = day, tzOffsetSeconds = 0, ownerIsOura = ownerIsOura, ownerIsImported = true,
            gravityRows = 0, hr = hr, rr = rr, resp = emptyList(), editedRows = edits,
            providedRows = emptyList(), nightEvents = nightEvents, diag = diag,
        )
    }

    @Test
    fun `ring resting sleep suppresses the hr-only fallback for that wake day`() {
        val lines = ArrayList<String>()
        val evidence = resolve(diag = { lines.add(it) })
        assertEquals(1, evidence.hints.size)
        assertTrue("HR-only must not add a second night beside the ring's", evidence.provided.isEmpty())
        assertTrue(lines.any { "attempted=false reason=oura-resting-sleep" in it })
    }

    @Test
    fun `without ring sleep evidence the hr-only fallback still runs`() {
        val evidence = resolve(nightEvents = emptyList())
        assertTrue(evidence.hints.isEmpty())
        assertTrue(evidence.provided.isNotEmpty())
    }

    @Test
    fun `a manual edit alone does not suppress the fallback`() {
        val manualStart = Instant.parse("2026-09-23T13:00:00Z").epochSecond
        val manual = SleepSession(
            deviceId = "oura-ring-noop", startTs = manualStart, endTs = manualStart + 1800, userEdited = true,
        )
        val evidence = resolve(nightEvents = emptyList(), edits = listOf(manual))
        assertEquals(1, evidence.hints.size)
        assertTrue(evidence.provided.isNotEmpty())
    }

    @Test
    fun `the stale field block is healed away once no pass re-creates it`() {
        // Field rows (2026-09-23/24): the real ring night, re-scored fresh this pass, and the leftover
        // 934-min HR-only block that overlaps it by 43 min. With the fallback suppressed nothing re-banks
        // the block, so the #899 heal's fresh-witness rule must keep the night and drop the block.
        val night = SleepSession(deviceId = "oura-ring-noop", startTs = 1_790_147_107L, endTs = 1_790_177_240L)
        val staleBlock = SleepSession(deviceId = "oura-ring-noop", startTs = 1_790_174_640L, endTs = 1_790_230_739L)
        val sweep = SleepSessionDedup.dedupe(listOf(night, staleBlock), freshStarts = setOf(night.startTs))
        assertEquals(listOf(night.startTs), sweep.kept.map { it.startTs })
        assertEquals(listOf(staleBlock.startTs), sweep.dropped.map { it.startTs })
    }

    @Test
    fun `whoop keeps the hr-only fallback unchanged`() {
        assertTrue(resolve(ownerIsOura = false).provided.isNotEmpty())
    }
}
