package com.noop.oura

// ActivityEstimate: aggregate the ring's 0x50 activity_info MET stream into a clearly-labeled activity
// estimate (active minutes / MET-minutes / active energy). Kotlin twin of the Swift ActivityEstimate.swift —
// the arithmetic and the 2 dp rounding must stay byte-identical (pinned by OuraActivityEstimatorTest's
// swiftc oracle). Derived only from already-decoded MET samples, never from a minted step count. The MET
// decode formula is third-party and NOT ground-truth-validated, so everything here stays Tier B: surfaced as
// an estimate, never folded into scoring. Facts per OURA_PROTOCOL.md s6.13.

/**
 * A MET-derived activity estimate for a set of 0x50 samples (one window, one day, whatever the caller
 * buckets). Every field is an ESTIMATE; the two energy fields are null when no body mass is supplied.
 */
data class OuraActivityEstimate(
    /** Number of MET samples aggregated (each 0x50 record contributes `met.size` of them). */
    val sampleCount: Int,
    /** The per-sample epoch length assumed for the minute/energy totals (the ring's cadence, 60 s). */
    val epochSeconds: Double,
    /** Mean MET across all samples (cadence-independent). */
    val meanMET: Double,
    /** Peak MET across all samples (cadence-independent). */
    val maxMET: Double,
    /** Σ metᵢ × epochMinutes — standard MET-minutes of activity. */
    val metMinutes: Double,
    /** Minutes whose MET ≥ the moderate threshold (default 3.0), = qualifying samples × epochMinutes. */
    val activeMinutes: Double,
    /** Estimated ABOVE-RESTING energy Σ max(metᵢ − 1, 0) × massKg × epochHours (kcal); null without mass. */
    val estActiveKcal: Double?,
    /** Estimated GROSS energy Σ metᵢ × massKg × epochHours (kcal), basal included; null without mass. */
    val estTotalKcal: Double?,
)

/** Pure MET-stream aggregation. No database, no BLE, no clock — the caller decides which samples belong to
 *  the window/day and passes them in. */
object OuraActivityEstimator {
    /**
     * Aggregate raw MET samples into an estimate. An empty input yields an all-zero estimate (never null —
     * "no activity" is a real answer). Scalars are rounded to 2 dp, half away from zero like Swift's
     * `rounded()` (all inputs here are non-negative, where that equals [Math.round]).
     */
    fun estimate(
        metSamples: List<Double>,
        epochSeconds: Double,
        bodyMassKg: Double? = null,
        moderateThresholdMET: Double = 3.0,
    ): OuraActivityEstimate {
        val epochMinutes = epochSeconds / 60.0
        val epochHours = epochSeconds / 3600.0
        val count = metSamples.size

        val sum = metSamples.fold(0.0) { acc, m -> acc + m }
        val mean = if (count > 0) sum / count else 0.0
        val peak = metSamples.maxOrNull() ?: 0.0

        val metMinutes = sum * epochMinutes
        val activeCount = metSamples.count { it >= moderateThresholdMET }
        val activeMinutes = activeCount * epochMinutes

        val activeKcal: Double?
        val totalKcal: Double?
        if (bodyMassKg != null) {
            // Active = above-resting (1-MET basal floor removed, clamped at 0); total = gross incl. basal.
            val aboveResting = metSamples.fold(0.0) { acc, m -> acc + maxOf(m - 1.0, 0.0) }
            activeKcal = aboveResting * bodyMassKg * epochHours
            totalKcal = sum * bodyMassKg * epochHours
        } else {
            activeKcal = null
            totalKcal = null
        }

        fun r2(x: Double): Double = Math.round(x * 100) / 100.0
        return OuraActivityEstimate(
            sampleCount = count,
            epochSeconds = epochSeconds,
            meanMET = r2(mean),
            maxMET = r2(peak),
            metMinutes = r2(metMinutes),
            activeMinutes = r2(activeMinutes),
            estActiveKcal = activeKcal?.let(::r2),
            estTotalKcal = totalKcal?.let(::r2),
        )
    }

    /** Convenience over decoded 0x50 records: flattens every record's `met` series and aggregates. The JVM
     *  name only avoids the List-erasure clash with the sample overload; Kotlin callers see `estimate`. */
    @JvmName("estimateFromRecords")
    fun estimate(
        records: List<OuraActivityInfo>,
        epochSeconds: Double,
        bodyMassKg: Double? = null,
        moderateThresholdMET: Double = 3.0,
    ): OuraActivityEstimate =
        estimate(records.flatMap { it.met }, epochSeconds, bodyMassKg, moderateThresholdMET)
}
