package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity a completion mark carries, and the fact that it is the SAME
 * identity the tracker card opposite it presents.
 *
 * The field case (Onn 4K Plus, 0.9.33/0.9.34): NEXT UP cards for episodes the
 * profile had already watched - RJ Decker, Animal Control S5E3, The Simpsons
 * S38E3 - survived every merge and every cold start, while other stale cards did
 * drop. The drop itself was in the merge and running; its MATCHER was the defect.
 * A mark could only ever present two identities (the raw parent id flavour and a
 * letter-for-letter title), both bucketed by [upNextMediaType]:
 *
 *  - the card's TMDB id was never among a mark's keys (the mark was built with
 *    `tmdbId = null`, since watch_history has no such column), so a row filed
 *    under "tt…" was unmatchable against a card filed under "tmdb:<n>";
 *  - a title key was punctuation-significant, so "R.J. Decker" and "RJ Decker"
 *    never met;
 *  - an addon's own type flavour bucketed both keys under "unknown", a bucket no
 *    show card visits, so a PERFECT id and title match could still be orphaned.
 *
 * Each of those is a test below, and the episode guard - the thing that must not
 * widen - is pinned on both sides of the change.
 */
class CompletedMarkIdentityTest {

    private fun row(
        parentId: String = "tt10986410",
        type: String = "series",
        name: String = SHOW,
        season: Int? = 2,
        episode: Int? = 8
    ): WatchHistoryEntity =
        WatchHistoryEntity(
            id = "$parentId:$season:$episode",
            parentId = parentId,
            type = type,
            name = name,
            poster = null,
            streamUrl = null,
            season = season,
            episode = episode,
            positionMs = 0L,
            durationMs = 44L * 60L * 1000L,
            updatedAt = 1_000L,
            isCompleted = true,
            completedAt = 1_000L
        )

    private fun trackerCard(
        parentId: String = "tmdb:97546",
        tmdbId: Int? = 97546,
        title: String = SHOW,
        parentType: String = "series",
        season: Int? = 2,
        episode: Int? = 8
    ): UpNextItem = UpNextItem(
        id = "simkl:show-12345",
        title = title,
        poster = null,
        badge = UpNextBadge.NEXT_UP,
        subtitle = "Up Next",
        parentId = parentId,
        parentType = parentType,
        season = season,
        episode = episode,
        tmdbId = tmdbId,
        recencyTimestamp = 2_000L
    )

    private fun mark(
        parentId: String = "tt10986410",
        parentType: String = "series",
        tmdbId: Int? = 97546
    ): CompletedTitleMark =
        completedTitleMark(
            parentId = parentId,
            parentType = parentType,
            tmdbId = tmdbId,
            season = 2,
            episode = 8,
            title = SHOW
        )!!

    // ── 1. the TMDB badge the marks never had ──────────────────────────────

    @Test
    fun `the resolved TMDB id is a mark's key, and it bridges the two flavors`() {
        // The row is filed under the IMDb id; the card only knows TMDB. The
        // resolved number is the one identity both can present.
        val rows = listOf(row(parentId = "tt10986410"))
        val card = trackerCard(parentId = "tmdb:97546", tmdbId = null, title = "Unrelated Name")

        assertTrue(
            "with the resolution, the show is matched on the number alone",
            dropTrackerCardsForCompletedTargets(
                items = listOf(card),
                completedRows = rows,
                tmdbByParentId = mapOf("tt10986410" to 97546)
            ).isEmpty()
        )
        assertTrue(
            "and without it the mark is exactly what it always was - the drop " +
                "degrades, it never guesses",
            dropTrackerCardsForCompletedTargets(
                items = listOf(card),
                completedRows = rows
            ).isNotEmpty()
        )
    }

    @Test
    fun `the mark carries the parent key in the card's own vocabulary`() {
        val keys = mark(tmdbId = 97546).keys
        assertTrue(
            "parent:series:97546 is what upNextStrongIdentityKeys emits for the card",
            "parent:series:97546" in keys
        )
        assertTrue("and the raw flavor is still there", "parent:series:tt10986410" in keys)
    }

    @Test
    fun `a null resolution adds no key and does not fail the mark`() {
        val withoutTmdb = mark(tmdbId = null)
        assertFalse("parent:series:97546" in withoutTmdb.keys)
        assertTrue("parent:series:tt10986410" in withoutTmdb.keys)
    }

    // ── 2. one punctuation-proof title key ────────────────────────────────

    @Test
    fun `a title differing only in punctuation still matches`() {
        // The reported pair.
        val dropped = dropTrackerCardsForCompletedTargets(
            items = listOf(trackerCard(title = "R.J. Decker", tmdbId = null, parentId = "tmdb:1")),
            completedRows = listOf(row(parentId = "tt1", name = "RJ Decker"))
        )
        assertTrue("punctuation is not part of a title's identity", dropped.isEmpty())
    }

    @Test
    fun `the normalizer keeps letters, digits and single spaces`() {
        assertEquals(
            "title:series:rj decker 2",
            upNextTitleKeyFor("  R.J.  Decker 2 ", "series")
        )
        assertEquals(
            "a name with no letters or digits has no title key",
            null,
            upNextTitleKeyFor("!!! ---", "series")
        )
        assertNull(upNextTitleKeyFor(null, "series"))
    }

    @Test
    fun `the card and the mark build the title key the same way`() {
        // The drift this exists to stop: two constructions, only one of them in
        // the mark, and the apostrophes on one side only.
        val item = trackerCard(title = "Bob's Burgers", parentType = "tv")
        assertEquals(
            upNextTitleKeyFor("Bob's Burgers", "tv"),
            upNextTitleKey(item)
        )
        assertEquals("title:series:bobs burgers", upNextTitleKey(item))
    }

    // ── 3. a completed row with an episode IS a series ────────────────────

    @Test
    fun `an addon type flavour on the row cannot orphan a perfect match`() {
        // The row was filed by an add-on under its own flavour, which
        // upNextMediaType collapses to "unknown" - a bucket no card visits.
        // Nothing but the id can pair these two: the names differ, and neither
        // side carries a resolvable TMDB id of its own.
        val dropped = dropTrackerCardsForCompletedTargets(
            items = listOf(trackerCard(tmdbId = null, title = "Something Else")),
            completedRows = listOf(
                row(parentId = "tmdb:97546", type = "anime", name = SHOW)
            )
        )
        assertTrue(
            "an episode row is a series for keying purposes, whatever the add-on called it",
            dropped.isEmpty()
        )
    }

    @Test
    fun `a movie keeps its own type bucket`() {
        val mark = completedTitleMark(
            parentId = "tt1160419",
            parentType = "movie",
            tmdbId = 438631,
            season = null,
            episode = null,
            title = "Dune"
        )!!
        assertTrue("parent:movie:438631" in mark.keys)
        assertTrue("title:movie:dune" in mark.keys)
    }

    @Test
    fun `a row with an episode zero is still a movie-shaped row`() {
        val mark = completedTitleMark(
            parentId = "tt1160419",
            parentType = "movie",
            tmdbId = null,
            season = 0,
            episode = 0,
            title = "Dune"
        )!!
        assertTrue("parent:movie:tt1160419" in mark.keys)
        assertNull(mark.season)
    }

    // ── 4. identifier vocabulary ──────────────────────────────────────────

    @Test
    fun `the prefix vocabulary is canonicalised, not just tmdb and simkl`() {
        assertEquals("123", upNextIdentifier("tmdb:123"))
        assertEquals("456", upNextIdentifier("tvdb:456"))
        assertEquals("789", upNextIdentifier("trakt:789"))
        assertEquals("tt123", upNextIdentifier("  TT123 "))
        assertNull(upNextIdentifier("   "))
    }

    // ── the guard the change must NOT widen ───────────────────────────────

    @Test
    fun `a card for the next episode still survives, however the show is identified`() {
        val dropped = dropTrackerCardsForCompletedTargets(
            items = listOf(trackerCard(season = 2, episode = 9, title = "R.J. Decker")),
            completedRows = listOf(row(name = "RJ Decker")),
            tmdbByParentId = mapOf("tt10986410" to 97546)
        )
        assertEquals(
            "the TMDB badge strengthens show identity only; the episode pair decides",
            1,
            dropped.size
        )
    }

    @Test
    fun `a movie completion still drops its card on the title alone`() {
        val mark = completedTitleMark(
            parentId = "tt1160419",
            parentType = "movie",
            tmdbId = null,
            season = null,
            episode = null,
            title = "Dune Part Two."
        )!!
        assertTrue(
            trackerCardJustCompletedByProfile(
                trackerCard(
                    parentId = "tmdb:693134",
                    tmdbId = 693134,
                    title = "Dune Part Two",
                    parentType = "movie",
                    season = null,
                    episode = null
                ),
                listOf(mark)
            )
        )
    }

    private companion object {
        const val SHOW = "The Gentlemen"
    }
}
