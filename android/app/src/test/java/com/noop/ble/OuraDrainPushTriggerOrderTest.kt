package com.noop.ble

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drain-end push trigger must not run ahead of the drain's own records (Android fork only).
 *
 * `maxStoredRingTime` advances when a record enters the in-memory buffer, which otherwise waits for its
 * 30-record / 30 s threshold before reaching [OuraLiveSource]'s `persist`. A drain that ended without
 * flushing it queued the self-hosted push while its last records were still only in memory, so the
 * receiver missed them until the next trigger.
 *
 * A BLE source has no unit-testable surface, so the ORDER is pinned against the source, the way
 * [OuraInterruptedDrainCursorTest] pins its own: `finishDrain` flushes before it fires the sink, and the
 * coordinator waits for every tracked write before forwarding it.
 */
class OuraDrainPushTriggerOrderTest {

    private fun repoRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(userDir, File(userDir, ".."), File(userDir, "../.."))
        return candidates.firstOrNull { File(it, "android/app/src/main/java/com/noop/ble/OuraLiveSource.kt").isFile }
            ?: error("could not locate the repo root from ${userDir.absolutePath}")
    }

    private fun source(name: String): String =
        File(repoRoot(), "android/app/src/main/java/com/noop/ble/$name").readText()

    /** The text of the function starting at [signature], up to the next member at the same indent. */
    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val end = Regex("\n    (private |internal |override |fun |val |var |/\\*\\*)").find(src, start + signature.length)
        return src.substring(start, end?.range?.first ?: src.length)
    }

    @Test
    fun `finishDrain flushes the record buffer before firing the drain sink`() {
        val finish = body(source("OuraLiveSource.kt"), "private fun finishDrain(")
        val flushAt = finish.indexOf("\n        flush()")
        val sinkAt = finish.indexOf("onDrainStoredData()")
        assertTrue("finishDrain must flush the record buffer", flushAt >= 0)
        assertTrue("finishDrain must fire onDrainStoredData", sinkAt >= 0)
        assertTrue("the buffer flush must come before onDrainStoredData", flushAt < sinkAt)
    }

    @Test
    fun `the coordinator awaits tracked Oura writes before forwarding the drain sink`() {
        val src = source("SourceCoordinator.kt")
        val wiring = src.substring(src.indexOf("onDrainStoredData = {"))
        val joinAt = wiring.indexOf("joinAll()")
        val sinkAt = wiring.indexOf("ouraDrainStoredSink()")
        assertTrue("onDrainStoredData must wait for in-flight writes", joinAt in 0 until sinkAt)
        assertTrue(
            "record writes must be tracked",
            src.contains("persist = { batch: StreamBatch, deviceId: String ->\n                trackOuraPersist("),
        )
        assertTrue(
            "hypnogram-night writes must be tracked",
            Regex("persistSleepSession = \\{[^\\n]*\\n(\\s*//[^\\n]*\\n)*\\s*trackOuraPersist\\(").containsMatchIn(src),
        )
    }
}
