package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring for account-scoped signed-out deletes, pinned at the source.
 *
 * The store and its rules are unit tested directly (see
 * [SyncDeferredDeletesTest]); the ordering that makes them correct lives inside
 * [SupabaseSync] and cannot be driven without a signed-in device, so it is
 * pinned here:
 *  - a signed-out delete is STAGED, not dropped;
 *  - the account is remembered so a later delete can be attributed, and that
 *    capture happens on sign-out BEFORE the session store is cleared;
 *  - the replay runs on the SAME account's sign-in and BEFORE the pull, so the
 *    pull cannot restore a row the replay deletes;
 *  - the pull honors a queued-but-unflushed tombstone instead of re-inserting.
 */
class DeferredDeleteWiringContractTest {

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

    /**
     * The body of the function starting at [signature], up to its closing brace
     * (a line indented by exactly four spaces). The body is taken from the
     * first `{` after the signature, so a declaration whose parameters span
     * several lines is handled too.
     */
    private fun functionBody(signature: String): String {
        val src = readSource(SYNC)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `a signed-out delete is staged, not dropped`() {
        val body = functionBody("fun deleteHistoryRows(")
        assertTrue(
            "the signed-out branch must stage the delete for its account",
            body.contains("stageSignedOutDelete(ids, profileId)")
        )
        val signedOut = body.indexOf("if (!isSignedIn()) {")
        val staging = body.indexOf("stageSignedOutDelete(ids, profileId)")
        assertTrue("staging must be the signed-out branch", signedOut in 0 until staging)
    }

    @Test
    fun `the signing-in account replays its staged deletes before the pull`() {
        val cases = listOf(
            "fun signIn(" to "pullAllNow(context)",
            "private fun restoreSession(" to "pullAll(context)"
        )
        cases.forEach { (signature, pull) ->
            val body = functionBody(signature)
            assertTrue(
                "$signature must remember the signing-in account",
                body.contains("rememberDeferredDeleteAccount(")
            )
            val replay = body.indexOf("replayDeferredDeletes(")
            val pullAt = body.indexOf(pull)
            assertTrue("$signature must replay deferred deletes", replay >= 0)
            assertTrue("$signature must replay BEFORE it pulls", pullAt in (replay + 1) until body.length)
        }
    }

    @Test
    fun `sign out captures the account before the session store is cleared`() {
        val body = functionBody("fun signOut(")
        val capture = body.indexOf("rememberDeferredDeleteAccount(context)")
        val clear = body.indexOf("outbox.clear()")
        assertTrue("sign-out must capture the account id", capture >= 0)
        assertTrue("the capture must precede the session-store clear", clear in (capture + 1) until body.length)
    }

    @Test
    fun `the pull honors a queued tombstone instead of re-inserting the row`() {
        val body = functionBody("private suspend fun pullHistory(")
        assertTrue(
            "the pull must consult the pending outbox for the row",
            body.contains("outbox.pending(TABLE_HISTORY, \"item_id\", storedId)")
        )
        assertTrue(
            "only a tombstone (not a pending live upload) suppresses the row",
            body.contains("HistoryTombstoneRules.isTombstone(it.payload)")
        )
        assertTrue(
            "a queued delete must tell the pull to remove the local row",
            body.contains("pendingDeletes.add(id)")
        )
    }

    @Test
    fun `the account id lives in a store of its own`() {
        val store = readSource(SOURCE_STORE)
        assertTrue(
            "the staged deletes must not share the session store that sign-out clears",
            store.contains("kbstream_sync_deferred_deletes")
        )
        assertTrue(store.contains("last_account_id"))
    }

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val SOURCE_STORE = "com/kennyb1201/kbstream/data/sync/SyncDeferredDeletes.kt"
    }
}
