package com.noop.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OuraKeyCandidatesTest {
    @Test
    fun `current address and stable serial precede newest legacy rows without duplicates`() {
        assertEquals(
            listOf("oura-current", "oura-serial", "oura-active", "oura-newest", "oura-older"),
            OuraKeyCandidates.preferredDeviceIds(
                addressDeviceId = "oura-current",
                serialDeviceId = "oura-serial",
                currentDeviceId = "oura-active",
                legacyIdsNewestFirst = listOf("oura-newest", "oura-active", "oura-older"),
            ),
        )
    }

    @Test
    fun `preferred order is preserved while duplicates and malformed keys are removed`() {
        val preferred = IntArray(16) { it }
        val older = IntArray(16) { 0xA0 + it }
        val normalized = OuraKeyCandidates.normalize(
            listOf(
                preferred,
                preferred.copyOf(),
                intArrayOf(1, 2, 3),
                IntArray(16) { 256 },
                older,
            ),
        )

        assertEquals(2, normalized.size)
        assertArrayEquals(preferred, normalized[0])
        assertArrayEquals(older, normalized[1])
    }

    @Test
    fun `normalized keys do not alias mutable preference results`() {
        val source = IntArray(16) { it }
        val normalized = OuraKeyCandidates.normalize(listOf(source))
        source[0] = 99
        assertEquals(0, normalized.single()[0])
    }
}
