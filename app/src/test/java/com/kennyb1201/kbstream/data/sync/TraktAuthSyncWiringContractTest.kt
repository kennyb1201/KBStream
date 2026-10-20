package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Trakt session rides the account's sync, the way the Simkl session does.
 *
 * A login is the one thing a viewer should never have to do twice per account,
 * so the session travels as its own `trakt_auth` blob. Read from the sources
 * because the alternative is two devices and a cloud account: this pins the
 * publish gate (a profile that never connected Trakt must not erase the
 * account's session), the full session payload (a refresh token and expiry
 * travel too, or the sibling is stranded when the token expires), and the
 * side effects of adopting one (the old account's cached watched state must
 * not answer the next badge read).
 */
class TraktAuthSyncWiringContractTest {

    private val syncPrefs = source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
    private val supabase = source("com/kennyb1201/kbstream/data/sync/SupabaseSync.kt")
    private val repository = source("com/kennyb1201/kbstream/data/trakt/TraktRepository.kt")
    private val store = source("com/kennyb1201/kbstream/data/trakt/TraktTokenStore.kt")

    @Test
    fun `the Trakt session is a synced blob on the Simkl rule`() {
        assertTrue(
            "its own row, so the token never rides a display-prefs blob",
            syncPrefs.contains("const val KEY_TRAKT_AUTH = \"trakt_auth\"")
        )
        assertTrue(
            "and it is built with every push",
            syncPrefs.contains("KEY_TRAKT_AUTH to buildTraktAuth(context)")
        )
        assertTrue(
            "a profile with no session and no disconnect says nothing",
            syncPrefs.contains("fun hasTraktAuthToPublish(context: Context): Boolean") &&
                syncPrefs.contains(
                    "TraktAuthRules.shouldPublish(store.accessToken(), store.isSignedOut())"
                )
        )
        assertTrue(
            "and the bulk push honours that gate, exactly as it does for Simkl",
            supabase.contains("key == PrefsPayloadBuilder.KEY_TRAKT_AUTH") &&
                supabase.contains("!PrefsPayloadBuilder.hasTraktAuthToPublish(context)")
        )
    }

    @Test
    fun `the blob carries the whole session, not just the access token`() {
        val build = syncPrefs.substringAfter("fun buildTraktAuth(context: Context): JsonObject {")
            .substringBefore("fun buildApiKeys(")
        listOf(
            "TraktTokenStore.KEY_ACCESS_TOKEN",
            "TraktTokenStore.KEY_REFRESH_TOKEN",
            "TraktTokenStore.KEY_EXPIRES_AT",
            "TraktTokenStore.KEY_ACCOUNT_SLUG",
            "TraktAuthRules.SIGNED_OUT_FIELD"
        ).forEach { field ->
            assertTrue(
                "a session without $field is not adoptable: Trakt rotates its " +
                    "single-use refresh token and expires its access token",
                build.contains(field)
            )
        }
        assertTrue(
            "a blank blob is only a sign-out when the profile deliberately disconnected",
            build.contains("val signedOut = session == null && store.isSignedOut()")
        )
    }

    @Test
    fun `adopting a session is guarded, profile-scoped and resets the old account`() {
        val apply = syncPrefs.substringAfter("private fun applyTraktAuth(")
            .substringBefore("private fun resetTraktStateAfterSessionChange(")
        assertTrue(
            "the cloud applier must write the ROW's profile, not the active one",
            apply.contains("traktAuthStore(context, profileId)")
        )
        assertTrue(
            "a blank clears only with the tombstone",
            apply.contains("if (!TraktAuthRules.clearsSession(access, signedOut)) return")
        )
        assertTrue(
            "it marks the tombstone through the store, not by dropping the file",
            apply.contains("store.markSignedOut()")
        )
        assertTrue(
            "and a stale remote session is refused before it overwrites a live one",
            apply.contains("TraktAuthRules.shouldAdopt(")
        )
        assertTrue(
            "the adopted pair is written in full",
            apply.contains("TraktToken(") &&
                apply.contains("refreshToken = nextRefresh")
        )
        assertTrue(
            "the old account's watched snapshot must not answer the next read, " +
                "while local history and markers stay the viewer's",
            apply.contains("resetTraktStateAfterSessionChange(context)") &&
                syncPrefs.contains(".onSyncedSessionChanged()") &&
                syncPrefs.contains("WatchedStatusRepository.invalidateAllCaches()")
        )
        assertTrue(
            "a sibling profile's session change must not clear the on-screen profile",
            apply.contains("if (live) resetTraktStateAfterSessionChange(context)")
        )
    }

    @Test
    fun `connect, refresh and disconnect all publish the session`() {
        assertTrue(
            "a fresh connect shares itself without waiting for a full sync",
            repository.contains("store.save(token)\n        // Cross-device sync")
        )
        assertTrue(
            "a refresh rotates the single-use refresh token, so it republishes",
            repository.contains("// A refresh ROTATES the single-use refresh token")
        )
        assertEquals(
            "connect, refresh and disconnect are the three call points plus the " +
                "declaration",
            4,
            Regex("publishAuthToSync\\(\\)").findAll(repository).count()
        )
        assertTrue(
            "a disconnect publishes a tombstone, which is the only thing that " +
                "may end the session on another device",
            repository.contains("store.markSignedOut()") &&
                repository.contains("publishAuthToSync()")
        )
        assertFalse(
            "a bare clear leaves no tombstone, so the other device cannot tell " +
                "it from a profile that never connected",
            repository.contains("store.clear()")
        )
    }

    @Test
    fun `the token store and the blob spell the fields once`() {
        listOf(
            "KEY_ACCESS_TOKEN = \"access_token\"",
            "KEY_REFRESH_TOKEN = \"refresh_token\"",
            "KEY_EXPIRES_AT = \"expires_at_ms\"",
            "KEY_ACCOUNT_SLUG = \"account_slug\"",
            "KEY_SIGNED_OUT = \"signed_out\""
        ).forEach { field ->
            assertTrue("TraktTokenStore must spell $field", store.contains(field))
        }
        assertTrue(
            "and the sync blob must read them from there rather than re-typing " +
                "the literals, so a rename cannot silently stop syncing",
            !syncPrefs.contains("\"refresh_token\"") &&
                !syncPrefs.contains("\"expires_at_ms\"")
        )
    }

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findMainSourceRoot(): File {
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
}
