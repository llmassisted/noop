package com.noop.oura

import kotlin.math.roundToInt

/**
 * SpO2 from the ring's `0x8b` R-ratio (OURA_PROTOCOL.md s6.5.1): `SpO2(%) = a·r² + b·r + c`, clamped to
 * [MIN_PERCENT]..[MAX_PERCENT]. The coefficients are the gen4/"oreo" hardware row cited by [open_oura-spo2]
 * as interoperability facts (the "cooper" row differs by < 1 % on real data); a Ring 4 reports hardware
 * `ORE_06`. Not a validated calibration: the result is an ESTIMATE, surfaced only through the SpO2 estimate
 * display path and never written to `spo2Pct` or scored.
 */
object OuraSpO2Ratio {
    const val COEF_A = -13.4
    const val COEF_B = -5.1
    const val COEF_C = 105.2
    const val MIN_PERCENT = 85.0
    const val MAX_PERCENT = 100.0

    /** Whole-percent SpO2 for one R-ratio sample. */
    fun percent(r: Double): Int = (COEF_A * r * r + COEF_B * r + COEF_C).coerceIn(MIN_PERCENT, MAX_PERCENT).roundToInt()
}
