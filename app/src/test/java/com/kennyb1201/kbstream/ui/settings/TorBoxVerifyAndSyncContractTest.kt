package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the TorBox card promises after VERIFY, and what travels between devices.
 *
 * Two reports sit behind this: a key that read "Connected" until Settings was
 * closed and then read "Saved" again (the answer lived only in the row's own
 * state, which dies with the screen), and the ask that a TorBox key set up on
 * one TV arrive on the other together with the switches it is used with. The
 * first is fixed by remembering the answer against the key; the second is
 * already the account's - this pins both, because either one failing is silent.
 */
class TorBoxVerifyAndSyncContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The literal body of one `setOf(...)` declaration in [text]. */
    private fun setLiteral(
        text: String,
        header: String
    ): String = text.substringAfter(header).substringBefore("\n    )")

    private val prefs =
        source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt")
    private val payload = source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
    private val settings =
        source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
    private val fingerprint =
        source("com/kennyb1201/kbstream/data/debrid/TorBoxKeyFingerprint.kt")

    @Test
    fun `a verified key stays verified after the screen closes`() {
        assertTrue(
            "the answer needs somewhere to live past the row's composition",
            prefs.contains("KEY_TORBOX_VERIFIED_FOR") &&
                prefs.contains("fun markTorboxVerified(context: Context)") &&
                prefs.contains("fun clearTorboxVerification(context: Context)") &&
                prefs.contains("fun isTorboxVerified(context: Context): Boolean")
        )
        assertTrue(
            "and it is filed under the key it was made for, never the key itself",
            prefs.contains("torboxKeyFingerprint(key)") &&
                fingerprint.contains("fun torboxKeyFingerprint(key: String): String")
        )
        assertTrue(
            "the row seeds its state from that record",
            squash(settings).contains("if (AppPreferences.isTorboxVerified(context)) {")
        )
        assertTrue(
            "a live answer is written down when the key was accepted",
            squash(settings).contains("if (verdict.first != null) { AppPreferences.markTorboxVerified(context)")
        )
        assertTrue(
            "and a rejection forgets the old one, or the row would claim a verdict " +
                "that no longer holds",
            squash(settings).contains("} else { AppPreferences.clearTorboxVerification(context) }")
        )
        assertTrue(
            "the remembered answer reads as verified, not as a fresh probe",
            squash(settings).contains("torboxVerify?.first == TORBOX_VERIFIED_MARKER")
        )
    }

    @Test
    fun `the phone card remembers it too, since it is the same question`() {
        // Both surfaces read and write the same record, and share the marker,
        // so the answer cannot read "verified" on one and "not verified yet"
        // on the other.
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")
        assertTrue(
            "the phone's TorBox card must seed from the record too",
            mobile.contains("alreadyVerified = AppPreferences.isTorboxVerified(context)")
        )
        assertTrue(
            "and write the verdict back",
            mobile.contains("AppPreferences.markTorboxVerified(context)") &&
                mobile.contains("AppPreferences.clearTorboxVerification(context)")
        )
        assertTrue(
            "with the shared marker rather than its own spelling",
            squash(mobile).contains("verifyResult?.first == TORBOX_VERIFIED_MARKER")
        )
    }

    @Test
    fun `re-saving the same key keeps its verification, a different one drops it`() {
        val setter = squash(prefs)
            .substringAfter("fun setTorboxApiKey(context: Context, key: String) {")
            .substringBefore("// ── TorBox key verification")
        assertTrue(
            "the field commits on every blur, so only a CHANGED key may clear the " +
                "record - otherwise tapping away loses it: $setter",
            setter.contains("if (normalized != previous) { clearTorboxVerification(context) }")
        )
        assertTrue(
            "the comparison has to be against what was stored, under a mutex-free read",
            setter.contains("val previous = storedApiKey(context, KEY_TORBOX_API_KEY)")
        )
    }

    @Test
    fun `the key and the switches it is used with travel between devices`() {
        val syncedDocs = setLiteral(
            payload,
            "internal val SYNCED_PREF_KEYS = setOf("
        )
        listOf("torbox_cached_only", "torbox_library_sync", "torbox_resolve_create")
            .forEach { key ->
                assertTrue("$key must sync", syncedDocs.contains("\"$key\""))
            }
        val syncedKeys = setLiteral(
            prefs,
            "val SYNCED_API_KEYS: List<String> = listOf("
        )
        assertTrue(
            "the key itself rides the credentials blob",
            syncedKeys.contains("KEY_TORBOX_API_KEY")
        )
        listOf(
            "fun setTorboxApiKey(" to "pushApiKeysBlob()",
            "fun setTorboxCachedOnly(" to "syncDisplayPrefsBlob(context)",
            "fun setTorboxLibrarySync(" to "syncDisplayPrefsBlob(context)"
        ).forEach { (header, publish) ->
            // Up to the next declaration, so the publish call has to be in THIS
            // setter rather than in the one after it.
            val body = prefs
                .substringAfter(header)
                .substringBefore("\n    fun ")
            assertTrue(
                "$header must publish its row, or the other TV keeps its own answer: $body",
                body.contains(publish)
            )
        }
        assertFalse(
            "a TorBox switch must not be excluded from sync as well as listed in it",
            setLiteral(payload, "internal val EXCLUDED_PREF_KEYS = setOf(")
                .contains("torbox_")
        )
    }
}
