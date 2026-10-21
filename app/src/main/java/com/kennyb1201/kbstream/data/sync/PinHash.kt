package com.kennyb1201.kbstream.data.sync

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The stored form of a 4-digit PIN (audit B2).
 *
 * v1 stored `SHA-256(pin)` — no salt, no work factor. Four digits is a 10,000
 * value space, so the stored hash WAS the PIN to anyone holding a copy of it,
 * and profile hashes travel inside the synced profiles blob (Supabase). One
 * precomputed table over every 4-digit PIN recovered every profile's PIN and
 * the parent PIN in a single pass.
 *
 * v2 is PBKDF2-HMAC-SHA256 with a per-hash random salt and [iterations]
 * rounds, stored as `v2$<iterations>$<salt-hex>$<digest-hex>`. The salt means
 * one table no longer covers every PIN on every account, and the iteration
 * count makes each guess cost real time.
 *
 * The iteration count is stored WITH the hash rather than read from this
 * constant, so raising it later keeps every existing hash verifiable (and
 * upgradeable) instead of locking people out.
 *
 * v1 hashes still verify. Nobody is locked out by the upgrade; the plain PIN
 * is only available at verify time, so that is where a v1 hash is rewritten.
 * [KidsTimeGuard] and [ProfileManager] both do that.
 */
object PinHash {

    /** Marks a v2 value; anything else is a legacy bare SHA-256 hex digest. */
    private const val PREFIX = "v2"

    private const val SEPARATOR = '$'

    /**
     * ~100 ms on a desktop, tens of milliseconds on a phone. Deliberately not
     * a server-grade count: verification runs on a TV remote press, and this
     * guards a household gate, not a bank. 100k rounds still turns a 10,000-PIN
     * sweep from instant into minutes per profile per salt.
     */
    private const val ITERATIONS = 100_000

    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    private val random = SecureRandom()

    /** A fresh v2 hash for [pin]. Salted per call, so equal PINs never collide. */
    fun create(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        return "$PREFIX$SEPARATOR$ITERATIONS$SEPARATOR" +
            "${salt.toHex()}$SEPARATOR${derive(pin, salt, ITERATIONS).toHex()}"
    }

    /**
     * Whether [pin] matches [stored]. False for a null/blank stored value,
     * which is the honest answer for "there is no PIN to check".
     */
    fun matches(stored: String?, pin: String): Boolean {
        val value = stored?.takeIf { it.isNotBlank() } ?: return false
        if (!value.startsWith("$PREFIX$SEPARATOR")) {
            // v1: unsalted SHA-256. Still accepted so an upgrade never locks
            // anyone out; the caller rewrites it (see [needsUpgrade]).
            return MessageDigest.isEqual(
                sha256(pin).toHex().toByteArray(Charsets.UTF_8),
                value.lowercase().toByteArray(Charsets.UTF_8)
            )
        }
        val parts = value.split(SEPARATOR)
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = parts[2].hexToBytes() ?: return false
        val expected = parts[3].hexToBytes() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    /** True when a stored PIN hash predates the salted format and should be rewritten. */
    fun needsUpgrade(stored: String?): Boolean =
        !stored.isNullOrBlank() && !stored.startsWith("$PREFIX$SEPARATOR")

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
