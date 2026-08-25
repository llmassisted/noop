package com.noop.ui

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveReadDeviceIdsTest {
    @Test
    fun addressToSerialAdoptionRebindsOnceAndNullUsesStartupFallback() = runBlocking {
        val ids = activeReadDeviceIds(
            activeIds = flowOf(null, "oura-address", "oura-address", "oura-serial"),
            fallback = "my-whoop",
        ).toList()

        assertEquals(listOf("my-whoop", "oura-address", "oura-serial"), ids)
    }
}
