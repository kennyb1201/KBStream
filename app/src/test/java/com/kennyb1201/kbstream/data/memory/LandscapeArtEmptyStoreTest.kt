package com.kennyb1201.kbstream.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the persisted "no landscape art" memory (spec 2026-11-15, F5).
 *
 * Worth pinning because every way it can go wrong is silent: a window that
 * never expires hides a backdrop the title has since gained, a missing window
 * pays the same ~500 ms lookup every session (the thing this exists to stop), a
 * cap that is not enforced grows a prefs blob on a 192 MB heap, and a parser
 * that trusts a corrupt line remembers the WRONG title as art-less.
 */
class LandscapeArtEmptyStoreTest {

    private val now = 1_000_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    // ── The window ────────────────────────────────────────────────────────

    @Test
    fun `an answer inside the window is remembered, and asked again after it`() {
        val entries = mapOf("series|tt1" to now - 3 * day)
        assertTrue(
            "three days old is still inside the fortnight",
            LandscapeArtEmptyStore.isRemembered(entries, "series|tt1", now)
        )
        assertTrue(
            "and it is trusted to the last moment of the window",
            LandscapeArtEmptyStore.isRemembered(
                entries,
                "series|tt1",
                now - 3 * day + LandscapeArtEmptyStore.TTL_MS - 1
            )
        )
        assertFalse(
            "past the window the lookup happens again: art can appear later",
            LandscapeArtEmptyStore.isRemembered(
                entries,
                "series|tt1",
                now - 3 * day + LandscapeArtEmptyStore.TTL_MS
            )
        )
    }

    @Test
    fun `an unknown title is never remembered`() {
        assertFalse(
            LandscapeArtEmptyStore.isRemembered(emptyMap(), "series|tt9", now)
        )
    }

    @Test
    fun `a stamp from the future is not trusted for longer than the window`() {
        // A clock that moved (or a restored backup) leaves a stamp ahead of now;
        // `now - at` is negative, and a negative age must not read as "fresh".
        val entries = mapOf("movie|tt2" to now + 30 * day)
        assertFalse(
            LandscapeArtEmptyStore.isRemembered(entries, "movie|tt2", now)
        )
    }

    // ── The cap ───────────────────────────────────────────────────────────

    @Test
    fun `the oldest entries are the ones dropped when the cap is reached`() {
        val entries = mutableMapOf<String, Long>()
        (1..5).forEach { index -> entries["series|tt$index"] = now - (10 - index) * day }
        LandscapeArtEmptyStore.remember(entries, "series|tt6", now, max = 4)

        assertEquals(4, entries.size)
        assertFalse("the oldest went", entries.containsKey("series|tt1"))
        assertTrue("the newest stayed", entries.containsKey("series|tt6"))
    }

    @Test
    fun `pruning drops expired entries and then the oldest above the cap`() {
        val entries = mapOf(
            "old" to now - 30 * day,
            "a" to now - 2 * day,
            "b" to now - 1 * day,
            "c" to now
        )
        val kept = LandscapeArtEmptyStore.prune(entries, now, max = 2)
        assertEquals(setOf("b", "c"), kept.keys)
    }

    // ── The blob ──────────────────────────────────────────────────────────

    @Test
    fun `a written blob reads back the same entries`() {
        val entries = mapOf("series|tt1" to now, "movie|tt2" to now - day)
        assertEquals(
            entries,
            LandscapeArtEmptyStore.parse(LandscapeArtEmptyStore.serialize(entries))
        )
    }

    @Test
    fun `a corrupt or empty blob costs a re-lookup, never a wrong memory`() {
        assertTrue(LandscapeArtEmptyStore.parse(null).isEmpty())
        assertTrue(LandscapeArtEmptyStore.parse("").isEmpty())
        assertTrue(LandscapeArtEmptyStore.parse("   ").isEmpty())
        assertEquals(
            "a line without a stamp, a stamp without a key and a non-numeric " +
                "stamp are all skipped - a half-written row must never remember " +
                "a title",
            mapOf("series|tt1" to now),
            LandscapeArtEmptyStore.parse("nonsense\n$now\nnull\tseries|tt9\n$now\tseries|tt1")
        )
    }
}
