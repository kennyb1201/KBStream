package com.kennyb1201.kbstream.data.mdblist

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watched-snapshot retry storm: a spent MDBList budget used to be read as
 * a transient failure, so `getWatchedSnapshot` cached nothing, the TTL never
 * engaged, and every caller re-attempted a download the local budget
 * interceptor was going to refuse anyway (the field log caught ~40 attempts in
 * 30 s, each logging `sync/watched failed code=429`).
 *
 * The rules that separate "the day's allowance is gone" from "the request
 * failed" are tested directly here; the wiring that has to use them — the
 * short-circuit ahead of the disk and network paths, the serve-stale branch,
 * and the write-back gate it deliberately returns before — is pinned on the
 * source, because the client needs an Android context and a live OkHttp stack
 * to run. The behavioral halves that a plain JUnit test cannot drive (the
 * actual "no attempt is made" and "the attempt happens again after the TTL")
 * are asserted as the ordered placement of those branches: the short-circuit
 * is the first thing after the key check and the download is the only path
 * past it.
 */
class MdbListBudgetBlockTest {

    // ── spec: the synthetic 429 is told apart from a real one ───────────

    @Test
    fun `only the interceptor's own 429 is a budget block`() {
        assertTrue(isLocalBudgetBlock(429, MDBLIST_LOCAL_BUDGET_MESSAGE))
    }

    @Test
    fun `a real 429 keeps the transient path`() {
        // The API's own refusals: a daily-limit body, an hourly one, an empty
        // body. None of them is the local block, and all of them may be
        // retried later, exactly as before.
        assertFalse(isLocalBudgetBlock(429, "Daily API limit reached"))
        assertFalse(isLocalBudgetBlock(429, "Too Many Requests"))
        assertFalse(isLocalBudgetBlock(429, ""))
        assertFalse(isLocalBudgetBlock(429, null))
    }

    @Test
    fun `the block is a 429, not a message anywhere in a status`() {
        // A 200 is not a failure branch at all, and a 403/500 carrying the
        // text is not this interceptor's answer.
        assertFalse(isLocalBudgetBlock(200, MDBLIST_LOCAL_BUDGET_MESSAGE))
        assertFalse(isLocalBudgetBlock(403, MDBLIST_LOCAL_BUDGET_MESSAGE))
        assertFalse(isLocalBudgetBlock(500, MDBLIST_LOCAL_BUDGET_MESSAGE))
    }

    // ── spec: the back-off expires, so the next day is attempted ────────

    @Test
    fun `a stamp holds callers off for its window and no longer`() {
        val ttl = 20 * 60 * 1000L
        val blockedAt = 1_000_000L

        assertTrue("a call right after the block", isBudgetBlockActive(blockedAt, blockedAt, ttl))
        assertTrue(
            "a call one millisecond inside the window",
            isBudgetBlockActive(blockedAt, blockedAt + ttl - 1, ttl)
        )
        assertFalse(
            "the window has run out - the next call must attempt a real download",
            isBudgetBlockActive(blockedAt, blockedAt + ttl, ttl)
        )
        assertFalse(
            "long past the window",
            isBudgetBlockActive(blockedAt, blockedAt + ttl + 1, ttl)
        )
    }

    @Test
    fun `an absent stamp is never a fresh block`() {
        // `now - 0` is the epoch time itself, so a device whose clock has not
        // been set yet would read "never blocked" as a block and answer every
        // call from an empty cache for the first 20 minutes of its uptime.
        assertFalse(isBudgetBlockActive(0L, 0L, 20 * 60 * 1000L))
        assertFalse(isBudgetBlockActive(0L, 60_000L, 20 * 60 * 1000L))
    }

    // ── spec: stale is served, and nothing is written back ──────────────

    @Test
    fun `the synthetic 429 is detected in the download loop`() {
        val body = snapshotBody()

        assertTrue(
            "the failure branch must tell the local block from a real failure",
            body.contains("if (isLocalBudgetBlock(response.code, response.message)) {")
        )
        assertEquals(
            "one detection site: a second, looser check could take the new " +
                "path for a real 429",
            1,
            body.split("isLocalBudgetBlock(response.code, response.message)").size - 1
        )
        assertTrue(
            "a real 429 must keep its own path, logged and transient",
            body.indexOf("isLocalBudgetBlock(response.code, response.message)") <
                body.indexOf("sync/watched failed code=")
        )
    }

    @Test
    fun `the block serves what is in hand and stamps the time`() {
        val block = blockBody()

        assertTrue(
            "the answer is built after the download unwinds, so the " +
                "write-back below cannot run for it",
            snapshotBody().contains("var blockedByLocalBudget = false")
        )
        assertTrue("the stamp is the moment of the block", block.contains("budgetBlockedAt = now"))
        assertTrue(
            "the stamp is per key, or one profile's spent day would hold " +
                "another profile's key off",
            block.contains("budgetBlockedKey = apiKey")
        )
        assertTrue(
            "stale is served only from the caller's own snapshot",
            block.contains("val stale = cachedSnapshot?.takeIf { cachedSnapshotKey == apiKey }")
        )
        assertTrue(
            "and its timestamp is refreshed, so the TTL suppresses the next " +
                "attempts too",
            block.contains("if (stale != null) cachedSnapshotAt = now")
        )
        assertTrue(
            "no snapshot: the caller reads empty rather than retrying",
            block.contains("return@withLock stale ?: MdbListWatchedSnapshot()")
        )
    }

    @Test
    fun `the block path never writes the snapshot back`() {
        val block = blockBody()

        assertTrue(
            "the block returns before the write-back gate",
            block.contains("return@withLock stale")
        )
        assertFalse(
            "the budget path only stamps the timestamp - writing the stale " +
                "blob back would refresh its disk copy for the 6 h disk TTL " +
                "and the next call after the in-memory TTL would never " +
                "attempt the download",
            block.contains("cachedSnapshot = ")
        )
    }

    @Test
    fun `the transient failure path is untouched`() {
        val body = snapshotBody()

        assertTrue(
            "a failed request still logs and returns empty, so nothing is " +
                "cached and the caller may retry",
            body.contains(
                "Log.w( TAG, \"sync/watched failed code=\${response.code} \" + " +
                    "response.body?.string().orEmpty().take(200) )"
            )
        )
        assertTrue(
            "and it still returns an empty snapshot",
            body.contains("return@withContext MdbListWatchedSnapshot()")
        )
        assertTrue(
            "the empty-result cache guard is exactly as it was",
            body.contains("if (!result.isEmpty && generationAtStart == snapshotGeneration) {")
        )
    }

    // ── spec: the short-circuit, and the TTL that ends it ───────────────

    @Test
    fun `the short-circuit sits ahead of the disk and network paths`() {
        val body = snapshotBody()

        val shortCircuit = body.indexOf("budgetBlockedKey == apiKey")
        assertTrue(
            "the block must be checked before the disk layer looks for a row",
            shortCircuit in 0 until body.indexOf("snapshotDiskKey(apiKey)")
        )
        assertTrue(
            "and before any download is attempted",
            shortCircuit in 0 until body.indexOf("client.newCall(")
        )
        assertTrue(
            "the short-circuit answers from the caller's snapshot when there " +
                "is one and empty when there is not",
            body.contains(
                "return cachedSnapshot?.takeIf { cachedSnapshotKey == apiKey } " +
                    "?: MdbListWatchedSnapshot()"
            )
        )
        assertTrue(
            "it expires against the snapshot TTL, so the next day (or the " +
                "TTL running out) attempts a real download again",
            body.contains(
                "isBudgetBlockActive(budgetBlockedAt, System.currentTimeMillis(), SNAPSHOT_TTL_MS)"
            )
        )
    }

    // ── spec: an invalidate during a block still wins ───────────────────

    @Test
    fun `an invalidate during a block still wins`() {
        val invalidate = invalidateBody()

        assertTrue(
            "the invalidate still drops the snapshot and its key",
            invalidate.contains("cachedSnapshot = null") &&
                invalidate.contains("cachedSnapshotKey = \"\"")
        )
        assertTrue(
            "and still retires the fetch in flight",
            invalidate.contains("snapshotGeneration += 1")
        )
        assertTrue(
            "the generation check is the only gate on the write-back",
            snapshotBody()
                .contains("if (!result.isEmpty && generationAtStart == snapshotGeneration) {")
        )
        val body = snapshotBody()
        assertTrue(
            "the block was read AFTER the download unwound, so an invalidate " +
                "that landed while it was in flight leaves nothing to serve",
            body.indexOf("val stale = cachedSnapshot?.takeIf") >
                body.indexOf("var blockedByLocalBudget = false")
        )
    }

    @Test
    fun `a mark or unmark during a blocked day does not re-open the storm`() {
        // invalidateWatchedSnapshot runs after every scrobble/mark/unmark. If
        // it cleared the block stamp, the first mark of a blocked day would
        // put every later caller back on the download the interceptor is
        // going to refuse - the storm, again, from the busiest code path in
        // the app.
        assertFalse(
            "the invalidation must leave the budget block standing",
            invalidateBody().contains("budgetBlockedAt")
        )
    }

    // ── the interceptor stamps what the client matches ──────────────────

    @Test
    fun `the interceptor writes the code and message the client matches`() {
        val client = source(CLIENT)

        assertTrue(
            "the synthetic response must be built from the shared constants, " +
                "or the match can drift from what is sent",
            client.contains(".code(MDBLIST_LOCAL_BUDGET_CODE)") &&
                client.contains(".message(MDBLIST_LOCAL_BUDGET_MESSAGE)")
        )
    }

    // ── source helpers ─────────────────────────────────────────────────

    /** `getWatchedSnapshot`, whitespace-collapsed so the assertions are about
     * the branches rather than about how they happen to be indented.
     *
     * Every slice is checked for its delimiters first: `substringAfter`
     * silently yields the WHOLE string when its marker is missing, and a
     * renamed branch would then be asserted against the rest of the file and
     * pass for the wrong reason. */
    private fun snapshotBody(): String {
        val client = source(CLIENT)
        assertTrue(
            "the watched-snapshot read is missing",
            client.contains("suspend fun getWatchedSnapshot(")
        )
        assertTrue(
            "the read after the snapshot is missing",
            client.contains("watchedEpisodesForShow(")
        )
        return client
            .substringAfter("suspend fun getWatchedSnapshot(")
            .substringBefore("watchedEpisodesForShow(")
            .replace(Regex("\\s+"), " ")
    }

    private fun blockBody(): String {
        val body = snapshotBody()
        assertTrue(
            "the budget-block branch is missing from getWatchedSnapshot",
            body.contains("if (blockedByLocalBudget) {")
        )
        assertTrue(
            "the write-back gate the block returns before is missing",
            body.contains("// Only cache successful")
        )
        return body
            .substringAfter("if (blockedByLocalBudget) {")
            .substringBefore("// Only cache successful")
    }

    private fun invalidateBody(): String {
        val client = source(CLIENT)
        assertTrue(
            "the invalidation is missing",
            client.contains("fun invalidateWatchedSnapshot() {")
        )
        assertTrue(
            "the profile-switch alias after it is missing",
            client.contains("fun clearTransientCaches()")
        )
        return client
            .substringAfter("fun invalidateWatchedSnapshot() {")
            .substringBefore("fun clearTransientCaches()")
    }

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

    private val CLIENT = "com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt"
}
