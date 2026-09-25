package com.noop.analytics

import com.noop.data.Spo2Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure unit test for [AnalyticsEngine.nightlySpo2RawMeans] (WHOOP 4.0 raw SpO2 red/IR ADC means over
 * detected sleep, #93). Twin of the Swift `nightlySpo2RawMeans`. No wear gate: the strap streams SpO2
 * only on-wrist, so a sample counts purely by whether its timestamp lands inside a detected in-bed span.
 */
class NightlySpo2RawTest {

    private fun sleep(start: Long, end: Long) =
        DetectedSleep(start = start, end = end, efficiency = 0.9, stages = emptyList(),
            restingHR = 55, avgHRV = 60.0)

    private fun spo2(ts: Long, red: Int, ir: Int) =
        Spo2Sample(deviceId = "my-whoop", ts = ts, red = red, ir = ir)

    @Test
    fun emptyInputs_returnNull() {
        assertNull(AnalyticsEngine.nightlySpo2RawMeans(emptyList(), listOf(spo2(100, 1, 1))))
        assertNull(AnalyticsEngine.nightlySpo2RawMeans(listOf(sleep(0, 1000)), emptyList()))
    }

    @Test
    fun inWindowSamples_averageRedAndIrSeparately() {
        val sessions = listOf(sleep(1000, 2000))
        val samples = listOf(
            spo2(1100, red = 30000, ir = 20000),
            spo2(1500, red = 32000, ir = 24000),
        )
        val (red, ir) = AnalyticsEngine.nightlySpo2RawMeans(sessions, samples)!!
        assertEquals(31000, red)   // (30000 + 32000) / 2
        assertEquals(22000, ir)    // (20000 + 24000) / 2
    }

    @Test
    fun samplesOutsideEveryWindow_returnNull() {
        val sessions = listOf(sleep(1000, 2000))
        val samples = listOf(spo2(500, 1, 1), spo2(2500, 2, 2))  // both outside [1000, 2000]
        assertNull(AnalyticsEngine.nightlySpo2RawMeans(sessions, samples))
    }

    @Test
    fun onlyInWindowSamplesCount_boundariesInclusive() {
        val sessions = listOf(sleep(1000, 2000))
        val samples = listOf(
            spo2(999, red = 9, ir = 9),        // just before → dropped
            spo2(1000, red = 100, ir = 200),   // inclusive start → kept
            spo2(2000, red = 300, ir = 400),   // inclusive end → kept
            spo2(2001, red = 9, ir = 9),       // just after → dropped
        )
        val (red, ir) = AnalyticsEngine.nightlySpo2RawMeans(sessions, samples)!!
        assertEquals(200, red)   // (100 + 300) / 2
        assertEquals(300, ir)    // (200 + 400) / 2
    }

    @Test
    fun multipleSessions_unionOfWindows() {
        val sessions = listOf(sleep(1000, 1500), sleep(3000, 3500))
        val samples = listOf(
            spo2(1200, red = 10, ir = 20),   // in first
            spo2(2000, red = 99, ir = 99),   // gap → dropped
            spo2(3200, red = 30, ir = 40),   // in second
        )
        val (red, ir) = AnalyticsEngine.nightlySpo2RawMeans(sessions, samples)!!
        assertEquals(20, red)   // (10 + 30) / 2
        assertEquals(30, ir)    // (20 + 40) / 2
    }

    // The same table carries the Oura ring's single-channel rows (red = the ring's reading, ir = 0, as
    // OuraStreamMapping writes them). They used to be averaged in, so a ring night reported an IR mean of
    // 0 beside a red mean of ~97 and the Raw SpO₂ tile's (red + ir) / 2 showed ~97 % as "~49 ADC".
    // Twin of the Swift Spo2RawNightlyTests.

    private fun ring(ts: Long, value: Int) = Spo2Sample(deviceId = "oura-ring", ts = ts, red = value, ir = 0)

    @Test
    fun ringOnlyNight_hasNoRawMeans() {
        val sessions = listOf(sleep(1000, 2000))
        assertNull(AnalyticsEngine.nightlySpo2RawMeans(sessions, listOf(ring(1100, 97), ring(1200, 98))))
    }

    @Test
    fun legacyPerfusionRow_isExcluded() {
        // A pre-#2152 dc_raw row, stored with ir = 0, cannot inflate the mean either.
        assertNull(AnalyticsEngine.nightlySpo2RawMeans(listOf(sleep(1000, 2000)), listOf(ring(1100, 11_709_098))))
    }

    @Test
    fun singleChannelRows_doNotDiluteTwoChannelMeans() {
        val sessions = listOf(sleep(1000, 2000))
        val samples = listOf(
            spo2(1100, red = 100, ir = 200),
            ring(1200, 97),
            spo2(1300, red = 300, ir = 400),
        )
        val (red, ir) = AnalyticsEngine.nightlySpo2RawMeans(sessions, samples)!!
        assertEquals(200, red)   // (100 + 300) / 2 — the ring row must not count
        assertEquals(300, ir)
    }

    @Test
    fun ringRows_stillReachTheCeilingMean() {
        val sessions = listOf(sleep(1000, 2000))
        val rows = listOf(ring(1100, 97), ring(1200, 99))
        assertNull(AnalyticsEngine.nightlySpo2RawMeans(sessions, rows))
        assertEquals(98, AnalyticsEngine.nightlySpo2CeilingMean(sessions, rows)!!.first)
    }
}
