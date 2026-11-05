package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the BingeCat session's sync is wired.
 *
 * BingeCat was the one integration that stayed device-local by spec, so signing
 * in on the TV left the phone signed out - on every profile. It now travels like
 * the Trakt and Simkl sessions do, and each of the four places it has to be
 * wired is a silent failure on its own: the blob (what travels), the push gate
 * (what must NOT travel), the applier (what a pulled session changes) and the
 * publisher in the repository (when a rotation reaches the other device).
 */
class BingeCatAuthSyncWiringContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private val payload by lazy { source(PAYLOAD) }
    private val sync by lazy { source(SUPABASE_SYNC) }
    private val repository by lazy { source(REPOSITORY) }
    private val store by lazy { source(STORE) }
    private val rules by lazy { source(RULES) }

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

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = text.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = text.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return squash(rest.substring(0, end))
    }

    @Test
    fun `the session has a payload row and carries all of it`() {
        assertTrue(
            "the row key is the store's own name",
            payload.contains("const val KEY_BINGECAT_AUTH = \"bingecat_auth\"")
        )
        assertTrue(
            "buildAll is the bulk push's list - a blob missing from it never leaves",
            payload.contains("KEY_BINGECAT_AUTH to buildBingeCatAuth(context)")
        )
        val build = functionBody(payload, "fun buildBingeCatAuth(context: Context)")
        listOf(
            "BingeCatTokenStore.KEY_ACCESS_TOKEN",
            "BingeCatTokenStore.KEY_REFRESH_TOKEN",
            "BingeCatTokenStore.KEY_EXPIRES_AT",
            "BingeCatTokenStore.KEY_SCOPE",
            "BingeCatAuthRules.SIGNED_OUT_FIELD"
        ).forEach { field ->
            assertTrue("$field must travel: $build", build.contains(field))
        }
        assertTrue(
            "the tombstone is a DELIBERATE disconnect, never just an empty store: $build",
            build.contains("val signedOut = session == null && store.isSignedOut()")
        )
    }

    @Test
    fun `a profile that never connected cannot blank the account's session`() {
        assertTrue(
            "the bulk push runs on every sign-in and Sync now, so the gate is the " +
                "whole protection",
            squash(sync).contains(
                "if (key == PrefsPayloadBuilder.KEY_BINGECAT_AUTH && " +
                    "!PrefsPayloadBuilder.hasBingeCatAuthToPublish(context)"
            ) &&
                squash(sync).contains("return@forEach")
        )
        val gate = functionBody(payload, "fun hasBingeCatAuthToPublish(context: Context)")
        assertTrue(
            "and it asks the rule with both halves: $gate",
            gate.contains("BingeCatAuthRules.shouldPublish(store.accessToken(), store.isSignedOut())")
        )
    }

    @Test
    fun `a pulled session is adopted, a tombstone signs this device out`() {
        assertTrue(
            "applyForProfile dispatches by key - an unhandled key is a blob that " +
                "syncs up and never down",
            squash(payload).contains(
                "PrefsPayloadBuilder.KEY_BINGECAT_AUTH -> " +
                    "applyBingeCatAuth(context, payload, profileId, live)"
            )
        )
        val apply = functionBody(payload, "private fun applyBingeCatAuth(")
        assertTrue(
            "the stale-overwrite rule: $apply",
            apply.contains("BingeCatAuthRules.shouldAdopt(")
        )
        assertTrue(
            "a tombstoned blank clears the local session: $apply",
            apply.contains("store.markSignedOut()")
        )
        assertTrue(
            "an adopted session is written back encrypted, with its refresh token: $apply",
            apply.contains("store.save( BingeCatToken(") ||
                apply.contains("store.save(")
        )
        assertTrue(
            "same session: retire the marker rather than republishing it: $apply",
            apply.contains("store.clearSignedOutMarker()")
        )
        assertTrue(
            "and the on-screen profile drops its cached snapshot: $apply",
            apply.contains("resetBingeCatStateAfterSessionChange(context)")
        )
        assertTrue(
            "which clears the snapshot through the repository",
            squash(payload).contains("BingeCatRepository .getInstance(context) .onSyncedSessionChanged()") ||
                squash(payload).contains("BingeCatRepository.getInstance(context).onSyncedSessionChanged()")
        )
    }

    @Test
    fun `a rotation reaches the other device without waiting for a full sync`() {
        val publish = functionBody(
            repository,
            "private fun publishAuthToSync()"
        )
        assertTrue(
            "a tombstone has no token to trigger the ordinary gate, and a rotated " +
                "refresh token is stale on the sibling: $publish",
            publish.contains("PrefsPayloadBuilder.KEY_BINGECAT_AUTH") &&
                publish.contains("PrefsPayloadBuilder.buildBingeCatAuth(appContext)")
        )
        val connect = functionBody(repository, "suspend fun pollOnce(")
        assertTrue("connect publishes the new session: $connect", connect.contains("publishAuthToSync()"))
        assertTrue(
            "a refresh rotates the pair, so it publishes too",
            squash(repository).contains(
                "publishAuthToSync() token.accessToken"
            )
        )
        val disconnect = functionBody(repository, "suspend fun disconnect()")
        assertTrue(
            "a deliberate disconnect leaves the tombstone: $disconnect",
            disconnect.contains("store.markSignedOut()")
        )
        assertTrue(
            "and publishes it only if the tombstone actually took: $disconnect",
            disconnect.contains("if (cleared) publishAuthToSync()")
        )
        assertFalse(
            "a bare clear is the 401 path - it must not look like a disconnect",
            disconnect.contains("store.clear()")
        )
    }

    @Test
    fun `the store can address a sibling profile and retires its own tombstone`() {
        assertTrue(
            "the applier writes another profile's session, not the active one's",
            store.contains("ProfileStorage.prefsName(it, PREFS_NAME)")
        )
        assertTrue(
            "a saved session must never be published beside a sign-out marker",
            functionBody(store, "fun save(token: BingeCatToken)").contains("putBoolean(KEY_SIGNED_OUT, false)")
        )
        assertTrue(
            "the tombstone is only written by a deliberate disconnect",
            functionBody(store, "fun markSignedOut(): Boolean")
                .contains("putBoolean(KEY_SIGNED_OUT, true)")
        )
        assertTrue(
            "and the rules live beside the Simkl and Trakt ones",
            rules.contains("internal object BingeCatAuthRules")
        )
    }

    private companion object {
        const val PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val SUPABASE_SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt"
        const val STORE = "com/kennyb1201/kbstream/data/bingecat/BingeCatTokenStore.kt"
        const val RULES = "com/kennyb1201/kbstream/data/sync/SyncRules.kt"
    }
}
