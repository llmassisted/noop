package com.noop.ui

import com.noop.data.EventRow
import com.noop.data.OuraStreamMapping
import com.noop.oura.OuraActivityEstimate
import com.noop.oura.OuraActivityEstimator
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject

/** One local calendar day of the Oura ring's MET stream, aggregated into a Tier-B activity estimate. */
internal data class OuraActivityDay(val day: String, val estimate: OuraActivityEstimate)

/**
 * UI-only adapter from durable `OURA_MET` rows (0x50, [OuraStreamMapping.EVENT_MET]) to per-day activity
 * estimates for the Health card. Never scored and never a step count (OURA_PROTOCOL.md s6.13).
 *
 * Each row's samples are laid BACKWARD from the row's anchored time at the stored per-sample cadence, the
 * convention of the sibling per-record series (0x6F SpO2, 0x5D HRV). Which end of a 0x50 record is the
 * oldest minute is not pinned, so a sample's time is only good to within its record's span (~13 min): it
 * can move a few minutes across midnight, never more. Gaps between records stay gaps: the ring stops logging
 * when still, so nothing is interpolated and an uncovered minute simply is not counted.
 */
internal object OuraActivityDays {
    fun aggregate(
        rows: List<EventRow>,
        zone: ZoneId = ZoneId.systemDefault(),
        bodyMassKg: Double? = null,
    ): List<OuraActivityDay> {
        val byDay = sortedMapOf<String, MutableList<Double>>()
        for (row in rows) {
            if (row.kind != OuraStreamMapping.EVENT_MET) continue
            val o = runCatching { JSONObject(row.payloadJSON) }.getOrNull() ?: continue
            val arr = o.optJSONArray("met_x10") ?: continue
            val sps = o.optInt("sec_per_sample", OuraStreamMapping.MET_SECONDS_PER_SAMPLE).toLong()
            if (sps != OuraStreamMapping.MET_SECONDS_PER_SAMPLE.toLong()) continue   // one epoch per estimate
            val n = arr.length()
            for (i in 0 until n) {
                val ts = row.ts - (n - 1 - i) * sps
                val day = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toString()
                byDay.getOrPut(day) { mutableListOf() }.add(arr.getInt(i) / 10.0)
            }
        }
        return byDay.map { (day, met) ->
            OuraActivityDay(
                day = day,
                estimate = OuraActivityEstimator.estimate(
                    met,
                    epochSeconds = OuraStreamMapping.MET_SECONDS_PER_SAMPLE.toDouble(),
                    bodyMassKg = bodyMassKg,
                ),
            )
        }
    }
}
