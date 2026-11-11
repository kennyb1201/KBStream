package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting a profile purges its per-profile `sync_prefs` rows.
 *
 * m25's second half, which the tree left undone: history and watched rows were
 * tombstoned on delete, but every `p:<profileId>:*` prefs row stayed in the
 * account - including the profile's live Simkl, Trakt and BingeCat sessions. The
 * rows are inert (the pull filters by active profile and ids are never reused),
 * so this is token hygiene rather than a live-breaking fix; what it prevents is
 * a deleted - possibly kids - profile's access tokens sitting in the cloud for
 * ever.
 *
 * The mechanism lives inside [SupabaseSync], which needs a session, a network
 * and an Application, so it is pinned at the source, following
 * [DeferredDeleteWiringContractTest]. Every assertion below fails against the
 * 0.9.223 source.
 */
class DeletedProfilePrefsPurgeContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    /** The body of the function at [signature], up to the next 4-space member. */
    private fun functionBody(signature: String, source: String = readSource(SYNC)): String {
        val start = source.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = source.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = source.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /** Whitespace-insensitive haystack, so indentation is not the test. */
    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    // ── (a) signed in: tombstone exactly this profile's rows ───────────────

    @Test
    fun `the signed-in purge tombstones this profile's rows through the outbox`() {
        val body = functionBody("suspend fun clearPrefsForProfile(")
        assertTrue(
            "F1: the cloud rows are enumerated by pref_key",
            body.contains("pullRows(TABLE_PREFS, \"pref_key\")")
        )
        assertTrue(
            "F1: and filtered to THIS profile's rows, the pull filter's own rule",
            body.contains("storedKeyMatchesProfile(storedKey, pid)")
        )
        assertTrue(
            "F1: each row is replaced by its tombstone, keyed by the stored pref_key",
            body.contains("OutboxItem(") &&
                body.contains("TABLE_PREFS,") &&
                body.contains("\"pref_key\",") &&
                body.contains("PrefsRowTombstones.payloadFor(unscopedKey(storedKey), now)")
        )
        assertTrue(
            "F1: and written through the same durable profile-scoped call the history half uses, " +
                "which drops the queued uploads for those keys first",
            body.contains(
                "outbox.replaceProfileScoped(TABLE_PREFS, \"pref_key\", pid, tombstones)"
            )
        )
    }

    @Test
    fun `the profiles blob is never purged`() {
        val body = functionBody("suspend fun clearPrefsForProfile(")
        val enumerated = body.substringAfter("pullRows(TABLE_PREFS")
            .substringBefore("OutboxItem(")
        assertTrue(
            "F1: the account-wide profiles blob is not profile-scoped - purging it would wipe " +
                "the whole list, and the delete flow owns removing the id from it",
            squash(enumerated).contains(
                "filter { storedKey -> storedKey != PrefsPayloadBuilder.KEY_PROFILES }"
            )
        )
    }

    @Test
    fun `a failed enumeration still drops the queued uploads`() {
        val body = functionBody("suspend fun clearPrefsForProfile(")
        val enumerate = body.indexOf("pullRows(TABLE_PREFS")
        val fallback = body.indexOf("getOrDefault(emptyList())")
        val replace = body.indexOf("outbox.replaceProfileScoped(")
        assertTrue("F1: the enumeration is guarded", enumerate >= 0 && fallback > enumerate)
        assertTrue(
            "F1: and the replace runs OUTSIDE that guard - a queued upload left behind flushes " +
                "after the purge and puts the row straight back",
            replace > fallback
        )
    }

    @Test
    fun `the delete runs both halves of the purge`() {
        val body = functionBody("fun launchDeletedProfileRowPurge(")
        assertTrue(
            "F1: the history/watched half is unchanged",
            body.contains("clearWatchStateForProfile(profileId)")
        )
        assertTrue(
            "F1: and the prefs half runs with it",
            body.contains("clearPrefsForProfile(profileId)")
        )
        assertTrue(
            "F1: both off the caller's thread",
            body.contains("scope.launch")
        )
    }

    // ── (b) signed out: stage, then replay on sign-in ─────────────────────

    @Test
    fun `signed out the prefs tombstones are staged before the local wipe`() {
        val body = functionBody("fun launchDeletedProfileRowPurge(")
        val signedOut = body.indexOf("if (!isSignedIn()) {")
        val staging = body.indexOf("stageSignedOutPrefsClear(context, profileId)")
        val launch = body.indexOf("scope.launch")
        assertTrue("F1: the signed-out branch exists", signedOut >= 0)
        assertTrue(
            "F1: the staging reads the profile's LOCAL stores, so it has to run before the " +
                "caller wipes them - not from inside the coroutine below",
            staging > signedOut && staging < launch
        )
    }

    @Test
    fun `the staging files a tombstone per local key under the last account`() {
        val body = functionBody("fun stageSignedOutPrefsClear(")
        assertTrue(
            "F1: filed under the account that signed out, like every other deferred delete",
            body.contains("SyncDeferredDeletes.lastAccountId(context)")
        )
        assertTrue(
            "F1: the keys are this device's record of what the profile published",
            body.contains("PrefsPayloadBuilder.localPrefKeysForProfile(context, profileId)")
        )
        assertTrue(
            "F1: one staged prefs tombstone per key",
            body.contains("SyncDeferredDeletes.stage(") &&
                body.contains("table = TABLE_PREFS,") &&
                body.contains("keyColumn = \"pref_key\",") &&
                body.contains("key = scopedKey(key, profileId),") &&
                body.contains("payloadJson = PrefsRowTombstones.payloadFor(key, now).toString()")
        )
        assertTrue(
            "F1: and the replay is table-agnostic, so a staged prefs row comes back as a prefs " +
                "row - it re-enqueues the staged table, key column and key",
            squash(functionBody("private suspend fun replayDeferredDeletes("))
                .contains("table = item.table, keyColumn = item.keyColumn, key = item.key")
        )
    }

    @Test
    fun `the local enumeration is the builders' own reads, with the gap stated`() {
        val payloads = readSource(PAYLOADS)
        val body = functionBody("fun localPrefKeysForProfile(", payloads)
        assertTrue(
            "F1: the three sessions use the same publish rules the push uses",
            body.contains("SimklAuthRules.shouldPublish(") &&
                body.contains("TraktAuthRules.shouldPublish(") &&
                body.contains("BingeCatAuthRules.shouldPublish(")
        )
        assertTrue(
            "F1: and are read for the DELETED profile, not the active one",
            body.contains("simklAuthPrefs(context, profileId)") &&
                body.contains("traktAuthStore(context, profileId)") &&
                body.contains("bingecatAuthStore(context, profileId)")
        )
        val signature = payloads.indexOf("fun localPrefKeysForProfile(")
        val documented = payloads.substring(maxOf(0, signature - 3_000), signature)
        assertTrue(
            "F1: the one key it cannot prove is named as a limitation instead of guessed at",
            documented.contains("Deliberately NOT enumerated") &&
                documented.contains("SecureTokenStore")
        )
        assertFalse(
            "F1: the profiles blob is account-wide, so it is never one of the deleted profile's keys",
            body.contains("KEY_PROFILES")
        )
    }

    // ── (c) what a sibling's pull does with the tombstones ────────────────

    @Test
    fun `a retired row is refused by the applier, not applied as data`() {
        val payloads = readSource(PAYLOADS)
        val body = functionBody("suspend fun applyForProfile(", payloads)
        val retired = body.indexOf("if (PrefsRowTombstones.isRetired(payload)) return")
        val whenAt = body.indexOf("when (prefKey) {")
        assertTrue("F1: the applier recognises a retired row", retired >= 0)
        assertTrue(
            "F1: and refuses it before any store write, or a blank tombstone would be applied as " +
                "an empty value",
            retired < whenAt
        )
    }

    @Test
    fun `the auth tombstones are the builders' own disconnect blobs`() {
        val payloads = readSource(PAYLOADS)
        val body = functionBody("fun payloadFor(", payloads)
        assertTrue(
            "F1: Simkl's disconnect shape",
            body.contains("PrefsPayloadBuilder.KEY_SIMKL_AUTH ->") &&
                body.contains("put(SIMKL_ACCESS_TOKEN_KEY, \"\")") &&
                body.contains("put(SimklAuthRules.SIGNED_OUT_FIELD, true)")
        )
        assertTrue(
            "F1: Trakt's, tokens blanked and the slug cleared",
            body.contains("PrefsPayloadBuilder.KEY_TRAKT_AUTH ->") &&
                body.contains("put(TraktTokenStore.KEY_ACCESS_TOKEN, \"\")") &&
                body.contains("put(TraktTokenStore.KEY_REFRESH_TOKEN, \"\")") &&
                body.contains("put(TraktAuthRules.SIGNED_OUT_FIELD, true)")
        )
        assertTrue(
            "F1: BingeCat's",
            body.contains("PrefsPayloadBuilder.KEY_BINGECAT_AUTH ->") &&
                body.contains("put(BingeCatTokenStore.KEY_ACCESS_TOKEN, \"\")") &&
                body.contains("put(BingeCatAuthRules.SIGNED_OUT_FIELD, true)")
        )
        assertTrue(
            "F1: everything else is the retired marker, not a fabricated value",
            body.contains("put(RETIRED_FIELD, true)")
        )
        assertTrue(
            "F1: so the applier's existing handlers clear the tokens - the disconnect payload is " +
                "the same one a deliberate sign-out publishes",
            functionBody("private fun applySimklAuth(", payloads)
                .contains("SimklAuthRules.clearsSession(token, signedOut)")
        )
    }

    @Test
    fun `a missed tombstone still cannot leak into the profile on screen`() {
        val body = functionBody("private suspend fun pullPrefs(")
        val profiles = body.indexOf("PrefsPayloadBuilder.KEY_PROFILES")
        val filter = body.indexOf("if (!storedKeyMatchesProfile(storedKey, pid)) continue")
        assertTrue("F1: the profiles blob is still applied first", profiles >= 0)
        assertTrue(
            "F1: and an orphaned p:<deletedId>:* row is still dropped by the profile filter",
            filter > profiles
        )
    }

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val PAYLOADS = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
    }
}
