package com.kennyb1201.kbstream.data.bingecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four decisions behind every BingeCat write, with no network.
 *
 * Each one fails silently if it drifts: a step-1 grant would throw 403s that
 * read like failures, an untranslatable id would file a play against the wrong
 * title, a malformed `watched_at` would be rejected by a server that only says
 * `400`, and an ignored `Retry-After` would turn a rate limit into a storm.
 */
class BingeCatWriteRulesTest {

    // ── The scope gate ───────────────────────────────────────────────────

    @Test
    fun `a step-1 grant cannot write`() {
        assertFalse(BingeCatWriteRules.hasWriteScope("profiles:read lists:read history:read"))
        assertFalse("an empty/garbled scope is not a write grant", BingeCatWriteRules.hasWriteScope(null))
        assertFalse(BingeCatWriteRules.hasWriteScope(""))
        assertFalse(
            "only the exact scope counts - a prefix is not the permission",
            BingeCatWriteRules.hasWriteScope("history:read history:writes")
        )
    }

    @Test
    fun `a re-approved grant can write, whatever the separators`() {
        assertTrue(
            BingeCatWriteRules.hasWriteScope(
                "profiles:read lists:read history:read history:write"
            )
        )
        assertTrue(
            "a grant that only carries the write scope still writes",
            BingeCatWriteRules.hasWriteScope("history:write")
        )
        assertTrue(
            "and a comma-separated echo must not read as missing",
            BingeCatWriteRules.hasWriteScope("history:read, history:write")
        )
        assertTrue(
            "the config's own scope line is a write grant",
            BingeCatWriteRules.hasWriteScope(BingeCatConfig.SCOPES)
        )
        assertTrue(
            "and it asks for the write scope the gate looks for",
            BingeCatConfig.SCOPES.split(" ").contains(BingeCatWriteRules.WRITE_SCOPE)
        )
    }

    // ── The identifiers ──────────────────────────────────────────────────

    @Test
    fun `both known ids travel, so the server can resolve either`() {
        val ids = BingeCatWriteRules.writeIds("tt1234567", tmdbId = 603)
        assertNotNull(ids)
        assertEquals("tt1234567", ids?.imdb)
        assertEquals(603, ids?.tmdb)
    }

    @Test
    fun `an app id spelling is translated when it is the only one`() {
        val tmdb = BingeCatWriteRules.writeIds("tmdb:603")
        assertEquals(603, tmdb?.tmdb)
        assertNull("an app-spelled tmdb id must not be sent as an imdb id", tmdb?.imdb)

        val imdb = BingeCatWriteRules.writeIds("tt1234567")
        assertEquals("tt1234567", imdb?.imdb)
        assertNull(imdb?.tmdb)
    }

    @Test
    fun `an id the server could never resolve is bailed on, not guessed`() {
        assertNull(BingeCatWriteRules.writeIds(""))
        assertNull(BingeCatWriteRules.writeIds("local-file-123"))
        assertNull(
            "a zero TMDB id is not an identifier, so it is simply not sent",
            BingeCatWriteRules.writeIds("tt1234567", tmdbId = 0)?.tmdb
        )
        val resolved = BingeCatWriteRules.writeIds("tmdb:603", tmdbId = 604)
        assertEquals(
            "the session's resolved TMDB id wins over the title's own spelling",
            604,
            resolved?.tmdb
        )
        assertNull(resolved?.imdb)
    }

    // ── watched_at ───────────────────────────────────────────────────────

    @Test
    fun `watched_at is an rfc3339 instant at second precision`() {
        assertEquals(
            "2025-11-04T21:15:59Z",
            BingeCatWriteRules.watchedAt(1_762_290_959_789L)
        )
        assertTrue(
            "the contract's format is date-time: no millis, always a zone",
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
                .matches(BingeCatWriteRules.watchedAt())
        )
    }

    // ── The 429 park ─────────────────────────────────────────────────────

    @Test
    fun `a 429 parks the leg for its retry-after`() {
        var now = 1_000_000L
        val guard = BingeCatWriteGuard(clock = { now })
        assertEquals("a fresh leg may write at once", 0L, guard.parkedForMs())

        guard.noteRetryAfter(90)
        assertEquals(90_000L, guard.parkedForMs())
        now += 89_000L
        assertEquals("still parked one second before the window ends", 1_000L, guard.parkedForMs())
        now += 1_000L
        assertEquals("and free again after it", 0L, guard.parkedForMs())
    }

    @Test
    fun `a 429 without a retry-after parks for the default minute, never less than a second`() {
        var now = 0L
        val guard = BingeCatWriteGuard(clock = { now })
        guard.noteRetryAfter(null)
        assertEquals(
            BingeCatWriteGuard.DEFAULT_RETRY_AFTER_SECONDS * 1_000L,
            guard.parkedForMs()
        )

        val zero = BingeCatWriteGuard(clock = { now })
        zero.noteRetryAfter(0)
        assertEquals("a zero window must not mean 'fire immediately'", 1_000L, zero.parkedForMs())
    }

    @Test
    fun `a park never shortens an existing one`() {
        var now = 0L
        val guard = BingeCatWriteGuard(clock = { now })
        guard.noteRetryAfter(600)
        guard.noteRetryAfter(1)
        assertEquals("the longer window wins", 600_000L, guard.parkedForMs())
    }

    @Test
    fun `only a 429 is a rate limit`() {
        assertFalse(noteBingeCatWriteRateLimit(401, "60"))
        assertFalse(noteBingeCatWriteRateLimit(403, "60"))
        assertFalse(noteBingeCatWriteRateLimit(500, null))
    }

    @Test
    fun `saving has its own scope gate, and a step-2 grant fails it`() {
        assertTrue(
            "the scope line asks for it, so a fresh grant can save",
            BingeCatWriteRules.hasListsWriteScope(BingeCatConfig.SCOPES)
        )
        assertTrue(BingeCatWriteRules.hasListsWriteScope("lists:read lists:write"))
        assertTrue(
            "a comma-separated echo is parsed the same way",
            BingeCatWriteRules.hasListsWriteScope("lists:read,lists:write")
        )
        assertFalse(
            "the step-2 grant has history:write and not lists:write: saving is off " +
                "until the viewer reconnects",
            BingeCatWriteRules.hasListsWriteScope("lists:read history:read history:write")
        )
        assertFalse("a prefix is not the scope", BingeCatWriteRules.hasListsWriteScope("lists:writes"))
        assertFalse(BingeCatWriteRules.hasListsWriteScope(null))
        assertTrue(
            "and the two gates do not borrow each other: the same step-2 grant still " +
                "scrobbles",
            BingeCatWriteRules.hasWriteScope("lists:read history:read history:write")
        )
    }

    @Test
    fun `a list write names the title by ONE media reference`() {
        // The contract's own grammar for /lists/{ref}/items items: `imdb:tt…`,
        // `tmdb:movie:N`, `tmdb:show:N` (or a BingeCat id/slug, which this app
        // never sends). A bare number would be read as a BingeCat id.
        assertEquals(
            "IMDb is preferred: unique without carrying a type",
            "imdb:tt0113277",
            BingeCatWriteRules.mediaRef("tt0113277", null, isMovie = true)
        )
        assertEquals(
            "and still preferred when a TMDB id is known too",
            "imdb:tt0113277",
            BingeCatWriteRules.mediaRef("tt0113277", 603, isMovie = false)
        )
        assertEquals(
            "the typed TMDB form is the fallback - and the type is in the string",
            "tmdb:movie:603",
            BingeCatWriteRules.mediaRef("tmdb:603", 603, isMovie = true)
        )
        assertEquals(
            "series get the show form, not the movie one",
            "tmdb:show:1399",
            BingeCatWriteRules.mediaRef("tmdb:1399", 1399, isMovie = false)
        )
        assertEquals(
            "a bare TMDB id from the session is enough to build one",
            "tmdb:movie:603",
            BingeCatWriteRules.mediaRef("", 603, isMovie = true)
        )
        assertNull(
            "a bare number is a BingeCat id, so it is not sent as a reference",
            BingeCatWriteRules.mediaRef("603", null, isMovie = true)
        )
        assertNull("a slug is not something this app sends", BingeCatWriteRules.mediaRef("some-slug", null, isMovie = true))
        assertNull(BingeCatWriteRules.mediaRef("", null, isMovie = true))
    }
}
