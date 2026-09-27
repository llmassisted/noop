package com.noop.oura

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The feature-mode WRITE (`2f 03 22 <id> <mode>`) and its generalized status read (`2f 02 20 <id>`) —
 * UNVALIDATED on NOOP's own hardware (OURA_PROTOCOL.md s7.5). Kotlin twin of Swift's
 * OuraFeatureModeWriteTests, plus the Android routing of the ring's `0x23` verdict. The builders carry no
 * gate of their own; the Test Centre confirmation is what keeps this off the automatic connect path.
 */
class OuraFeatureModeWriteTest {
    private val key: IntArray = IntArray(16) { it }

    @Test
    fun featureReadStatusGeneralizesTheExistingSpo2AndRealStepsProbes() {
        assertArrayEquals(OuraCommands.spo2ReadStatus().bytes, OuraCommands.featureReadStatus(OuraCommands.featureSpO2).bytes)
        assertArrayEquals(
            OuraCommands.realStepsReadStatus().bytes,
            OuraCommands.featureReadStatus(OuraCommands.featureRealSteps).bytes,
        )
    }

    @Test
    fun setFeatureModeBytesExactForSpo2() {
        assertArrayEquals(intArrayOf(0x2F, 0x03, 0x22, 0x04, 0x01), OuraCommands.setFeatureMode(OuraCommands.featureSpO2, 0x01).bytes)
        assertArrayEquals(intArrayOf(0x2F, 0x03, 0x22, 0x04, 0x00), OuraCommands.setFeatureMode(OuraCommands.featureSpO2, 0x00).bytes)
    }

    @Test
    fun setFeatureModeSubOpIsTheEnableVerbNotTheReadVerb() {
        assertEquals(0x22, OuraCommands.setFeatureMode(OuraCommands.featureSpO2, 0x01).bytes[2])
        assertEquals(0x20, OuraCommands.featureReadStatus(OuraCommands.featureSpO2).bytes[2])
    }

    @Test
    fun decodesTheFeatureModeReply() {
        assertEquals(OuraFeatureModeReply(0x04, 0x02), OuraDecoders.decodeFeatureModeReply(intArrayOf(0x04, 0x02)))
        assertNull(OuraDecoders.decodeFeatureModeReply(intArrayOf(0x04)))
    }

    @Test
    fun nonDaytimeReplySurfacesItsVerdictWhenIdle() {
        val d = OuraDriver(ringGen = OuraRingGen.GEN4, authKey = key)
        assertEquals(
            OuraDriver.SecureRouting.FeatureModeReply(OuraFeatureModeReply(0x04, 0x00)),
            d.handleSecureFrame(OuraSecureFrame(0x23, intArrayOf(0x04, 0x00))),
        )
        // The resting-HR restore sent at connect answers the same way.
        assertEquals(
            OuraDriver.SecureRouting.FeatureModeReply(OuraFeatureModeReply(0x08, 0x00)),
            d.handleSecureFrame(OuraSecureFrame(0x23, intArrayOf(0x08, 0x00))),
        )
    }

    @Test
    fun daytimeHrReplyStillAdvancesTheTriplet() {
        val d = OuraDriver(ringGen = OuraRingGen.GEN4, authKey = key)
        assertEquals(OuraDriver.SecureRouting.EnableAck, d.handleSecureFrame(OuraSecureFrame(0x23, intArrayOf(0x02, 0x00))))
    }

    @Test
    fun anyReplyDuringLiveHrEnableStaysWithTheTriplet() {
        val d = OuraDriver(ringGen = OuraRingGen.GEN4, authKey = key)
        d.nextStep(OuraTransition.Ready)
        d.nextStep(OuraTransition.NonceReceived(IntArray(15) { it + 1 }))
        d.nextStep(OuraTransition.AuthCompleted(OuraAuthStatus.SUCCESS))
        assertEquals(OuraDriverPhase.EnablingLiveHR, d.phase)
        assertEquals(OuraDriver.SecureRouting.EnableAck, d.handleSecureFrame(OuraSecureFrame(0x23, intArrayOf(0x04, 0x00))))
    }

    @Test
    fun shortReplyFallsBackToEnableAck() {
        val d = OuraDriver(ringGen = OuraRingGen.GEN4, authKey = key)
        assertEquals(OuraDriver.SecureRouting.EnableAck, d.handleSecureFrame(OuraSecureFrame(0x23, intArrayOf(0x04))))
    }
}
