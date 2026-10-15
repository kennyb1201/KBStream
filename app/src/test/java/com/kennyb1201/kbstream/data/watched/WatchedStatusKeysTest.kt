package com.kennyb1201.kbstream.data.watched

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind a browse tile's watched badge, pinned directly.
 *
 * Field report 2026-10-14: on the genre / studio / decade / collection pages
 * many posters showed no eye and no checkmark even though the titles were
 * watched. Every one of those screens badged off a resolved IMDB id, and that
 * resolution pass is capped at 300 ids — while a six-rail page carries up to
 * ~840 items, so everything past the cap had no key at all. The `tmdb:<n>` id
 * flavor needs no resolution, and this is the rule that emits it.
 *
 * The rule is exercised here rather than through the four view models, which
 * are `AndroidViewModel`s that construct their repository privately — the
 * repository being the seam, this is the unit that can be faked. The four call
 * sites are pinned separately (see `BrowseWatchedKeyWiringContractTest`).
 */
class WatchedStatusKeysTest {

    /**
     * The repository as the view models use it, faked down to the set of
     * `"type::id"` keys it would answer true for.
     */
    private class FakeWatchedStatus(private vararg val watched: String) {
        suspend fun isWatchedCached(id: String, type: String): Boolean =
            WatchedStatusKeys.key(id, type) in watched
    }

    @Test
    fun `an item the resolution pass never reached badges under its tmdb key`() = runBlocking {
        // Item 601 of a six-rail genre page: past the resolver's cap, so there
        // is no IMDB id for it and there never will be on this visit.
        val repository = FakeWatchedStatus("series::tmdb:601")

        val keys = WatchedStatusKeys.watchedKeysOf(
            tmdbId = 601,
            mediaType = "series",
            resolvedImdbId = null,
            matches = repository::isWatchedCached
        )

        assertEquals(listOf("series::tmdb:601"), keys)
    }

    @Test
    fun `an item whose resolution returned nothing badges under its tmdb key`() = runBlocking {
        // TMDB holds no `imdb_id` for the title, or the lookup failed: the
        // resolution ran and answered nothing. Under the old rule this title was
        // bare permanently.
        val repository = FakeWatchedStatus("movie::tmdb:550")

        val keys = WatchedStatusKeys.watchedKeysOf(
            tmdbId = 550,
            mediaType = "movie",
            resolvedImdbId = null,
            matches = repository::isWatchedCached
        )

        assertEquals(listOf("movie::tmdb:550"), keys)
    }

    @Test
    fun `a resolved item watched under both flavors keeps both keys, IMDB first`() = runBlocking {
        val repository = FakeWatchedStatus("series::tt0903747", "series::tmdb:1396")

        val keys = WatchedStatusKeys.watchedKeysOf(
            tmdbId = 1396,
            mediaType = "series",
            resolvedImdbId = "tt0903747",
            matches = repository::isWatchedCached
        )

        // Order is part of the contract: the tile tests the IMDB key first, so
        // the badge a resolved title gets is the one it always got.
        assertEquals(listOf("series::tt0903747", "series::tmdb:1396"), keys)
    }

    @Test
    fun `a resolved title watched only under its IMDB key is unchanged`() = runBlocking {
        val repository = FakeWatchedStatus("series::tt0903747")

        val keys = WatchedStatusKeys.watchedKeysOf(
            tmdbId = 1396,
            mediaType = "series",
            resolvedImdbId = "tt0903747",
            matches = repository::isWatchedCached
        )

        assertEquals(listOf("series::tt0903747"), keys)
    }

    @Test
    fun `an unwatched title emits nothing under either flavor`() = runBlocking {
        val repository = FakeWatchedStatus()

        val keys = WatchedStatusKeys.watchedKeysOf(
            tmdbId = 1396,
            mediaType = "series",
            resolvedImdbId = "tt0903747",
            matches = repository::isWatchedCached
        )

        assertTrue("no badge for a title with no watch state", keys.isEmpty())
    }

    @Test
    fun `a blank resolved id is not a key of its own`() = runBlocking {
        val repository = FakeWatchedStatus("movie::tmdb:550")

        val keys = WatchedStatusKeys.watchedKeysOf(
            tmdbId = 550,
            mediaType = "movie",
            resolvedImdbId = "   ",
            matches = repository::isWatchedCached
        )

        assertEquals(listOf("movie::tmdb:550"), keys)
    }

    @Test
    fun `the tmdb flavor is built from the rail id alone`() {
        // The reason the fix needs no network and cannot be capped: this is a
        // pure string build, and nothing about it depends on the resolution
        // pass having run.
        assertEquals("tmdb:1396", WatchedStatusKeys.tmdbKeyId(1396))
        assertEquals("movie::tmdb:1396", WatchedStatusKeys.key("tmdb:1396", "movie"))
    }

    @Test
    fun `the key lowercases the type, and only lowercases it`() {
        assertEquals("series::tt2", WatchedStatusKeys.key("tt2", "Series"))
        assertEquals("movie::tt3", WatchedStatusKeys.key("tt3", "MOVIE"))
        // "TV" lowercases to "tv" and is NOT mapped onto the app's own
        // "series" — mapping belongs to the callers (normalizeMediaType /
        // normalizedType), which all normalize before the type gets here. Pinned
        // so this function is not read as the place that normalizes.
        assertEquals("tv::tt1", WatchedStatusKeys.key("tt1", "TV"))
    }
}
