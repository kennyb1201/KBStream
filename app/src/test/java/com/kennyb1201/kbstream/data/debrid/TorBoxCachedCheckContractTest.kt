package com.kennyb1201.kbstream.data.debrid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one contract change the cached-only filter needs (spec §2, T4/T5).
 *
 * `checkCached` used to answer `emptySet()` for four different facts: no key, no
 * usable hash, every batch failed, and "none of these are cached". The chip pass
 * could not tell them apart and did not care. The filter cares enormously - the
 * first three mean "we never judged this list, leave it alone", the fourth means
 * "hide every hash row" - so the check now names which one it is.
 *
 * These are source pins, because the function itself is Android- and
 * network-bound and the states it distinguishes are exactly the branches a
 * mocked HTTP client cannot reach (the failure path swallows exceptions).
 */
class TorBoxCachedCheckContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun check(): String = source(CLIENT)
        .substringAfter("suspend fun checkCached(")
        .substringBefore("private fun query(")

    private companion object {
        const val CLIENT = "com/kennyb1201/kbstream/data/debrid/TorBoxClient.kt"
        const val RESOLVER = "com/kennyb1201/kbstream/data/debrid/TorBoxResolve.kt"
        const val FILTER = "com/kennyb1201/kbstream/data/debrid/TorBoxCachedOnly.kt"
    }

    // ── UNAVAILABLE: the check has no verdict to give ────────────────────────

    @Test
    fun `no key is unavailable, not an empty answer`() {
        val keyless = check().substringAfter("if (apiKey.isBlank()) {")
        assertTrue(
            "a keyless device must leave the picker alone, never empty it",
            keyless.take(400).contains("TorBoxCachedAnswer.Unavailable")
        )
    }

    @Test
    fun `a list with no lookable hash is unavailable`() {
        val noHash = check().substringAfter("if (wanted.isEmpty()) {")
        assertTrue(
            noHash.take(900).contains("TorBoxCachedAnswer.Unavailable")
        )
    }

    @Test
    fun `every batch failing is unavailable, and teaches the memo nothing`() {
        val body = check()
        assertTrue(
            "a failed batch must not count as an answer",
            body.contains(".getOrNull() ?: return@forEach")
        )
        assertTrue(
            "and with none answered the function says so instead of answering " +
                "an empty set that would hide every hash row",
            body.contains("if (!answered)") &&
                body.substringAfter("if (!answered)").contains("TorBoxCachedAnswer.Unavailable")
        )
        assertTrue(
            "the reason line exists so a capture can tell 'not cached' from " +
                "'could not ask'",
            body.contains("no batch answered")
        )
        // The memo write is after the null check, so a failed hash is retried on
        // the next fetch rather than remembered as uncached.
        val cacheWrite = body.indexOf("cache[hash] = Entry(")
        assertTrue(
            "a failed batch left uncached for retry",
            body.indexOf(".getOrNull() ?: return@forEach") < cacheWrite
        )
    }

    // ── ANSWERED: the check has a verdict, even when it is "none" ────────────

    @Test
    fun `a genuine zero cached is ANSWERED with an empty set`() {
        val body = check()
        assertTrue(
            "the successful path returns Answered, carrying whatever was cached",
            body.trimEnd().endsWith("TorBoxCachedAnswer.Answered(cachedNow)\n        }") ||
                body.contains("TorBoxCachedAnswer.Answered(cachedNow)")
        )
        assertFalse(
            "and an empty result must NOT be re-labelled unavailable: 'none of " +
                "these are cached' is the answer the filter hides rows on",
            body.contains("if (cachedNow.isEmpty())")
        )
    }

    @Test
    fun `a memo-only answer counts as answered`() {
        assertTrue(
            "on a repeat visit every hash is fresh in the memo, no batch runs, and " +
                "the filter still has to work - so an empty toQuery is an answer",
            check().contains("var answered = toQuery.isEmpty()")
        )
    }

    @Test
    fun `one answered batch is enough on a partially failed check`() {
        assertTrue(
            "the flag flips on the first batch that returns; it never asks for all " +
                "of them",
            check().contains("answered = true")
        )
    }

    // ── T5: the two existing callers keep their exact outcomes ───────────────

    @Test
    fun `the resolver plays nothing on UNAVAILABLE and nothing on uncached`() {
        val resolve = source(RESOLVER).substringAfter("suspend fun playableStream(")
        assertTrue(
            "UNAVAILABLE is unplayable: an unproven hash cannot be served off " +
                "the account's cache",
            resolve.contains("TorBoxCachedAnswer.Unavailable ->") &&
                resolve.contains("TorBoxTrace.note(\"resolve · no cached answer, left unplayable\")")
        )
        assertTrue(
            "and an answered set still gates on membership, exactly as before",
            resolve.contains("is TorBoxCachedAnswer.Answered -> answer.cached") &&
                resolve.contains("if (hash !in cached)")
        )
    }

    @Test
    fun `the check is normalized through the same rule the filter uses`() {
        assertTrue(
            "one normalization, or the badge pass and the filter could disagree " +
                "about which rows were judged",
            check().contains("mapNotNull { TorBoxCachedOnly.lookupHash(it) }")
        )
        assertFalse(
            "no second trim-lowercase-length dance in the client",
            check().contains("it.length >=")
        )
        assertTrue(
            "and the filter's own rule is that function",
            source(FILTER).contains("fun lookupHash(raw: String?): String?")
        )
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
