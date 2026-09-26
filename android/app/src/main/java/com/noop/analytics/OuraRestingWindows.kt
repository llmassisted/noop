package com.noop.analytics

import com.noop.data.EventRow
import com.noop.data.OuraStreamMapping
import org.json.JSONObject

/** One contiguous run of Oura's own automatic-resting five-minute HR/HRV buckets. */
data class OuraRestingWindow(
    val startTs: Long,
    val endTs: Long,
    val bucketCount: Int,
    val avgHrBpm: Double?,
    val avgHrvMs: Double,
) {
    val durationSeconds: Long get() = endTs - startTs
}

/**
 * Converts durable Oura 0x5D events into resting windows without claiming that every rest is sleep.
 *
 * Every valid bucket is retained in [windows], including short awake rest and naps. [isSleepCandidate]
 * is deliberately narrower: in the absence of the ring's SleepNet phases, only a long continuous block
 * is strong enough to supply an automatic in-bed boundary. Short naps are recorded through NOOP's
 * explicit bedtime/wake or add-nap paths; a 40-minute awake resting block must not become fake sleep.
 */
object OuraRestingWindows {
    const val BUCKET_SECONDS: Long = 5L * 60L
    const val MAX_GAP_SECONDS: Long = 15L * 60L
    const val MIN_AUTOMATIC_SLEEP_SECONDS: Long = 90L * 60L

    fun windows(events: List<EventRow>): List<OuraRestingWindow> {
        data class Bucket(val ts: Long, val hr: Int?, val hrv: Double)

        val buckets = events.asSequence()
            .filter { it.kind == OuraStreamMapping.EVENT_HRV }
            .mapNotNull { event ->
                runCatching {
                    val json = JSONObject(event.payloadJSON)
                    val hrv = json.optDouble("rmssd_ms", Double.NaN)
                        .takeIf { it.isFinite() && it > 0.0 && it <= 255.0 } ?: return@runCatching null
                    val hr = json.optInt("hr_bpm", -1).takeIf { it in 20..240 }
                    Bucket(event.ts, hr, hrv)
                }.getOrNull()
            }
            .sortedBy { it.ts }
            // A replayed packet may contain the same five-minute bucket. Its event PK normally removes it,
            // but keeping the pure adapter idempotent makes tests/imported stores safe too.
            .distinctBy { it.ts }
            .toList()
        if (buckets.isEmpty()) return emptyList()

        val runs = ArrayList<List<Bucket>>()
        var current = ArrayList<Bucket>()
        for (bucket in buckets) {
            val previous = current.lastOrNull()
            if (previous != null && bucket.ts - previous.ts > MAX_GAP_SECONDS) {
                runs.add(current)
                current = ArrayList()
            }
            current.add(bucket)
        }
        if (current.isNotEmpty()) runs.add(current)

        return runs.map { run ->
            val hrs = run.mapNotNull { it.hr }
            OuraRestingWindow(
                startTs = run.first().ts,
                endTs = run.last().ts + BUCKET_SECONDS,
                bucketCount = run.size,
                avgHrBpm = hrs.takeIf { it.isNotEmpty() }?.average(),
                avgHrvMs = run.map { it.hrv }.average(),
            )
        }
    }

    fun isSleepCandidate(window: OuraRestingWindow): Boolean =
        window.durationSeconds >= MIN_AUTOMATIC_SLEEP_SECONDS &&
            // Tolerate an occasional missing native bucket, but do not join a sparse scatter of resting
            // values into one claimed sleep block merely because every individual gap stayed under 15 min.
            window.bucketCount * BUCKET_SECONDS * 10L >= window.durationSeconds * 7L
}
