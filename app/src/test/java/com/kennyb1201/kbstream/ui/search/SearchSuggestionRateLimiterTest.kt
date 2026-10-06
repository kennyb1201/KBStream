package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exported provider's query budget (HD-P2-6).
 *
 * The provider must be exported for the launcher to reach it, and `query`
 * answers over binder by blocking on `runBlocking`, so any app on the box can
 * make this app spend its own TMDB quota one call at a time. The window is
 * injected so it can be exercised without a provider, a device or a sleep.
 */
class SearchSuggestionRateLimiterTest {

    @Test
    fun `a burst up to the cap is allowed`() {
        val limiter = SearchSuggestionRateLimiter(maxRequests = 3, windowMs = 1_000L)
        assertTrue(limiter.allow(0L))
        assertTrue(limiter.allow(0L))
        assertTrue(limiter.allow(0L))
    }

    @Test
    fun `past the cap the same window refuses`() {
        val limiter = SearchSuggestionRateLimiter(maxRequests = 3, windowMs = 1_000L)
        repeat(3) { assertTrue(limiter.allow(0L)) }
        assertFalse("the fourth call in the window must answer empty", limiter.allow(0L))
        assertFalse(limiter.allow(999L))
    }

    @Test
    fun `the window slides so a quiet caller recovers`() {
        val limiter = SearchSuggestionRateLimiter(maxRequests = 2, windowMs = 1_000L)
        assertTrue(limiter.allow(0L))
        assertTrue(limiter.allow(0L))
        assertFalse(limiter.allow(500L))
        // Both stamps have aged out by t=1000, so the budget is fresh - a
        // hammering caller is throttled, not permanently banned.
        assertTrue(limiter.allow(1_000L))
    }

    @Test
    fun `only the calls inside the window count`() {
        val limiter = SearchSuggestionRateLimiter(maxRequests = 2, windowMs = 1_000L)
        assertTrue(limiter.allow(0L))
        assertTrue(limiter.allow(600L))
        // The t=0 stamp is out at t=1000 but the t=600 one is not.
        assertTrue(limiter.allow(1_000L))
        assertFalse(limiter.allow(1_100L))
        assertTrue(limiter.allow(1_600L))
    }
}

/**
 * And the provider actually consults it, before it spends the query (HD-P2-6).
 * Read from the source: reaching the gate for real needs a ContentProvider, a
 * launcher and a TMDB key.
 */
class SearchSuggestionProviderBudgetContractTest {

    private fun source(): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, PROVIDER)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `the query path is gated on the budget`() {
        val text = source()
        val gate = text.indexOf("if (!rateLimiter.allow(SystemClock.elapsedRealtime()))")
        assertTrue("the provider must consult the limiter", gate >= 0)
        val fetch = text.indexOf("fetchSuggestions(context, query)")
        assertTrue("and it must still fetch", fetch >= 0)
        assertTrue(
            "the gate has to come BEFORE the TMDB call, or it bounds nothing",
            gate < fetch
        )
        assertTrue(
            "the limiter is per provider instance (one per process)",
            text.contains("private val rateLimiter = SearchSuggestionRateLimiter()")
        )
    }

    private companion object {
        const val PROVIDER = "com/kennyb1201/kbstream/ui/search/SearchSuggestionsProvider.kt"
    }
}
