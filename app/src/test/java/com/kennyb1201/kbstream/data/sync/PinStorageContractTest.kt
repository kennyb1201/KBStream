package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings L2: the PIN hashes are encrypted at rest, and nothing that follows can
 * strand a PIN.
 *
 * The preview engine cannot run Android's keystore-backed store, so this pins the
 * CONTRACT in the source the same way the other contract tests do: which store
 * each hash is written to, that the old plaintext location is drained rather than
 * abandoned, and that a failed vault write falls back instead of dropping the
 * credential. [PinVault]'s own reads and writes are covered on a device by
 * `ParentPinTest`.
 */
class PinStorageContractTest {

    private val manager = source("data/sync/ProfileManager.kt")
    private val guard = source("data/sync/KidsTimeGuard.kt")
    private val vault = source("data/security/PinVault.kt")

    // ── the store itself ──────────────────────────────────────────────────

    @Test
    fun `the vault is an encrypted store, not a fourth plaintext file`() {
        assertTrue(
            "it goes through SecureTokenStore, the same master key as the tokens",
            vault.contains("SecureTokenStore.prefs(context, STORE)")
        )
        assertFalse(
            "and never opens a plain SharedPreferences of its own",
            vault.contains("getSharedPreferences")
        )
        assertTrue(
            "both kinds of PIN are here: the parent's and each profile's",
            vault.contains("KEY_PARENT") && vault.contains("profileKey(")
        )
    }

    @Test
    fun `every write is read back before the caller may drop the only other copy`() {
        assertTrue(
            "the write commits and then compares what came back",
            vault.contains("editor.commit()") && vault.contains("stored == expect")
        )
        assertTrue(
            "and a mismatch is a reported failure, not a log line and a true",
            vault.contains("PIN hash write did not read back")
        )
    }

    // ── the parent PIN ────────────────────────────────────────────────────

    @Test
    fun `the parent hash is written to the vault and the old file is emptied`() {
        val set = block(guard, "fun setParentPin(")
        assertTrue("the vault is the store", set.contains("PinVault.setParentHash(context, hash)"))
        assertTrue(
            "the legacy key is removed only on a successful vault write",
            set.contains("if (PinVault.setParentHash(context, hash)) {") &&
                set.contains("remove(KEY_PARENT_PIN_HASH)")
        )
        assertTrue(
            "and a failed vault write still SETS the PIN, in the old place - a PIN " +
                "that silently did not happen is the worse fault",
            set.contains("legacy.edit().putString(KEY_PARENT_PIN_HASH, hash)")
        )
    }

    @Test
    fun `a pre-vault parent hash is adopted, and only then erased`() {
        val read = block(guard, "private fun parentPinHash()")
        assertTrue("the vault answers first", read.contains("PinVault.parentHash(context)"))
        assertTrue(
            "the legacy value is the fallback, so an upgrade cannot lock a parent out",
            read.contains("getString(KEY_PARENT_PIN_HASH, null)")
        )
        assertTrue(
            "and it is erased only after the vault write verified",
            read.contains("if (PinVault.setParentHash(context, legacy)) {") &&
                read.contains("remove(KEY_PARENT_PIN_HASH)")
        )
    }

    @Test
    fun `the hash upgrade rewrites the vault, and keeps the old value when it cannot`() {
        val verify = block(guard, "fun verifyParentPin(")
        assertTrue(
            "a legacy plain hash is upgraded in the vault",
            verify.contains("PinVault.setParentHash(context, upgraded)")
        )
        assertTrue(
            "and if that write fails the pin still answers where it did before",
            verify.contains("putString(KEY_PARENT_PIN_HASH, upgraded)")
        )
    }

    // ── profile PINs ──────────────────────────────────────────────────────

    @Test
    fun `profile hashes leave profiles_json for the vault`() {
        val save = block(manager, "private fun saveProfiles(")
        assertTrue(
            "each hash is written to the vault by profile id",
            save.contains(".setProfileHash(context, profile.id, profile.pinHash)")
        )
        assertTrue(
            "and the document that is written no longer carries it",
            save.contains("profile.copy(pinHash = null)") &&
                save.contains("ListSerializer(Profile.serializer()), stripped)")
        )
        assertTrue(
            "a vault write that did not read back keeps the hash instead of losing it",
            save.contains("return@map profile")
        )
    }

    @Test
    fun `a profile hash still in the document is adopted on read`() {
        val read = block(manager, "private fun readProfiles(")
        assertTrue(
            "the vault re-attaches what the document no longer holds",
            read.contains(".profileHash(context, normalized.id)")
        )
        assertTrue(
            "so callers keep seeing a Profile that behaves exactly as before",
            read.contains("normalized.copy(pinHash = vaulted)")
        )
        assertTrue(
            "and a pre-migration hash is moved out and the document rewritten",
            read.contains("if (stored) adopted = true") &&
                read.contains("saveProfiles(context, reattached, cloudRepair = true)")
        )
    }

    @Test
    fun `deleting a profile takes its hash with it`() {
        assertTrue(
            "the vault is keyed by id inside one store, so evictProfile cannot cover it",
            manager.contains("PinVault.clearProfileHash(context, profileId)")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        error("unbalanced: $declaration")
    }

    /**
     * Walks up for the source root rather than assuming the working directory:
     * Gradle's test task and the harness do not agree on it.
     */
    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }
}
