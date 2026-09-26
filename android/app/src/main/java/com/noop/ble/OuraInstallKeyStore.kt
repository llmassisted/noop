package com.noop.ble

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.noop.data.SecurePrefs

/**
 * Secure, at-rest-encrypted storage for an Oura ring's 16-byte application install key.
 *
 * Backed by Jetpack Security `EncryptedSharedPreferences` - values are encrypted with a key held in
 * the Android Keystore (hardware-backed where available), so the install key is never written to disk
 * in the clear. This is the Android counterpart to storing the key in the macOS Keychain, and the same
 * pattern the AI Coach key uses ([com.noop.ai.AiKeyStore]).
 *
 * The install key is the 16-byte application-layer secret the ring's challenge handshake authenticates
 * against (docs/OURA_PROTOCOL.md s3). It is injected into [OuraLiveSource] via its `authKey` closure;
 * this store NEVER hardcodes a key and only ever holds a key the app provisioned. When no key is stored
 * for a ring, [load] returns null and [OuraLiveSource] drives its honest needs-pairing path (no faked
 * data) rather than authenticating.
 *
 * Keys are stored per ring (keyed by the registry device id), so a future second ring keeps its own.
 */
object OuraInstallKeyStore {

    private const val FILE_NAME = "noop_oura_secure_prefs"
    private const val KEY_PREFIX = "install_key_"
    private const val SAVED_AT_PREFIX = "install_key_saved_at_"

    /** Prefix for the one-shot adopt-intent marker (see [setPendingAdopt]). Kept in the SAME encrypted
     *  file as the key so the two move together when a ring is forgotten. */
    private const val ADOPT_PREFIX = "adopt_intent_"

    /** The exact byte length of an Oura application install key (s3). A stored value of any other length
     *  is treated as absent so a corrupt entry can never be sent as a malformed proof input. */
    const val KEY_LENGTH = 16

    /** Per-ring preference key. */
    private fun prefKey(deviceId: String) = "$KEY_PREFIX$deviceId"

    private fun savedAtKey(deviceId: String) = "$SAVED_AT_PREFIX$deviceId"

    /** Per-ring adopt-intent marker key. */
    private fun adoptKey(deviceId: String) = "$ADOPT_PREFIX$deviceId"

    /**
     * The encrypted preferences file. The master key uses the AES256_GCM key scheme and lives in the
     * Android Keystore (mirrors [com.noop.ai.AiKeyStore]).
     *
     * Delegated to [SecurePrefs] so both credential stores open their file the same way — and so the
     * Keystore and Tink setup happens once per process rather than on every read and write, which is
     * what it used to do.
     */
    private fun prefs(ctx: Context): SharedPreferences = SecurePrefs.of(ctx, FILE_NAME)

    /**
     * Persist the 16-byte install [key] (encrypted at rest) for [deviceId]. The key is supplied as
     * unsigned bytes 0..255 (the shape the protocol package uses). A wrong-length key is rejected
     * (returns false) so only a valid key is ever stored.
     */
    fun save(ctx: Context, deviceId: String, key: IntArray): Boolean {
        if (key.size != KEY_LENGTH) return false
        if (key.any { it !in 0..255 }) return false
        val bytes = ByteArray(KEY_LENGTH) { key[it].toByte() }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        prefs(ctx).edit()
            .putString(prefKey(deviceId), encoded)
            .putLong(savedAtKey(deviceId), System.currentTimeMillis())
            .apply()
        return true
    }

    /**
     * Read the stored 16-byte install key for [deviceId] as unsigned bytes 0..255, or null when none is
     * stored (or a stored value is the wrong length / unreadable). null is the honest signal that drives
     * [OuraLiveSource]'s needs-pairing path. The returned closure-friendly shape matches
     * `OuraLiveSource.authKey` and `OuraDriver`'s key parameter exactly.
     */
    fun load(ctx: Context, deviceId: String): IntArray? {
        val encoded = runCatching { prefs(ctx).getString(prefKey(deviceId), null) }.getOrNull() ?: return null
        return decode(encoded)
    }

    private fun decode(encoded: String): IntArray? {
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (bytes.size != KEY_LENGTH) return null
        return IntArray(KEY_LENGTH) { bytes[it].toInt() and 0xFF }
    }

    /**
     * Return distinct stored keys, trying [preferredDeviceIds] first. The remaining entries are recovery
     * candidates from older address-keyed rows: Android rotates an Oura ring's BLE address, while releases
     * before the stable-serial migration stored the credential under `oura-<MAC>`.
     *
     * This never exposes key bytes to logs and never installs anything on the ring. It only lets the normal
     * challenge handshake test locally-stored candidates until one authenticates.
     */
    fun loadCandidates(ctx: Context, preferredDeviceIds: List<String>): List<IntArray> {
        val p = runCatching { prefs(ctx) }.getOrNull() ?: return emptyList()
        val ids = LinkedHashSet<String>()
        ids.addAll(preferredDeviceIds.filter { it.isNotBlank() })
        val remaining = runCatching { p.all.keys }
            .getOrDefault(emptySet())
            .filter { it.startsWith(KEY_PREFIX) && !it.startsWith(SAVED_AT_PREFIX) }
            .sortedByDescending { storedKey ->
                val storedDeviceId = storedKey.removePrefix(KEY_PREFIX)
                runCatching { p.getLong(savedAtKey(storedDeviceId), 0L) }.getOrDefault(0L)
            }
        ids.addAll(remaining)

        val out = ArrayList<IntArray>()
        for (idOrKey in ids) {
            val keyName = if (idOrKey.startsWith(KEY_PREFIX)) idOrKey else prefKey(idOrKey)
            val encoded = runCatching { p.getString(keyName, null) }.getOrNull() ?: continue
            val candidate = decode(encoded) ?: continue
            if (out.none { it.contentEquals(candidate) }) out.add(candidate)
        }
        return out
    }

    /** Copy a valid key to a stable id. Used when a reset-mode advertisement reveals the serial. */
    fun copy(ctx: Context, fromDeviceId: String, toDeviceId: String, overwrite: Boolean = true): Boolean {
        val key = load(ctx, fromDeviceId) ?: return false
        if (!overwrite && hasKey(ctx, toDeviceId)) return true
        return save(ctx, toDeviceId, key)
    }

    /** True when a valid-length install key is stored for [deviceId]. */
    fun hasKey(ctx: Context, deviceId: String): Boolean = load(ctx, deviceId) != null

    /** Remove the stored install key AND any adopt-intent marker for [deviceId] (e.g. on forget-device /
     *  re-pair), so a forgotten ring never carries a stale key or a stale "install my key" intent. */
    fun clear(ctx: Context, deviceId: String) {
        prefs(ctx).edit()
            .remove(prefKey(deviceId))
            .remove(savedAtKey(deviceId))
            .remove(adoptKey(deviceId))
            .apply()
    }

    // MARK: - Adopt-intent (one-shot, gates the DANGEROUS post-factory-reset key install)

    /**
     * Record that the user explicitly consented to ADOPT [deviceId] (the wizard's destructive
     * factory-reset-and-adopt path, after its irreversible-consent gate). This is the ONLY signal that
     * permits [OuraLiveSource] to send the dangerous `0x24` install opcode: the live source reads it via
     * [consumePendingAdopt] when it builds its [com.noop.oura.OuraDriver] and passes it straight to the
     * driver's `allowKeyInstall` gate (OURA_PROTOCOL.md s3.2). Default-absent means the Advanced-key and
     * every read-only connect NEVER provision a key.
     *
     * Stored alongside the per-ring install key (encrypted at rest); [pass true] to arm, false is the
     * same as never set (the Advanced path explicitly does NOT arm it).
     */
    fun setPendingAdopt(ctx: Context, deviceId: String, intent: Boolean) {
        if (intent) {
            prefs(ctx).edit().putBoolean(adoptKey(deviceId), true).apply()
        } else {
            prefs(ctx).edit().remove(adoptKey(deviceId)).apply()
        }
    }

    /**
     * Read AND clear the one-shot adopt-intent marker for [deviceId]: returns true exactly once after
     * [setPendingAdopt] armed it, then false on every later read. One-shot by design so a single
     * consent provisions ONE install attempt; a later read-only reconnect cannot re-fire the dangerous
     * `0x24` write. [OuraLiveSource] consumes it when constructing its driver.
     */
    fun consumePendingAdopt(ctx: Context, deviceId: String): Boolean {
        val p = prefs(ctx)
        val armed = p.getBoolean(adoptKey(deviceId), false)
        if (armed) p.edit().remove(adoptKey(deviceId)).apply()
        return armed
    }
}
