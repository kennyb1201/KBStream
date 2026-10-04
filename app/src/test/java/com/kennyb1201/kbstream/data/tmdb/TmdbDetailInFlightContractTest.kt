package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A title being fetched must be fetched ONCE.
 *
 * Home's enrichment pass and a focus-driven hero resolve routinely ask for the
 * same detail at the same moment. Both miss the memory/disk TTL caches (the
 * first has not filled them yet) and, before this, both fired a request — pure
 * waste on a TV's link and a jitter source for the on-screen resolve. The
 * caller now awaits a shared in-flight deferred (see TmdbHeroArtworkRepository
 * for the pattern). A dropped dedup fails nothing at compile time and is
 * invisible in a JVM test that cannot reach TMDB, so the wiring is read from
 * the source.
 */
class TmdbDetailInFlightContractTest {

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

    private val repository: String
        get() {
            val file = File(
                sourceRoot,
                "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
            )
            assertTrue("source missing: $file", file.isFile)
            return file.readText()
        }

    /** The body of getDetailByTmdbId, up to the next function declaration. */
    private fun detailBody(): String {
        val src = repository
        val start = src.indexOf("suspend fun getDetailByTmdbId(")
        assertTrue("getDetailByTmdbId must exist", start >= 0)
        val end = src.indexOf("suspend fun cachedDetailByTmdbId(", start)
        assertTrue("the body must be bounded by the next function", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `the repository keeps an in-flight detail map`() {
        assertTrue(
            "a concurrent detail fetch must have somewhere to register",
            repository.contains("detailFetchInFlight")
        )
        assertTrue(
            "the registration must be a CompletableDeferred so a second " +
                "caller can await the first one's result",
            repository.contains(
                "ConcurrentHashMap<String, CompletableDeferred<TmdbDetail?>>()"
            )
        )
    }

    @Test
    fun `a concurrent caller awaits the pending fetch instead of fetching again`() {
        val body = detailBody()
        assertTrue(
            "the map must be consulted before the network call",
            body.contains("detailFetchInFlight[key]?.let { pending -> return pending.await() }")
        )
        assertTrue(
            "the check-then-register window must be closed with putIfAbsent",
            body.contains("detailFetchInFlight.putIfAbsent(key, deferred)")
        )
        assertTrue(
            "the shared deferred must be completed with the fetch result",
            body.contains("deferred.complete(result)")
        )
        assertTrue(
            "the in-flight marker must be dropped once the fetch settles",
            body.contains("detailFetchInFlight.remove(key)")
        )
        assertFalse(
            "the old shape fired an unconditional request - if that call site " +
                "is back, so is the duplicate fetch",
            body.contains("val result = runCatchingCancellable {")
        )
    }
}
