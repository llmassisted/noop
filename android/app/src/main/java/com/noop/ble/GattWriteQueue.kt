package com.noop.ble

/**
 * Tiny transport-independent FIFO used by BLE Write Without Response paths.
 *
 * Android permits only one GATT operation at a time. A successful API return means the stack accepted
 * the operation, not that the peripheral replied, so callers pace [accepted] items before peeking again.
 */
internal class GattWriteQueue<T>(private val maxRejects: Int = 8) {
    enum class Rejection { Retry, Dropped }

    private val items = ArrayDeque<T>()
    private var rejectsForHead = 0

    val size: Int get() = items.size

    fun add(item: T) {
        items.addLast(item)
    }

    fun peek(): T? = items.firstOrNull()

    fun accepted(): T? {
        rejectsForHead = 0
        return if (items.isEmpty()) null else items.removeFirst()
    }

    fun rejected(): Rejection {
        if (items.isEmpty()) return Rejection.Dropped
        rejectsForHead += 1
        if (rejectsForHead <= maxRejects) return Rejection.Retry
        items.removeFirst()
        rejectsForHead = 0
        return Rejection.Dropped
    }

    fun clear() {
        items.clear()
        rejectsForHead = 0
    }
}

/** Pure monotonic pacing calculation, separated so the empty-FIFO edge stays regression-tested. */
internal object GattWritePacing {
    fun delayMs(nowMs: Long, nextAllowedAtMs: Long, requestedDelayMs: Long): Long =
        maxOf(requestedDelayMs, (nextAllowedAtMs - nowMs).coerceAtLeast(0L))
}
