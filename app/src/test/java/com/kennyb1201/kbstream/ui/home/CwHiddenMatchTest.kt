package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.library.HiddenTitleKeys
import com.kennyb1201.kbstream.data.reporting.CwFilterTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hidden-titles filter's identity on Continue Watching, against the real key
 * math.
 *
 * Field case (phone, 0.9.168 (5473), profile/1, 2026-11-05): PAW Patrol hidden,
 * absent from Library and Browse, and still on Continue Watching. The sync was
 * exonerated - Library proves the phone holds the key - and so was the filter,
 * because the rails twenty lines away dropped the same title from the same
 * `hiddenTitleKeys`. The miss was the identity the CW call handed the matcher:
 * the card's `(parentType, showTitle ?: title, parentId, id)` names a show the
 * way the rail does not.
 *
 * Three shapes can produce that, and the field could not say which, so all three
 * are pinned here - and each is asserted to MISS under the pre-fix identity,
 * which is the only way this test can prove it is testing the fix:
 *
 *  - (a) a resume card whose `showTitle` never resolved, so the call compared an
 *    EPISODE title;
 *  - (b) a series card with no declared type and no season, which the old
 *    `season != null` fallback matched as a MOVIE;
 *  - (c) a card whose TMDB show id is the only spelling the hide shares, which
 *    the call never offered.
 */
class CwHiddenMatchTest {

    /** One card's fields, for building the same case under both identities. */
    private data class Card(
        val title: String,
        val parentType: String? = null,
        val showTitle: String? = null,
        val episodeTitle: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val parentId: String? = null,
        val id: String = "history:row",
        val tmdbId: Int? = null,
        val historyRowId: String? = "row",
        val showNameFromHistory: String? = null
    )

    /** The hidden set the store builds for one hide: ids plus the name key. */
    private fun hiddenSet(
        mediaType: String,
        title: String,
        year: Int? = null,
        ids: List<String?> = emptyList()
    ): Set<String> =
        HiddenTitleKeys.keysFor(mediaType, ids) +
            setOfNotNull(HiddenTitleKeys.titleKey(mediaType, title, year))

    /** The identity this filter passed before the fix, verbatim. */
    private fun legacyHides(card: Card, hidden: Set<String>): Boolean =
        HiddenTitleKeys.hides(
            hidden,
            card.parentType ?: if (card.season != null) "series" else "movie",
            card.showTitle ?: card.title,
            null,
            card.parentId,
            card.id
        )

    private fun decision(card: Card, hidden: Set<String>): UpNextHiddenDecision =
        upNextHiddenDecision(
            hiddenKeys = hidden,
            parentType = card.parentType,
            title = card.title,
            showTitle = card.showTitle,
            episodeTitle = card.episodeTitle,
            season = card.season,
            episode = card.episode,
            parentId = card.parentId,
            id = card.id,
            tmdbId = card.tmdbId,
            historyRowId = card.historyRowId,
            showNameFromHistory = card.showNameFromHistory
        )

    // (a) A resume row whose season never resolved, built without showTitle.
    private val unresolvedShowTitle =
        Card(
            title = "Pups Save a Train",
            episodeTitle = "Pups Save a Train",
            parentId = "tt1234567:1:2",
            id = "history:row",
            historyRowId = "row",
            showNameFromHistory = "PAW Patrol"
        )

    // (b) A series card that declares no type and carries no season.
    private val undeclaredSeries =
        Card(
            title = "PAW Patrol",
            showTitle = "PAW Patrol",
            parentId = "tt1234567",
            id = "history:row"
        )

    // (c) An IMDb-flavoured parent id, with the TMDB show id only on tmdbId.
    private val episodeIdParentAndTmdb =
        Card(
            title = "Pups Save a Train",
            parentType = "series",
            episodeTitle = "Pups Save a Train",
            parentId = "tt1234567:1:2",
            id = "history:row",
            tmdbId = 7472
        )

    private val pawPatrolHidden =
        hiddenSet(
            mediaType = "series",
            title = "PAW Patrol",
            ids = listOf("tmdb:7472")
        )

    @Test
    fun `all three field shapes miss under the pre-fix identity and drop now`() {
        listOf(
            "an unresolved show title" to unresolvedShowTitle,
            "an undeclared type with no season" to undeclaredSeries,
            "a TMDB id the call never offered" to episodeIdParentAndTmdb
        ).forEach { (label, card) ->
            assertFalse(
                "the pre-fix identity must MISS ($label), or this pins nothing",
                legacyHides(card, pawPatrolHidden)
            )
            assertTrue(
                "the widened identity must drop the card ($label)",
                decision(card, pawPatrolHidden).hidden
            )
        }
    }

    @Test
    fun `a null show title is matched by the parent row's name, never the episode's`() {
        val decision = decision(unresolvedShowTitle, pawPatrolHidden)
        assertEquals(
            "the name leg is handed the SHOW's name",
            "PAW Patrol",
            decision.identity.title
        )
        assertEquals("the card is an episode, so it is a series", "series", decision.identity.mediaType)
    }

    @Test
    fun `a series card that declares no type is not matched as a movie`() {
        assertEquals("series", decision(undeclaredSeries, pawPatrolHidden).identity.mediaType)

        // ...and the type is what made the old call miss: the same card, with
        // the hide stored under "movie", is NOT hidden - the distinction is
        // kept, not widened away.
        val hiddenAsMovie =
            hiddenSet(mediaType = "movie", title = "PAW Patrol", ids = listOf("tmdb:7472"))
        assertFalse(
            "a series card must not be dropped by a movie's hide",
            decision(undeclaredSeries, hiddenAsMovie).hidden
        )
    }

    @Test
    fun `every id spelling the card carries is offered, in one call`() {
        val identity = decision(episodeIdParentAndTmdb, pawPatrolHidden).identity
        assertEquals(
            "parent, own id, the TMDB show id, and the history row",
            listOf("tt1234567:1:2", "history:row", "tmdb:7472", "row"),
            identity.ids
        )
        assertTrue(
            "the TMDB show id is what a rail hide is stored under",
            "series::tmdb:7472" in pawPatrolHidden
        )
    }

    @Test
    fun `a control card is kept, with the identity it always had`() {
        val bluey =
            Card(
                title = "Bluey",
                parentType = "series",
                showTitle = "Bluey",
                season = 2,
                episode = 5,
                parentId = "tt7678620",
                id = "history:bluey"
            )
        val decision = decision(bluey, pawPatrolHidden)
        assertFalse("a title nobody hid is not dropped", decision.hidden)
        assertEquals("series", decision.identity.mediaType)
        assertEquals("Bluey", decision.identity.title)
    }

    @Test
    fun `a movie is matched by its own title and its own type`() {
        val movie =
            Card(
                title = "PAW Patrol: The Movie",
                parentType = "movie",
                parentId = "tt11832046",
                id = "history:movie"
            )
        val movieHidden = hiddenSet("movie", "PAW Patrol: The Movie", year = 2021, ids = listOf("tt11832046"))
        assertTrue("a hidden movie is dropped by its own name/id", decision(movie, movieHidden).hidden)

        // The series hide does not reach the film: the name differs and the ids
        // are of different namespaces, so a kid's show hide cannot take a film
        // off the rail.
        assertFalse(decision(movie, pawPatrolHidden).hidden)
    }

    @Test
    fun `an episode-shaped parent id is a series signal on its own`() {
        val shapeOnly =
            Card(
                title = "Pups Save a Train",
                parentId = "tt1234567:3:7",
                id = "history:row"
            )
        assertEquals(
            "an id naming an episode cannot be a movie's",
            "series",
            decision(shapeOnly, pawPatrolHidden).identity.mediaType
        )
    }

    @Test
    fun `the decision line names the card, its identity, and the verdict`() {
        val hidden = decision(unresolvedShowTitle, pawPatrolHidden)
        assertEquals(
            "cw.filter: Pups Save a Train parentId=tt1234567:1:2 id=history:row " +
                "tmdbId=- type=series hidden=true",
            hidden.line
        )
        val kept = decision(
            Card(title = "Bluey", parentType = "series", showTitle = "Bluey", tmdbId = 91771),
            pawPatrolHidden
        )
        assertEquals(
            "cw.filter: Bluey parentId=- id=history:row tmdbId=91771 type=series hidden=false",
            kept.line
        )
    }

    @Test
    fun `the trace carries one rail's pass and replaces the previous one`() {
        CwFilterTrace.recordAll(listOf("cw.filter: first hidden=false"))
        assertEquals(listOf("cw.filter: first hidden=false"), CwFilterTrace.lines())

        val longPass = (1..CwFilterTrace.MAX_ENTRIES + 5).map { "cw.filter: card$it hidden=false" }
        CwFilterTrace.recordAll(longPass)
        assertEquals(
            "a report is not a log file: the pass is capped, not appended",
            CwFilterTrace.MAX_ENTRIES,
            CwFilterTrace.lines().size
        )
        assertEquals("cw.filter: card1 hidden=false", CwFilterTrace.lines().first())
        assertTrue(
            "the previous pass is gone",
            CwFilterTrace.lines().none { it.contains("first") }
        )
    }
}
