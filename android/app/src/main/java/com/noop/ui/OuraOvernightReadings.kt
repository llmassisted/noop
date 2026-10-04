package com.noop.ui

import com.noop.analytics.OuraRestingWindows
import com.noop.analytics.SleepSessionDedup
import com.noop.data.EventRow
import com.noop.data.OuraStreamMapping
import com.noop.data.SkinTempSample
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import com.noop.oura.OuraSpO2Ratio
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One stored reading. SpO2 samples share their record time; index preserves every sample without guessed seconds. */
internal data class OvernightVitalReading(val ts: Long, val value: Double, val index: Int = 0, val count: Int = 1)

/** Raw readings for sleep windows ending on the selected local date, independent of daily-average availability. */
internal data class OvernightVitalData(val windows: List<LongRange>, val readings: List<OvernightVitalReading>)

/** Exact sample range and median within one occupied display interval; absent intervals are not filled. */
internal data class OvernightChartBucket(
    val start: Long, val end: Long, val low: Double, val median: Double, val high: Double, val count: Int,
)

/** Android Oura detail adapter: reads existing records only, without changing nightly scoring or storage. */
internal object OuraOvernightReadings {
    fun windows(sessions: List<SleepSession>, day: LocalDate, zone: ZoneId): List<LongRange> =
        SleepSessionDedup.dedupe(sessions).kept.filter {
            it.endTs > it.effectiveStartTs && Instant.ofEpochSecond(it.endTs).atZone(zone).toLocalDate() == day
        }.map { it.effectiveStartTs..it.endTs }

    fun ratios(rows: List<EventRow>, windows: List<LongRange>): List<OvernightVitalReading> =
        rows.asSequence().filter { it.kind == OuraStreamMapping.EVENT_SPO2_RPI && windows.any { w -> it.ts in w } }
            .sortedBy { it.ts }.flatMap { row ->
                // Ignore a malformed record as a unit, never display a partial record with shifted indices.
                runCatching {
                    val json = JSONObject(row.payloadJSON)
                    val r = json.getJSONArray("r_x16384")
                    val pi = json.getJSONArray("pi")
                    require(r.length() == pi.length())
                    List(r.length()) { i ->
                        OvernightVitalReading(row.ts, OuraSpO2Ratio.percent(r.getInt(i) / 16384.0).toDouble(), i, r.length())
                    }
                }.getOrDefault(emptyList()).asSequence()
            }.toList()

    /** Five-minute display summaries only. Every raw reading remains in the list and nightly statistics. */
    fun chartBuckets(readings: List<OvernightVitalReading>): List<OvernightChartBucket> =
        readings.asSequence().filter { it.value.isFinite() }
            .groupBy { Math.floorDiv(it.ts, 300L) * 300L }.toSortedMap().map { (start, rows) ->
                val values = rows.map { it.value }.sorted()
                val n = values.size
                val median = if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2.0
                OvernightChartBucket(start, start + 300L, values.first(), median, values.last(), n)
            }

    /** Empty time intervals have no selectable value; never snap across a gap in recording. */
    fun chartBucketAt(buckets: List<OvernightChartBucket>, ts: Long): Int =
        buckets.indexOfFirst { ts >= it.start && ts < it.end }

    fun temperatures(rows: List<SkinTempSample>, windows: List<LongRange>): List<OvernightVitalReading> =
        rows.filter { row -> windows.any { row.ts in it } }.sortedBy { it.ts }
            .map { OvernightVitalReading(it.ts, it.raw / 100.0) }

    /** Page by the unique timestamp within one device and event kind, so long nights cannot silently hit a row cap. */
    suspend fun <T> readAll(from: Long, to: Long, timestamp: (T) -> Long,
                          read: suspend (Long, Long, Int) -> List<T>): List<T> {
        val out = ArrayList<T>()
        var cursor = from
        while (cursor <= to) {
            val page = read(cursor, to, 10_000)
            out.addAll(page)
            if (page.size < 10_000) break
            val last = timestamp(page.last())
            check(last >= cursor) { "Non-advancing overnight stream" }
            if (last >= to) break
            cursor = last + 1
        }
        return out
    }

    suspend fun load(repo: WhoopRepository, owner: String, key: String, day: LocalDate, zone: ZoneId): OvernightVitalData =
        withContext(Dispatchers.IO) {
            val from = day.minusDays(1).atStartOfDay(zone).toEpochSecond()
            val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
            val sleeps = repo.sleepSessionsForDevice(repo.computedDeviceId(owner), from, to, 4_000) +
                repo.sleepSessionsForDevice(owner, from, to, 4_000)
            val windows = windows(sleeps, day, zone).ifEmpty {
                val rests = readAll(from, to, EventRow::ts) { lo, hi, limit ->
                    repo.eventsByKind(owner, OuraStreamMapping.EVENT_HRV, lo, hi, limit)
                }
                OuraRestingWindows.windows(rests).filter {
                    OuraRestingWindows.isSleepCandidate(it) && Instant.ofEpochSecond(it.endTs).atZone(zone).toLocalDate() == day
                }.map { it.startTs..it.endTs }
            }
            if (windows.isEmpty()) return@withContext OvernightVitalData(emptyList(), emptyList())
            val lo = windows.minOf { it.first }
            val hi = windows.maxOf { it.last }
            val readings = if (key == "skin") {
                temperatures(readAll(lo, hi, SkinTempSample::ts) { a, b, limit -> repo.skinTempSamples(owner, a, b, limit) }, windows)
            } else {
                val records = readAll(lo, hi, EventRow::ts) { a, b, limit ->
                    repo.eventsByKind(owner, OuraStreamMapping.EVENT_SPO2_RPI, a, b, limit)
                }
                ratios(records, windows).ifEmpty {
                    // Older firmware-percentage nights retain the same ceiling transform as their nightly estimate.
                    readAll(lo, hi, com.noop.data.Spo2Sample::ts) { a, b, limit -> repo.spo2Samples(owner, a, b, limit) }
                        .filter { row -> row.ir == 0 && row.red in 50..110 && windows.any { row.ts in it } }
                        .map { OvernightVitalReading(it.ts, minOf(it.red, 100).toDouble()) }
                }
            }
            OvernightVitalData(windows, readings)
        }
}
