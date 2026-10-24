package com.kennyb1201.kbstream.data.debrid

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TorBox's two memories are bounded.
 *
 * Both are write-only structures a long-lived process adds to and nothing ever
 * takes out of: [TorBoxClient] memoizes one hash -> cached row per release the
 * viewer browses, and [TorBoxCloudStore] persists one id per torrent its library
 * sync has already handled. Neither is something the viewer can see, so neither
 * was ever going to be noticed growing — a season of browsing, or a very large
 * account, simply made the process hold more and more. Each now has a ceiling
 * and a stated policy for what it drops.
 *
 * Read out of the source rather than driven: the memo is a private field of an
 * object whose network path needs a key, and the store's set lives in a
 * SharedPreferences file, so neither ceiling is observable from a unit test
 * without an Android context.
 */
class TorBoxResourceBoundsContractTest {

    private val client = read("com/kennyb1201/kbstream/data/debrid/TorBoxClient.kt")
    private val store = read("com/kennyb1201/kbstream/data/debrid/TorBoxCloudStore.kt")

    // ── the hash memo ──────────────────────────────────────────────────────

    @Test
    fun `the cached-hash memo has a ceiling and prunes past it`() {
        assertTrue(
            "the memo needs a size ceiling",
            client.contains("private const val MAX_CACHE_ENTRIES = ")
        )
        val prune = client
            .substringAfter("private fun pruneCache(nowMs: Long) {")
            .substringBefore("fun checkCached(")
        val expired = prune.indexOf("removeIf")
        val oldest = prune.indexOf("sortedBy { it.value.atMs }")
        assertTrue(
            "the expired entries are the ones to drop first",
            expired >= 0 && oldest > expired
        )
        assertTrue(
            "and when that is not enough, the oldest go too",
            prune.contains(".take(cache.size - MAX_CACHE_ENTRIES)")
        )
    }

    @Test
    fun `a filled batch is what triggers the prune`() {
        val filled = client.indexOf("cache[hash] = Entry(cached = hash in result, atMs = stamp)")
        val pruned = client.indexOf("pruneCache(stamp)")
        assertTrue("the batch insert must be in the source", filled >= 0)
        assertTrue("the prune must follow the insert it bounds", pruned > filled)
    }

    // ── the processed-torrent set ──────────────────────────────────────────

    @Test
    fun `the processed-torrent set has a ceiling and persists the capped set`() {
        assertTrue(
            "the set needs a size ceiling",
            store.contains("private const val MAX_PROCESSED = ")
        )
        val cap = store.indexOf("if (updated.size > MAX_PROCESSED)")
        val write = store.indexOf("putStringSet(KEY_PROCESSED, capped.map")
        assertTrue("the cap must be applied to the set that is written", cap >= 0 && write > cap)
    }

    @Test
    fun `every read-modify-write on the store's sets is locked`() {
        // Launch, the schedule and a manual "sync now" (or a Resync) can all
        // reach this, and the read-then-write each of these does is the shape
        // that loses a whole round's ids when two overlap. The stored sets are
        // the processed ids, the empty-search streak and the no-match streak
        // that decide when a torrent stops being looked up (see
        // [TorBoxCloudRules.EMPTY_SEARCH_ATTEMPTS]), plus the last round's
        // record - and the migration that clears all of it. Every member that
        // reads or writes them is listed here, so a new one cannot arrive
        // unlocked.
        listOf(
            "@Synchronized\\s+fun processed\\(\\)",
            "@Synchronized\\s+fun markProcessed\\(",
            "@Synchronized\\s+fun clearAllMarks\\(",
            "@Synchronized\\s+fun forgetPreCountingFixMarksOnce\\(",
            "@Synchronized\\s+fun emptySearchAttempts\\(",
            "@Synchronized\\s+fun noteEmptySearch\\(",
            "@Synchronized\\s+fun clearEmptySearch\\(",
            "@Synchronized\\s+fun noMatchAttempts\\(",
            "@Synchronized\\s+fun noteNoMatch\\(",
            "@Synchronized\\s+fun clearNoMatch\\(",
            "@Synchronized\\s+fun lastRound\\(\\)",
            "@Synchronized\\s+fun recordRound\\(",
            "@Synchronized\\s+fun ownershipRemediationPending\\(\\)",
            "@Synchronized\\s+fun markOwnershipRemediated\\(\\)",
            "@Synchronized\\s+fun clearOwnershipRemediation\\(\\)"
        ).forEach { signature ->
            assertTrue(
                "missing lock: $signature",
                Regex(signature).containsMatchIn(store)
            )
        }
        assertEquals(
            "no member that does not touch the stored sets should be locked",
            15,
            Regex("@Synchronized[\\s\\S]{0,40}?fun ").findAll(store).count()
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
}
