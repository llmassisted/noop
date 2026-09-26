package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GattWriteQueueTest {
    @Test
    fun `commands remain FIFO until Android accepts each head`() {
        val queue = GattWriteQueue<String>()
        queue.add("notify_all")
        queue.add("get_nonce")

        assertEquals("notify_all", queue.peek())
        assertEquals("notify_all", queue.accepted())
        assertEquals("get_nonce", queue.peek())
        assertEquals("get_nonce", queue.accepted())
        assertNull(queue.peek())
    }

    @Test
    fun `busy head is retried without allowing the next command past it`() {
        val queue = GattWriteQueue<String>(maxRejects = 2)
        queue.add("notify_all")
        queue.add("get_nonce")

        assertEquals(GattWriteQueue.Rejection.Retry, queue.rejected())
        assertEquals("notify_all", queue.peek())
        assertEquals(GattWriteQueue.Rejection.Retry, queue.rejected())
        assertEquals("notify_all", queue.peek())
        assertEquals(GattWriteQueue.Rejection.Dropped, queue.rejected())
        assertEquals("get_nonce", queue.peek())
    }

    @Test
    fun `clear removes queued commands and rejection state`() {
        val queue = GattWriteQueue<String>(maxRejects = 1)
        queue.add("get_nonce")
        assertEquals(GattWriteQueue.Rejection.Retry, queue.rejected())
        queue.clear()
        assertEquals(0, queue.size)
        assertNull(queue.peek())
    }

    @Test
    fun `pacing survives a momentarily empty queue`() {
        // Command A was accepted at t=1,000 and the FIFO became empty. Command B arrives on the handler
        // 5 ms later: it must still wait the remaining 70 ms rather than slipping through immediately.
        assertEquals(70L, GattWritePacing.delayMs(nowMs = 1_005L, nextAllowedAtMs = 1_075L, requestedDelayMs = 0L))
        assertEquals(0L, GattWritePacing.delayMs(nowMs = 1_075L, nextAllowedAtMs = 1_075L, requestedDelayMs = 0L))
        assertEquals(50L, GattWritePacing.delayMs(nowMs = 2_000L, nextAllowedAtMs = 0L, requestedDelayMs = 50L))
    }
}
