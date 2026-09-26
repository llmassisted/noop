package com.noop.ble

import com.noop.oura.OuraAuth

/** Pure validation/order gate for legacy address-keyed Oura credential recovery. */
internal object OuraKeyCandidates {
    fun preferredDeviceIds(
        addressDeviceId: String,
        serialDeviceId: String?,
        currentDeviceId: String,
        legacyIdsNewestFirst: List<String>,
    ): List<String> = LinkedHashSet<String>().apply {
        add(addressDeviceId)
        serialDeviceId?.let(::add)
        add(currentDeviceId)
        addAll(legacyIdsNewestFirst)
    }.toList()

    fun normalize(candidates: List<IntArray>): List<IntArray> = buildList {
        for (candidate in candidates) {
            if (candidate.size == OuraAuth.keyLength && candidate.all { it in 0..255 } &&
                none { it.contentEquals(candidate) }
            ) {
                add(candidate.copyOf())
            }
        }
    }
}
