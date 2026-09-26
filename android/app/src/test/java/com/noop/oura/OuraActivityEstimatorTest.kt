package com.noop.oura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The MET-stream activity estimate — Kotlin twin of Swift's ActivityEstimateTests, plus a byte-identical
 * oracle: `oura_activity_estimate_swift_oracle.txt` is the verbatim stdout of the Swift
 * `ActivityEstimate.swift` compiled standalone (`swiftc -O ActivityEstimate.swift main.swift`) over 300
 * generated inputs from the LCG below. Regenerate it from the Swift source whenever either twin changes.
 */
class OuraActivityEstimatorTest {

    @Test
    fun emptyInputYieldsZeroEstimateNotNull() {
        val e = OuraActivityEstimator.estimate(emptyList<Double>(), epochSeconds = 60.0)
        assertEquals(0, e.sampleCount)
        assertEquals(0.0, e.meanMET, 0.0)
        assertEquals(0.0, e.activeMinutes, 0.0)
        assertNull(e.estActiveKcal)
    }

    @Test
    fun meanMaxAndMetMinutesAt60s() {
        val e = OuraActivityEstimator.estimate(listOf(1.0, 1.0, 3.0, 3.0), epochSeconds = 60.0)
        assertEquals(2.0, e.meanMET, 0.0)
        assertEquals(3.0, e.maxMET, 0.0)
        assertEquals(8.0, e.metMinutes, 0.0)
        assertEquals(2.0, e.activeMinutes, 0.0)
    }

    @Test
    fun subRestingSampleNeverContributesNegativeActiveEnergy() {
        val e = OuraActivityEstimator.estimate(listOf(0.5), epochSeconds = 60.0, bodyMassKg = 80.0)
        assertEquals(0.0, e.estActiveKcal!!, 0.0)
        assertTrue(e.estTotalKcal!! > 0.0)
    }

    @Test
    fun goldenEndToEndFromRealisticSample() {
        val met = listOf(1.2, 0.9, 0.9, 0.9, 1.0, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 1.2)
        val e = OuraActivityEstimator.estimate(met, epochSeconds = 60.0, bodyMassKg = 70.0)
        assertEquals(0.0, e.activeMinutes, 0.0)
        assertEquals(0.95, e.meanMET, 0.0)
        assertEquals(0.47, e.estActiveKcal!!, 0.01)
    }

    @Test
    fun matchesTheSwiftTwinByteForByte() {
        val expected = javaClass.classLoader!!.getResourceAsStream(ORACLE_RESOURCE)!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
        var s = 42UL
        fun next(): ULong {
            s = s * 6364136223846793005UL + 1442695040888963407UL
            return s shr 33
        }
        fun f(x: Double?): String = x?.let { String.format(Locale.ROOT, "%.10f", it) } ?: "nil"
        val actual = (0 until 300).map { i ->
            val n = (next() % 40UL).toInt()
            val met = ArrayList<Double>(n)
            repeat(n) { met.add((next() % 160UL).toDouble() / 10.0 + (if (next() % 4UL == 0UL) 0.05 else 0.0)) }
            val epoch = listOf(30.0, 60.0, 90.0)[(next() % 3UL).toInt()]
            val mass: Double? = if (next() % 3UL == 0UL) null else (45UL + next() % 80UL).toDouble() + 0.5
            val e = OuraActivityEstimator.estimate(met, epochSeconds = epoch, bodyMassKg = mass)
            "$i ${e.sampleCount} ${f(e.meanMET)} ${f(e.maxMET)} ${f(e.metMinutes)} ${f(e.activeMinutes)} " +
                "${f(e.estActiveKcal)} ${f(e.estTotalKcal)}"
        }
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("case $i", expected[i], actual[i])
    }

    private companion object {
        const val ORACLE_RESOURCE = "oura_activity_estimate_swift_oracle.txt"
    }
}
