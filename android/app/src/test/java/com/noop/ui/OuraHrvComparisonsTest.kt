package com.noop.ui

import com.noop.data.RrInterval
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OuraHrvComparisonsTest {
    private val start = Instant.parse("2026-09-01T23:00:00Z").epochSecond

    private fun reading(offset: Int, duration: Long = 8 * 3600L): OuraNativeHrvReading {
        val from = start + offset * 86400L
        val to = from + duration
        return OuraNativeHrvReading(
            day = Instant.ofEpochSecond(to).atZone(ZoneOffset.UTC).toLocalDate().toString(),
            valueMs = 50.0, bucketCount = (duration / 300).toInt(),
            windowStartTs = from, windowEndTs = to,
        )
    }

    private fun beats(reading: OuraNativeHrvReading, variation: Int = 20): List<RrInterval> =
        (reading.windowStartTs until reading.windowEndTs).mapIndexed { index, ts ->
            RrInterval("oura-ring", ts, if (index % 2 == 0) 1000 - variation else 1000 + variation)
        }

    @Test
    fun `newest comparison survives more than 250000 historical beats`() = runTest {
        val native = (0 until 12).map { reading(it) }
        val stored = native.flatMapIndexed { index, reading -> beats(reading, variation = 20 + index) }
        assertTrue(stored.size > 250_000)

        val comparisons = OuraNativeHrv.loadComparisons(native, emptyList()) { from, to, limit ->
            // Match the repository's inclusive time filter and oldest-first LIMIT behavior.
            stored.asSequence().filter { it.ts in from..to }.take(limit).toList()
        }

        assertEquals(native.map { it.day }, comparisons.map { it.day })
        comparisons.forEachIndexed { index, result ->
            assertEquals(40.0 + 2 * index, result.value, 1e-9)
            assertTrue(result.fromRestingWindow)
        }
    }

    @Test
    fun `canonical readings are preserved without reading raw beats for those days`() = runTest {
        val native = listOf(reading(0, duration = 600), reading(1, duration = 600))
        val canonical = NoopHrvComparisonReading(native.first().day, 73.0, fromRestingWindow = false)
        val requested = mutableListOf<Pair<Long, Long>>()

        val comparisons = OuraNativeHrv.loadComparisons(native, listOf(canonical)) { from, to, _ ->
            requested.add(from to to)
            beats(native.last())
        }

        assertEquals(listOf(native.last().windowStartTs to native.last().windowEndTs), requested)
        assertEquals(canonical, comparisons.first())
        assertEquals(40.0, comparisons.last().value, 1e-9)
        assertTrue(comparisons.last().fromRestingWindow)
    }

    @Test
    fun `a failed or empty window does not hide a later valid comparison`() = runTest {
        val native = (0 until 3).map { reading(it, duration = 600) }
        val comparisons = OuraNativeHrv.loadComparisons(native, emptyList()) { from, _, _ ->
            when (from) {
                native[0].windowStartTs -> throw IllegalStateException("read failed")
                native[1].windowStartTs -> emptyList()
                else -> beats(native[2])
            }
        }
        assertEquals(listOf(NoopHrvComparisonReading(native[2].day, 40.0, true)), comparisons)
    }

    @Test
    fun `leaving the screen cancels further window reads`() = runTest {
        var reads = 0
        try {
            OuraNativeHrv.loadComparisons(listOf(reading(0), reading(1)), emptyList()) { _, _, _ ->
                reads++
                throw CancellationException("screen closed")
            }
            fail("cancellation must reach the caller")
        } catch (_: CancellationException) {
            assertEquals(1, reads)
        }
    }
}
