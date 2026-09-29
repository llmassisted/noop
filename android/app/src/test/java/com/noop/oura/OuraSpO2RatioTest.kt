package com.noop.oura

import com.noop.analytics.DetectedSleep
import com.noop.analytics.OuraSpO2Nightly
import com.noop.data.EventRow
import com.noop.data.OuraStreamMapping
import com.noop.data.StreamPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `0x8b` spo2_r_pi path (OURA_PROTOCOL.md s6.5.1): verbatim decode → ONE OURA_SPO2_RPI event per record →
 * the stored row → the nightly estimate. Fixtures are verbatim payloads from the first Ring 4 (`ORE_06`)
 * capture after SpO2 was enabled (strap log 2026-09-27), with their real ring-times.
 */
class OuraSpO2RatioTest {
    private fun rec(hex: String, rt: Long = 44_340_307L) =
        OuraRecord(type = 0x8B, ringTimestamp = rt, payload = hex.chunked(2).map { it.toInt(16) }.toIntArray())

    /** The 03:57 capture: 8 records, real ring-times (2.3-4.6 s apart), including PI=255 samples. */
    private val capture = listOf(
        44_173_853L to "0023619125d576248772235a72",
        44_173_899L to "0022c37727296a271e6d23546c",
        44_173_922L to "0023266c238f6c232094282f84",
        44_173_967L to "002a6a932a6b922faff2310aff",
        44_174_013L to "00310bff310aff310aff310aff",
        44_174_058L to "003104ff2477c7261ac622cb6b",
        44_174_081L to "00228b9622439622b1d42497ee",
        44_174_122L to "002247db23cdc5246bd7258ada",
    )

    @Test
    fun decodesTheRecordVerbatim() {
        val r = OuraDecoders.decodeSpO2RatioPi(rec("0031a28832849133547832d66a"))!!
        assertEquals(listOf(0x31a2, 0x3284, 0x3354, 0x32d6), r.rX16384)
        assertEquals(listOf(0x88, 0x91, 0x78, 0x6a), r.pi)
        assertEquals(44_340_307L, r.ringTimestamp)
        // PI=255 samples are kept verbatim, just like every other sample.
        assertEquals(listOf(0xff, 0xff, 0xff, 0xff), OuraDecoders.decodeSpO2RatioPi(rec("00310bff310aff310aff310aff"))!!.pi)
    }

    @Test
    fun malformedPayloadsDecodeToNull() {
        assertNull(OuraDecoders.decodeSpO2RatioPi(rec("00")))
        assertNull(OuraDecoders.decodeSpO2RatioPi(rec("0031a28832")))     // not 1 + 3n
    }

    @Test
    fun everyCapturedRecordSurvivesToItsOwnStoredRowWithItsSource() {
        val d = OuraDriver(ringGen = OuraRingGen.GEN4, authKey = IntArray(16) { it })
        val base = 1_790_468_000L
        for (phase in listOf(0.0, 0.3, 0.6, 0.9)) {
            val events = capture.flatMap { (rt, hex) -> d.ingest(rec(hex, rt)) }
            assertTrue(events.all { it is OuraEvent.Spo2Ratio })
            // Anchor ring ticks (0.1 s) to whole seconds at several sub-second phases, like the live anchor.
            val streams = OuraStreamMapping.streams(events) { rt -> (base + Math.floor(rt / 10.0 + phase)).toInt() }
            val batch = StreamPersistence.toBatch(streams)
            // Nothing lands in spo2Sample (no unit column there), and every record keeps a distinct key.
            assertTrue(batch.spo2.isEmpty())
            assertEquals(capture.size, batch.events.map { it.ts }.toSet().size)
            assertTrue(batch.events.all { it.kind == OuraStreamMapping.EVENT_SPO2_RPI })
        }
        val one = StreamPersistence.toBatch(
            OuraStreamMapping.streams(d.ingest(rec("0031a28832849133547832d66a"))) { 1_790_000_000 },
        ).events.single()
        assertEquals("""{"pi":[136,145,120,106],"r_x16384":[12706,12932,13140,13014]}""", one.payloadJSON)
    }

    @Test
    fun nightlySummaryIncludesPi255AndCountsOnlyInSessionSamples() {
        val night = DetectedSleep(start = 1_000L, end = 2_000L, efficiency = 1.0, stages = emptyList(), restingHR = null, avgHRV = null)
        fun row(ts: Long, hex: String) = EventRow(
            "oura-x", ts, OuraStreamMapping.EVENT_SPO2_RPI,
            StreamPersistence.toBatch(OuraStreamMapping.streams(listOf(OuraEvent.Spo2Ratio(OuraDecoders.decodeSpO2RatioPi(rec(hex))!!))) { ts.toInt() })
                .events.single().payloadJSON,
        )
        val rows = listOf(
            row(1_100L, "0023619125d576248772235a72"),   // 98 98 98 98
            row(1_200L, "003104ff2477c7261ac622cb6b"),   // 93 98 97 98; one PI=255
            row(1_300L, "00310bff310aff310aff310aff"),   // 93 93 93 93; all PI=255
            row(3_000L, "00310bff310aff310aff310aff"),   // outside the night -> ignored, including PI count
        )
        val s = OuraSpO2Nightly.summary(listOf(night), rows)!!
        assertEquals(12, s.samples)
        assertEquals(5, s.pi255)
        assertEquals(93, s.low)
        assertEquals(98, s.high)
        assertEquals(97.5, s.median, 0.0)
        assertEquals((98 * 6 + 97 + 93 * 5) / 12.0, s.mean, 1e-9)
        assertEquals(96, OuraSpO2Nightly.displayMean(s))
        assertEquals(4, OuraSpO2Nightly.summary(listOf(night), listOf(rows[2]))!!.samples)
        assertEquals(0, OuraSpO2Nightly.summary(listOf(night), rows.take(1))!!.pi255)
        assertNull(OuraSpO2Nightly.summary(listOf(night), rows.takeLast(1)))
    }

    @Test
    fun varyingPi255SamplesFromSeptember28ContributeAndAreLogged() {
        // Verbatim 04:50 capture: three different R values have PI=255 (estimates 98, 97, 97).
        val record = OuraDecoders.decodeSpO2RatioPi(rec("00254fff2863ff2605ff2456b9"))!!
        val stored = StreamPersistence.toBatch(
            OuraStreamMapping.streams(listOf(OuraEvent.Spo2Ratio(record))) { 1_100 },
        ).events.single()
        val row = EventRow("oura-x", stored.ts, stored.kind, stored.payloadJSON)
        val night = DetectedSleep(start = 1_000L, end = 2_000L, efficiency = 1.0, stages = emptyList(), restingHR = null, avgHRV = null)
        val s = OuraSpO2Nightly.summary(listOf(night), listOf(row))!!
        assertEquals(4, s.samples)
        assertEquals(3, s.pi255)
        assertEquals(97.5, s.mean, 0.0)
        assertEquals(
            "spo2 r-pi night samples=4 pi255=3 low=97 median=97.5 mean=97.5 high=98 (0x8b estimate, not scored)",
            OuraSpO2Nightly.line(s),
        )
    }

    @Test
    fun percentIsClampedToTheDocumentedRange() {
        assertEquals(100, OuraSpO2Ratio.percent(0.0))
        assertEquals(85, OuraSpO2Ratio.percent(1.5))
        assertEquals(93, OuraSpO2Ratio.percent(0x31a2 / 16384.0))
    }
}
