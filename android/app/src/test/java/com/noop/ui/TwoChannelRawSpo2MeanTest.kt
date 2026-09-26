package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [twoChannelRawSpo2Mean] is the Raw SpO₂ tile's value AND its latest-row predicate. An Oura night scored
 * before `nightlySpo2RawMeans` went two-channel-only stored the ring's single channel as red ≈ 97 beside
 * ir = 0, and those rows stay in `dailyMetric` until the night is re-scored — so the tile must refuse them
 * itself rather than show a ~97 % reading as "~49 ADC". Twin of the iOS `spo2rawPoints` guard.
 */
class TwoChannelRawSpo2MeanTest {

    private fun row(red: Int?, ir: Int?) =
        DailyMetric(deviceId = "my-whoop-noop", day = "2026-09-01", spo2Red = red, spo2Ir = ir)

    @Test
    fun whoopTwoChannelRow_isTheRedIrMean() {
        assertEquals(26_000.0, twoChannelRawSpo2Mean(row(red = 30_000, ir = 22_000))!!, 1e-9)
    }

    @Test
    fun storedRingRowWithZeroIr_isNotARawReading() {
        assertNull(twoChannelRawSpo2Mean(row(red = 97, ir = 0)))
    }

    @Test
    fun missingEitherChannel_isNull() {
        assertNull(twoChannelRawSpo2Mean(row(red = null, ir = 22_000)))
        assertNull(twoChannelRawSpo2Mean(row(red = 30_000, ir = null)))
        assertNull(twoChannelRawSpo2Mean(row(red = null, ir = null)))
    }
}
