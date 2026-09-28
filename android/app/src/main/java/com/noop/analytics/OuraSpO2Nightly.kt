package com.noop.analytics

import com.noop.data.EventRow
import com.noop.data.OuraStreamMapping
import com.noop.oura.OuraSpO2Ratio
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Nightly SpO2 ESTIMATE from the Ring 4's verbatim `0x8b` records (OURA_SPO2_RPI events, OURA_PROTOCOL.md
 * s6.5.1). A record counts when its anchored time falls inside a detected sleep session; every sample in it is
 * converted with [OuraSpO2Ratio.percent]. A nightly low / median / mean / high needs no per-second timing, so
 * the unpinned within-record spacing does not matter here. Diagnostic estimate only: never `spo2Pct`, never
 * scored. Android-only.
 */
internal object OuraSpO2Nightly {
    /** Samples used, and the night's low / median / mean / high in whole percent (median and mean unrounded). */
    data class Summary(val samples: Int, val low: Int, val median: Double, val mean: Double, val high: Int)

    /**
     * Perfusion-index byte marking a held / no-signal sample: in the first Ring 4 capture all 6 of 64 such
     * samples carried the same R (0.766, ~93 %) while every other sample varied. Inferred, not documented.
     */
    const val PI_SATURATED = 0xFF

    fun summary(sessions: List<DetectedSleep>, rows: List<EventRow>): Summary? {
        if (sessions.isEmpty() || rows.isEmpty()) return null
        val values = ArrayList<Int>()
        for (row in rows) {
            if (row.kind != OuraStreamMapping.EVENT_SPO2_RPI) continue
            if (sessions.none { row.ts in it.start..it.end }) continue
            val o = runCatching { JSONObject(row.payloadJSON) }.getOrNull() ?: continue
            val r = o.optJSONArray("r_x16384") ?: continue
            val pi = o.optJSONArray("pi") ?: continue
            if (r.length() != pi.length()) continue
            for (i in 0 until r.length()) {
                if (pi.getInt(i) == PI_SATURATED) continue
                values.add(OuraSpO2Ratio.percent(r.getInt(i) / 16384.0))
            }
        }
        if (values.isEmpty()) return null
        values.sort()
        val n = values.size
        val median = if (n % 2 == 1) values[n / 2].toDouble() else (values[n / 2 - 1] + values[n / 2]) / 2.0
        return Summary(n, values.first(), median, values.average(), values.last())
    }

    /** The strap-log line for one night's summary (counts and percentages only). */
    fun line(s: Summary): String =
        "spo2 r-pi night samples=${s.samples} low=${s.low} median=${"%.1f".format(java.util.Locale.ROOT, s.median)} " +
            "mean=${"%.1f".format(java.util.Locale.ROOT, s.mean)} high=${s.high} (0x8b estimate, not scored)"

    /** The whole-percent value the SpO2 estimate tile shows. */
    fun displayMean(s: Summary): Int = s.mean.roundToInt()
}
