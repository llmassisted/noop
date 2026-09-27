package com.noop.data

import java.io.File
import org.json.JSONObject

/**
 * One-time import of the Oura 0x50 MET history that predates durable OURA_MET rows. Since #676 every
 * anchored 0x50 record was appended to the diagnostic sidecar `<filesDir>/diagnostics/oura-activity-*.jsonl`
 * (schema 1, [com.noop.oura.OuraActivityDumpLine]) but never stored; the ring trims its own copy once a drain
 * is acknowledged, so that sidecar is the only remaining record. Each line becomes the SAME row the live path
 * now writes ([OuraStreamMapping.EVENT_MET], identical canonical payload), so a record present in both lands
 * once: the event PK is (deviceId, ts, kind) with insert-or-ignore, and both paths stamp the record with the
 * same ring-time anchor. Tier-B estimate input only — never scored.
 */
object OuraMetBackfill {
    private const val DUMP_PREFIX = "oura-activity-"
    private const val INSERT_CHUNK = 2_000

    /** Parse one sidecar line into its row `(utc, payloadJSON)`, or null for a malformed / unknown-schema /
     *  empty record. Pure (JVM-testable); the payload is encoded by the live path's canonical encoder. */
    fun parseLine(line: String): Pair<Long, String>? {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (o.optInt("schema", -1) != 1) return null
        val utc = o.optLong("utc", -1L)
        if (utc <= 0L) return null
        val arr = o.optJSONArray("met") ?: return null
        if (arr.length() == 0) return null
        val metX10 = (0 until arr.length()).map { Math.round(arr.getDouble(it) * 10).toInt() }
        val payload = StreamPersistence.encodePayload(
            linkedMapOf(
                "state" to o.optInt("state", 0),
                "sec_per_sample" to o.optInt("secPerSample", OuraStreamMapping.MET_SECONDS_PER_SAMPLE),
                "met_x10" to metX10,
            ),
        )
        return utc to payload
    }

    /** The sidecar files to import: every per-device corpus plus its single rotated generation. */
    fun dumpFiles(filesDir: File): List<File> =
        File(filesDir, "diagnostics").listFiles()
            ?.filter { it.isFile && it.name.startsWith(DUMP_PREFIX) && (it.name.endsWith(".jsonl") || it.name.endsWith(".jsonl.1")) }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Import every sidecar record under [deviceId] (the active ring's stable id — the ring the Health card
     * reads). Returns the number of parsed records offered to the store (duplicates are ignored there).
     */
    suspend fun run(
        filesDir: File,
        deviceId: String,
        insert: suspend (List<EventRow>) -> Unit,
        log: (String) -> Unit,
    ): Int {
        var offered = 0
        var skipped = 0
        val batch = ArrayList<EventRow>(INSERT_CHUNK)
        for (file in dumpFiles(filesDir)) {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val parsed = parseLine(line)
                    if (parsed == null) {
                        skipped += 1
                        continue
                    }
                    batch.add(EventRow(deviceId, parsed.first, OuraStreamMapping.EVENT_MET, parsed.second))
                    offered += 1
                }
            }
            // Flush per file (and in chunks) so one corrupt file cannot hold the whole import in memory.
            for (chunk in batch.chunked(INSERT_CHUNK)) insert(chunk)
            batch.clear()
        }
        log("Oura: MET backfill imported $offered record(s) from the activity sidecar ($skipped unparseable) under $deviceId")
        return offered
    }
}
