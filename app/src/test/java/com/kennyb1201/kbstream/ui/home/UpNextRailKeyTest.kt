package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Continue Watching rail is keyed by the SHOW, not by the winning leg.
 *
 * Field bug (2026-11-09, TCL TV, 0.9.208): two freezes that ended in a silent
 * return to the launcher, nothing in Sentry. The rail was keyed on
 * [UpNextItem.id], and the same show's id flips between legs across ordinary
 * rebuilds - The Simpsons `simkl:show-1218` <-> `nextup:tt0096697`, Universal
 * Basic Guys `simkl:show-2509096` <-> `nextup:tt23469464`, six more shows the
 * same afternoon. Compose read each flip as a different item, disposed the old
 * card and composed a new one, so every visible poster and backdrop was
 * requested from Coil and decoded again against a ~29 MB cache on a 192 MB
 * heap. The churn across the whole rail at once is what froze it and then got
 * the process LMK-killed.
 *
 * The property is proved here rather than by scrolling: [upNextRailKey] must
 * agree for any two cards the merge clusters together, and must stay unique
 * across a published rail (a duplicate key in a LazyRow throws - the
 * ANDROID-1D crash family).
 */
class UpNextRailKeyTest {

    private fun card(
        id: String,
        title: String = "The Simpsons",
        parentId: String? = "tt0096697",
        parentType: String = "series",
        tmdbId: Int? = null,
        season: Int? = 1,
        episode: Int? = 2,
        resumeSource: ResumeSource = ResumeSource.LOCAL,
        recencyTimestamp: Long = 1_000L
    ): UpNextItem = UpNextItem(
        id = id,
        title = title,
        poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
        badge = UpNextBadge.CONTINUE_WATCHING,
        parentId = parentId,
        parentType = parentType,
        season = season,
        episode = episode,
        tmdbId = tmdbId,
        resumeSource = resumeSource,
        recencyTimestamp = recencyTimestamp,
        progressPercent = 0.4f
    )

    // ── the field pairs the pastes saw flip ─────────────────────────────

    @Test
    fun `a show's two legs produce one rail key, whichever leg wins`() {
        // 15:32 vs 15:40 on the same TV and profile: the card id flipped
        // simkl -> nextup between two ordinary rebuilds.
        val simklLeg = card(
            id = "simkl:show-1218",
            parentId = "tmdb:456",
            tmdbId = 456,
            resumeSource = ResumeSource.SIMKL
        )
        val localLeg = card(
            id = "nextup:tt0096697",
            parentId = "tt0096697",
            tmdbId = 456
        )

        assertEquals(upNextRailKey(simklLeg), upNextRailKey(localLeg))
        assertEquals("parent:series:456", upNextRailKey(simklLeg))

        // The id itself is NOT the rail key, and is deliberately untouched:
        // `cw.filter` still prints the leg that won.
        assertNotEquals(simklLeg.id, localLeg.id)
        assertEquals("simkl:show-1218", simklLeg.id)
        assertEquals("nextup:tt0096697", localLeg.id)
    }

    @Test
    fun `the winner flip keeps the key stable through the real merge`() {
        val simklLeg = card(
            id = "simkl:show-2509096",
            title = "Universal Basic Guys",
            parentId = "tmdb:214087",
            tmdbId = 214087,
            resumeSource = ResumeSource.SIMKL
        )

        // Same show, rebuilt from its other leg with a newer touch, so the
        // merge publishes the local card instead. One card either way, and the
        // rail key that card carries must not move.
        val localLeg = card(
            id = "nextup:tt23469464",
            title = "Universal Basic Guys",
            parentId = "tt23469464",
            tmdbId = 214087
        )
            .copy(recencyTimestamp = 9_000L)

        val first = dedupeAndSortUpNext(listOf(simklLeg, localLeg))
        val second = dedupeAndSortUpNext(listOf(simklLeg.copy(recencyTimestamp = 9_000L), localLeg))

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(
            "the published card's key must survive the winner flipping",
            upNextRailKey(first.single()),
            upNextRailKey(second.single())
        )
    }

    // ── the property the merge relies on ────────────────────────────────

    @Test
    fun `cards the merge clusters together always share a rail key`() {
        val cards = listOf(
            // One show, four flavors of its id at once: a Simkl session, a
            // local next-up row, a TMDB-keyed row and a Trakt card.
            card(id = "simkl:show-1218", parentId = "tmdb:456", tmdbId = 456, resumeSource = ResumeSource.SIMKL),
            card(id = "nextup:tt0096697", parentId = "tt0096697", tmdbId = 456),
            card(id = "history:tmdb:456:1:1", parentId = "tmdb:456", tmdbId = 456),
            card(id = "trakt:456", parentId = "trakt:456", tmdbId = 456, resumeSource = ResumeSource.TRAKT),
            // A card whose enrichment has not landed yet: no tmdbId, only the
            // id flavor its own source files it under.
            card(id = "nextup:tt23469464", title = "Universal Basic Guys", parentId = "tt23469464"),
            card(
                id = "history:tmdb:214087:1:2",
                title = "Universal Basic Guys",
                parentId = "tmdb:214087",
                tmdbId = 214087
            ),
            // Two id-less cards that share a name - the merge groups them by
            // name, so the rail key must fall back to the name as well.
            card(id = "nextup:a", title = "Rooster", parentId = null),
            card(id = "nextup:b", title = "rooster!", parentId = null)
        )

        for (left in cards) {
            for (right in cards) {
                val clusters =
                    upNextGroupingKeys(left) intersect upNextGroupingKeys(right)

                if (clusters.isNotEmpty()) {
                    assertEquals(
                        "clustered cards must agree on the rail key: ${left.id} / ${right.id}",
                        upNextRailKey(left),
                        upNextRailKey(right)
                    )
                }
            }
        }
    }

    @Test
    fun `no published rail carries the same key twice`() {
        val rail = dedupeAndSortUpNext(
            listOf(
                // The Simpsons, three legs.
                card(id = "simkl:show-1218", parentId = "tmdb:456", tmdbId = 456),
                card(id = "nextup:tt0096697", parentId = "tt0096697", tmdbId = 456),
                card(id = "history:tmdb:456:1:1", parentId = "tmdb:456", tmdbId = 456),
                // Universal Basic Guys, two legs.
                card(
                    id = "simkl:show-2509096",
                    title = "Universal Basic Guys",
                    parentId = "tmdb:214087",
                    tmdbId = 214087
                ),
                card(
                    id = "nextup:tt23469464",
                    title = "Universal Basic Guys",
                    parentId = "tt23469464",
                    tmdbId = 214087
                ),
                // Two id-less titles: only their names can tell them apart,
                // and they are different shows.
                card(id = "nextup:c", title = "Animal Control", parentId = null),
                card(id = "nextup:d", title = "The Paper", parentId = null),
                // A movie shares its tmdb number space with nothing here, and
                // its media type is part of the key.
                card(
                    id = "nextup:e",
                    title = "Good Times",
                    parentId = "tt0072508",
                    parentType = "movie",
                    tmdbId = 456,
                    season = null,
                    episode = null
                )
            )
        )

        assertEquals(
            "every published card needs its own key or the LazyRow throws",
            rail.size,
            rail.map(::upNextRailKey).toSet().size
        )
        assertEquals(5, rail.size)
    }

    // ── the branches of the key ─────────────────────────────────────────

    @Test
    fun `a lagging enrichment still lands on the same key`() {
        // `parentId` "tmdb:456" normalizes to the bare number, which is the
        // key the resolved tmdbId emits - so a card that has not finished
        // resolving does not move the node it already occupies.
        val unresolved = card(id = "nextup:tmdb:456", parentId = "tmdb:456", tmdbId = null)
        val resolved = card(id = "nextup:tmdb:456", parentId = "tmdb:456", tmdbId = 456)

        assertEquals("parent:series:456", upNextRailKey(unresolved))
        assertEquals(upNextRailKey(resolved), upNextRailKey(unresolved))
    }

    @Test
    fun `a card with no id at all falls back to its title key`() {
        val noId = card(id = "nextup:x", title = "Forbidden History", parentId = null)

        // Exactly the fallback grouping uses for the same card, so two id-less
        // cards that the merge groups by name also agree on the rail key.
        assertEquals(upNextTitleKey(noId), upNextRailKey(noId))
        assertTrue(upNextRailKey(noId).startsWith("title:series:"))
    }

    @Test
    fun `the media type is part of the key`() {
        val series = card(id = "nextup:s", parentType = "series", tmdbId = 42, season = 1, episode = 1)
        val movie = card(id = "nextup:m", parentType = "movie", tmdbId = 42, season = null, episode = null)

        assertNotEquals(upNextRailKey(series), upNextRailKey(movie))
        assertTrue(upNextGroupingKeys(series).intersect(upNextGroupingKeys(movie)).isEmpty())
    }
}
