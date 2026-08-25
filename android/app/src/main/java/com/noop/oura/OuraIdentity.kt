package com.noop.oura

/** Stable identity helpers for Oura advertisements and product-info replies. */
object OuraIdentity {
    /**
     * Extract the stable serial from reset-mode advertisements such as
     * `Oura 4016082535033542`. Marketing names (`Oura Ring 4`, `Oura Horizon`, …) are not serials.
     */
    fun advertisedSerial(name: String?): String? {
        val value = name?.trim().orEmpty()
        if (!value.startsWith("Oura ", ignoreCase = true)) return null
        val suffix = value.substringAfter(' ').trim()
        return suffix.takeIf { isPlausibleSerial(it) && it.any(Char::isDigit) }
    }

    /** Guard against a malformed frame or a marketing name minting a bogus registry id. */
    fun isPlausibleSerial(value: String): Boolean =
        value.length in 8..24 && value.all { it.isLetterOrDigit() }

    /**
     * Whether an advertisement may belong to the expected stable serial.
     *
     * A reset ring advertises `Oura <serial>`, but after setup the same ring can advertise only
     * `Oura Ring 4`. A missing advertised serial is therefore UNKNOWN, not a mismatch: the authenticated
     * challenge is what verifies the ring. An explicitly different serial remains a hard rejection.
     */
    fun mayMatchExpectedSerial(expectedSerial: String?, advertisedSerial: String?): Boolean = when {
        expectedSerial == null -> true
        advertisedSerial == null -> true
        else -> expectedSerial.equals(advertisedSerial, ignoreCase = true)
    }
}
