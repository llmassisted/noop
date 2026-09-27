package com.noop.oura

import com.noop.data.OuraStreamMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `0x8b` spo2_r_pi decode (OURA_PROTOCOL.md s6.5.1). Fixtures are verbatim payloads from the first
 * Ring 4 (`ORE_06`) capture after SpO2 was enabled (strap log 2026-09-27); expected percentages are the
 * gen4 quadratic, clamped 85-100 and rounded.
 */
class OuraSpO2RatioTest {
    private fun rec(hex: String, rt: Long = 44_340_307L) =
        OuraRecord(type = 0x8B, ringTimestamp = rt, payload = hex.chunked(2).map { it.toInt(16) }.toIntArray())

    @Test
    fun decodesRealRing4Payload() {
        val out = OuraDecoders.decodeSpO2RatioPi(rec("0023619125d576248772235a72"))!!
        assertEquals(listOf(98, 98, 98, 98), out.map { it.value })
        assertEquals(listOf(0, 1, 2, 3), out.map { it.index })
        assertTrue(out.all { it.count == 4 && it.unit == OuraSpO2Channel.RATIO_PERCENT_UNIT })
        assertEquals(
            listOf(96, 96, 94, 93),
            OuraDecoders.decodeSpO2RatioPi(rec("002a6a932a6b922faff2310aff".replace("310aff", "310a00")))!!.map { it.value },
        )
    }

    @Test
    fun saturatedPerfusionSampleIsSkippedButKeepsItsSecond() {
        // First sample carries PI 0xFF (the held ~0.766 R); the rest are real.
        val out = OuraDecoders.decodeSpO2RatioPi(rec("003104ff2477c7261ac622cb6b"))!!
        assertEquals(listOf(98, 97, 98), out.map { it.value })
        assertEquals(listOf(1, 2, 3), out.map { it.index })
        assertTrue(out.all { it.count == 4 })
        // A record that is ALL held values yields nothing.
        assertNull(OuraDecoders.decodeSpO2RatioPi(rec("00310bff310aff310aff310aff")))
    }

    @Test
    fun malformedPayloadsDecodeToNull() {
        assertNull(OuraDecoders.decodeSpO2RatioPi(rec("00")))
        assertNull(OuraDecoders.decodeSpO2RatioPi(rec("0031a28832")))     // not 1 + 3n
        // The smallest well-formed record: one header byte + one sample.
        assertEquals(listOf(93), OuraDecoders.decodeSpO2RatioPi(rec("0031a288"))!!.map { it.value })
    }

    @Test
    fun percentIsClampedToTheDocumentedRange() {
        assertEquals(100, OuraSpO2Ratio.percent(0.0))
        assertEquals(85, OuraSpO2Ratio.percent(1.5))
        assertEquals(97, OuraSpO2Ratio.percent(0.6))   // 105.2 - 3.06 - 4.824 = 97.316
    }

    @Test
    fun driverRoutesTheTagAndMappingPersistsDerivedPercentages() {
        val d = OuraDriver(ringGen = OuraRingGen.GEN4, authKey = IntArray(16) { it })
        val events = d.ingest(rec("0023619125d576248772235a72"))
        assertEquals(4, events.size)
        assertTrue(events.all { it is OuraEvent.Spo2 })
        val streams = OuraStreamMapping.streams(events) { 1_790_000_000 }
        assertEquals(listOf(1_789_999_997, 1_789_999_998, 1_789_999_999, 1_790_000_000), streams.spo2.map { it.ts })
        assertTrue(streams.spo2.all { it.red == 98 && it.ir == 0 && it.unit == OuraSpO2Channel.RATIO_PERCENT_UNIT })
    }

    @Test
    fun channelNamesTheDerivedPercentage() {
        assertEquals(OuraSpO2Channel.RATIO_PERCENTAGE, OuraSpO2Channel.forUnit(OuraSpO2Channel.RATIO_PERCENT_UNIT))
        assertEquals(OuraSpO2Channel.PERCENTAGE, OuraSpO2Channel.forUnit(OuraSpO2Channel.PERCENTAGE_UNIT))
    }
}
