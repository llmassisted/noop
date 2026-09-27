package com.noop.ui

import com.noop.analytics.SleepStager
import com.noop.data.EventRow
import com.noop.data.OuraStreamMapping
import com.noop.data.RrInterval
import com.noop.data.SleepSession
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One wake-day summary of the Oura ring's own open 0x5D five-minute RMSSD buckets. */
internal data class OuraNativeHrvReading(
    val day: String,
    val valueMs: Double,
    val bucketCount: Int,
    /** Inclusive bounds of the ring's five-minute buckets. The end is one bucket past the last event so
     *  the UI-only NOOP comparator can read the same resting interval from the durable R-R stream. */
    val windowStartTs: Long,
    val windowEndTs: Long,
)

/** One NOOP-side value used only in the Oura comparison card. Canonical values come from DailyMetric;
 * resting-window fallbacks use raw R-R and never alter DailyMetric or Charge. */
internal data class NoopHrvComparisonReading(
    val day: String,
    val value: Double,
    val fromRestingWindow: Boolean,
)

/**
 * UI-only comparison adapter for Oura's native RMSSD stream.
 *
 * NOOP's canonical [com.noop.data.DailyMetric.avgHrv] remains unchanged and continues to feed Charge.
 * This adapter only makes the ring's separately stored `OURA_HRV` evidence visible beside it. Buckets are
 * assigned to a detected sleep session, then to that session's LOCAL wake day, matching the daily metric's
 * night ownership. Ring 4 can also expose automatic-resting 0x5D buckets outside a sleep session (including
 * short naps/rest); those ring-owned buckets remain visible and are grouped into local noon-to-noon wake
 * days. This does not invent a sleep session or feed Charge: it only makes the ring's real resting
 * measurement visible and provides its exact interval to the separately-labelled NOOP RMSSD comparator.
 */
internal object OuraNativeHrv {
    /** Fill comparison gaps one resting window at a time. A single oldest-first capped query over the
     * whole history discards recent nights once earlier beats consume its limit. Per-window reads also
     * keep only one night's raw beats in memory and preserve every canonical NOOP reading. */
    suspend fun loadComparisons(
        native: List<OuraNativeHrvReading>,
        canonical: List<NoopHrvComparisonReading>,
        readRr: suspend (from: Long, to: Long, limit: Int) -> List<RrInterval>,
    ): List<NoopHrvComparisonReading> {
        val canonicalDays = canonical.mapTo(HashSet()) { it.day }
        val missing = native.filter { it.day !in canonicalDays }
        val comparisons = canonical.toMutableList()
        for (reading in missing) {
            val rr = try {
                readRr(reading.windowStartTs, reading.windowEndTs, RR_LIMIT_PER_WINDOW)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            val value = withContext(Dispatchers.Default) {
                SleepStager.sessionAvgHRV(reading.windowStartTs, reading.windowEndTs, rr)
            } ?: continue
            comparisons.add(NoopHrvComparisonReading(reading.day, value, fromRestingWindow = true))
        }
        return comparisons.sortedBy { it.day }
    }

    fun aggregate(
        events: List<EventRow>,
        sleepSessions: List<SleepSession>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<OuraNativeHrvReading> {
        if (events.isEmpty()) return emptyList()

        val sessions = sleepSessions.sortedBy { it.effectiveStartTs }
        val hrvEvents = events.filter { it.kind == OuraStreamMapping.EVENT_HRV }.sortedBy { it.ts }
        val byDay = linkedMapOf<String, MutableList<Pair<Long, Double>>>()
        var sessionIndex = 0
        for (event in hrvEvents) {
            val value = parseRmssd(event.payloadJSON) ?: continue
            // Both inputs are chronological. Advance past sessions that have ended, then inspect only the
            // sessions whose onset is at/before this event. This stays linear for a long Oura history rather
            // than scanning hundreds of past nights for every five-minute bucket.
            while (sessionIndex < sessions.size && sessions[sessionIndex].endTs < event.ts) sessionIndex++
            var candidateIndex = sessionIndex
            var session: SleepSession? = null
            while (candidateIndex < sessions.size && sessions[candidateIndex].effectiveStartTs <= event.ts) {
                val candidate = sessions[candidateIndex]
                if (event.ts <= candidate.endTs) {
                    session = candidate
                    break
                }
                candidateIndex++
            }
            val day = if (session != null) {
                Instant.ofEpochSecond(session.endTs).atZone(zone).toLocalDate().toString()
            } else {
                // Keep short ring-resting windows visible even after NOOP has learned a separate main sleep
                // session. A noon boundary is calendar ownership, not a claim that this bucket was sleep:
                // 23:00 and 06:00 belong to the same next-morning wake day, including UTC/local-date edges.
                val local = Instant.ofEpochSecond(event.ts).atZone(zone)
                val wakeDate = if (local.hour >= 12) local.toLocalDate().plusDays(1) else local.toLocalDate()
                wakeDate.toString()
            }
            byDay.getOrPut(day) { ArrayList() }.add(event.ts to value)
        }

        return byDay.entries
            .sortedBy { it.key }
            .map { (day, buckets) ->
                OuraNativeHrvReading(
                    day = day,
                    valueMs = buckets.map { it.second }.average(),
                    bucketCount = buckets.size,
                    windowStartTs = buckets.minOf { it.first },
                    windowEndTs = buckets.maxOf { it.first } + FIVE_MINUTES_SECONDS,
                )
            }
    }

    private fun parseRmssd(payloadJSON: String): Double? = runCatching {
        val value = JSONObject(payloadJSON).optDouble("rmssd_ms", Double.NaN)
        value.takeIf { it.isFinite() && it > 0.0 && it <= 255.0 }
    }.getOrNull()

    private const val FIVE_MINUTES_SECONDS = 5L * 60L
    private const val RR_LIMIT_PER_WINDOW = 250_000
}
