package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.data.library.TasteSignals
import com.kennyb1201.kbstream.data.library.isDisliked
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared recommendation blend's arithmetic and its watched rule.
 *
 * Both surfaces - Detail's "More Like This" and the player's "Because You
 * Watched" - pick through this engine, so its scoring, its ranking, its
 * per-tier cap and its watched predicate are pinned here as pure functions,
 * with no Android and no TMDB in the way. What a JUnit test cannot drive (the
 * live tiers, the repository reads, the two call sites) is pinned on the
 * sources by [RecommendationBlendWiringContractTest].
 *
 * The field report this engine's watched rule comes from: "watched titles are
 * NOT dropped from Because You Watched - they always show up". The old rule
 * resolved a title's IMDB id and compared it with the history rows, so a row
 * filed under the TMDB flavor never matched, and a resolve that failed read as
 * "unwatched" - [blendWatched] is the answer to both, and the cases below are
 * the three flavors it has to catch.
 */
class RecommendationBlendTest {

    // ── scoring ─────────────────────────────────────────────────────────

    @Test
    fun `a single-signal candidate scores exactly its tier weight`() {
        BlendTier.entries.forEach { tier ->
            assertEquals(
                "one tier agreeing is worth that tier's weight and nothing more",
                tier.weight,
                blendScore(candidate(1, setOf(tier)), emptySet())
            )
        }
    }

    @Test
    fun `a title several tiers agree on climbs above one only a single tier knows`() {
        val seedGenres = setOf(28)
        val franchiseOnly = candidate(1, setOf(BlendTier.FRANCHISE))
        val franchiseAndCreatives =
            candidate(2, setOf(BlendTier.FRANCHISE, BlendTier.CREATIVES))

        assertEquals(100 + 60, blendScore(franchiseAndCreatives, seedGenres))
        assertTrue(
            "the additive score is what makes the row read like a recommendation",
            blendScore(franchiseAndCreatives, seedGenres) > blendScore(franchiseOnly, seedGenres)
        )
        assertTrue(
            "creatives plus keywords beats creatives alone",
            blendScore(
                candidate(3, setOf(BlendTier.CREATIVES, BlendTier.KEYWORDS)),
                seedGenres
            ) > blendScore(candidate(4, setOf(BlendTier.CREATIVES)), seedGenres)
        )
    }

    @Test
    fun `the overlap bonuses cap at twenty, ten and ten`() {
        val two = listOf(person(1, "A", BlendCreditRole.DIRECTOR), person(2, "B", BlendCreditRole.CAST))
        val three = two + person(3, "C", BlendCreditRole.CAST)
        val four = three + person(4, "D", BlendCreditRole.CAST)

        assertEquals("two people is one bonus step", 10, blendScore(candidate(1, emptySet(), people = two), emptySet()))
        assertEquals(20, blendScore(candidate(2, emptySet(), people = three), emptySet()))
        assertEquals("four people is still two steps", 20, blendScore(candidate(3, emptySet(), people = four), emptySet()))

        assertEquals(5, blendScore(candidate(4, emptySet(), keywords = listOf(1, 2)), emptySet()))
        assertEquals(10, blendScore(candidate(5, emptySet(), keywords = listOf(1, 2, 3)), emptySet()))
        assertEquals(10, blendScore(candidate(6, emptySet(), keywords = listOf(1, 2, 3, 4, 5)), emptySet()))

        val seed = setOf(1, 2, 3, 4)
        assertEquals(5, blendScore(candidate(7, emptySet(), genres = listOf(1, 2)), seed))
        assertEquals(10, blendScore(candidate(8, emptySet(), genres = listOf(1, 2, 3)), seed))
        assertEquals(10, blendScore(candidate(9, emptySet(), genres = listOf(1, 2, 3, 4)), seed))
    }

    @Test
    fun `unknown genres score no overlap and are never a penalty`() {
        val seed = setOf(1, 2)
        listOf(null, emptyList(), listOf(9)).forEach { genres ->
            assertEquals(
                "unknown is not 'neither': a genre-less candidate loses nothing",
                0,
                blendScore(candidate(1, emptySet(), genres = genres), seed)
            )
        }
    }

    // ── ranking ─────────────────────────────────────────────────────────

    @Test
    fun `ranking is score first, then the stronger tier, then source order`() {
        // The same total score (115) reached two different ways: the candidate
        // whose STRONGEST tier is heavier comes first.
        val franchisePlusKeyword = candidate(1, setOf(BlendTier.FRANCHISE, BlendTier.SIMILAR))
        val creativesPlusTwo = candidate(
            2,
            setOf(BlendTier.CREATIVES, BlendTier.RECOMMENDATIONS, BlendTier.KEYWORDS)
        )
        assertEquals(
            blendScore(franchisePlusKeyword, emptySet()),
            blendScore(creativesPlusTwo, emptySet())
        )
        assertEquals(
            listOf(1, 2),
            blendRank(listOf(creativesPlusTwo, franchisePlusKeyword), emptySet())
                .map { it.pick.tmdbId }
        )

        // Equal score, equal tier: first seen wins.
        val later = candidate(3, setOf(BlendTier.CREATIVES), order = 5)
        val earlier = candidate(4, setOf(BlendTier.CREATIVES), order = 1)
        assertEquals(
            listOf(4, 3),
            blendRank(listOf(later, earlier), emptySet()).map { it.pick.tmdbId }
        )
    }

    @Test
    fun `the similar tier can never outrank recommendations at equal membership`() {
        val recommended = candidate(1, setOf(BlendTier.RECOMMENDATIONS))
        val similar = candidate(2, setOf(BlendTier.SIMILAR))

        assertEquals(
            "30 beats 15, so the fill tier stays behind TMDB's own list",
            listOf(1, 2),
            blendRank(listOf(similar, recommended), emptySet()).map { it.pick.tmdbId }
        )
    }

    // ── selection ───────────────────────────────────────────────────────

    @Test
    fun `the per-tier cap keeps a franchise-heavy row from becoming all saga`() {
        val pool = (1..4).map { candidate(it, setOf(BlendTier.FRANCHISE), order = it - 1) } +
            (5..12).map { candidate(it, setOf(BlendTier.CREATIVES), order = it) }

        val picked = blendSelect(blendRank(pool, emptySet()), 7)

        assertEquals(4, picked.count { BlendTier.FRANCHISE in it.tiers })
        assertEquals("the row still fills", 7, picked.size)
    }

    @Test
    fun `the second pass fills the row when one tier is all there is`() {
        val allFranchise = (1..10).map { candidate(it, setOf(BlendTier.FRANCHISE), order = it) }

        val picked = blendSelect(blendRank(allFranchise, emptySet()), 7)

        assertEquals(
            "a cap must never shorten a row while candidates remain",
            7,
            picked.size
        )
    }

    @Test
    fun `selection never pads a row past its candidates`() {
        val two = listOf(
            candidate(1, setOf(BlendTier.CREATIVES)),
            candidate(2, setOf(BlendTier.CREATIVES))
        )

        assertEquals(2, blendSelect(blendRank(two, emptySet()), 7).size)
        assertTrue("and nothing at all comes back for an empty pool", blendSelect(emptyList(), 7).isEmpty())
        assertTrue("a zero-pick surface picks nothing", blendSelect(blendRank(two, emptySet()), 0).isEmpty())
    }

    // ── the watched rule (the field report) ─────────────────────────────

    @Test
    fun `a tmdb-flavored history row drops the candidate with no resolve at all`() {
        assertTrue(
            "a row filed by a TMDB route is the flavor no lookup is needed for",
            blendWatched(
                candidateTmdbId = 603,
                mediaType = "movie",
                imdbId = null,
                localHistory = setOf("tmdb:603"),
                reverseHistoryTmdbIds = emptySet(),
                repoWatchedKeys = emptySet(),
                repoPartialKeys = emptySet()
            )
        )
        assertTrue(
            "the bare-digit spelling too",
            blendWatched(603, "movie", null, setOf("603"), emptySet(), emptySet(), emptySet())
        )
    }

    @Test
    fun `an imdb-flavored history row drops it through either direction`() {
        assertTrue(
            "forward: the candidate's own resolved id is in the history",
            blendWatched(603, "movie", "tt0133093", setOf("tt0133093"), emptySet(), emptySet(), emptySet())
        )
        assertTrue(
            "reverse: the history row's tt id resolves to this candidate",
            blendWatched(603, "movie", null, setOf("tt0133093"), setOf(603), emptySet(), emptySet())
        )
    }

    @Test
    fun `the merged tracker and menu state drops it under either flavor`() {
        val tmdbKey = WatchedStatusKeys.key("tmdb:603", "movie")

        assertTrue(
            "a title completed on Simkl/Trakt/MDBList is watched even with no history row",
            blendWatched(603, "movie", null, emptySet(), emptySet(), setOf(tmdbKey), emptySet())
        )
        assertTrue(
            "and a started (eye) title counts the same way, as it always has",
            blendWatched(603, "movie", null, emptySet(), emptySet(), emptySet(), setOf(tmdbKey))
        )
        assertTrue(
            "the IMDB flavor of the same key works too",
            blendWatched(
                603,
                "movie",
                "tt0133093",
                emptySet(),
                emptySet(),
                setOf(WatchedStatusKeys.key("tt0133093", "movie")),
                emptySet()
            )
        )
    }

    @Test
    fun `a lookup that answered nothing never reads as watched`() {
        assertFalse(
            blendWatched(603, "movie", null, emptySet(), emptySet(), emptySet(), emptySet())
        )
        assertFalse(
            "another title's state must not be mistaken for this one's",
            blendWatched(
                603,
                "movie",
                null,
                setOf("tmdb:604"),
                setOf(604),
                setOf(WatchedStatusKeys.key("tmdb:604", "movie")),
                emptySet()
            )
        )
    }

    // ── the reason label ────────────────────────────────────────────────

    @Test
    fun `the reason names the signal that actually matched`() {
        assertEquals(
            "Same series",
            blendReason(candidate(1, setOf(BlendTier.FRANCHISE)), seedPeople, "Seed")
        )
        assertEquals(
            "Directed by A Director",
            blendReason(
                candidate(2, setOf(BlendTier.CREATIVES), people = listOf(seedPeople[0])),
                seedPeople,
                "Seed"
            )
        )
        assertEquals(
            "Written by A Writer",
            blendReason(
                candidate(3, setOf(BlendTier.CREATIVES), people = listOf(seedPeople[1])),
                seedPeople,
                "Seed"
            )
        )
        assertEquals(
            "Starring A Star",
            blendReason(
                candidate(4, setOf(BlendTier.CREATIVES), people = listOf(seedPeople[2])),
                seedPeople,
                "Seed"
            )
        )
        assertEquals(
            "Similar theme: heist",
            blendReason(
                candidate(
                    5,
                    setOf(BlendTier.KEYWORDS),
                    keywords = listOf(7),
                    keywordNames = listOf("heist")
                ),
                seedPeople,
                "Seed"
            )
        )
        assertEquals(
            "Similar to Seed",
            blendReason(candidate(6, setOf(BlendTier.SIMILAR)), seedPeople, "Seed")
        )
    }

    @Test
    fun `the director is named when a pick matched more than one person`() {
        assertEquals(
            "precedence is director, then writer, then cast - not match order",
            "Directed by A Director",
            blendReason(
                candidate(
                    1,
                    setOf(BlendTier.CREATIVES),
                    people = listOf(seedPeople[2], seedPeople[0])
                ),
                seedPeople,
                "Seed"
            )
        )
    }

    // ── taste: the one rule a single-seed blend applies ──────────────────

    /**
     * A title the viewer thumbed down is never recommended back to them. The
     * DROP itself sits in the pipeline (it needs the live tiers, so its wiring is
     * pinned on the source); the rule it filters with is pure, and this is it.
     *
     * The snapshot is built through [TasteSignals.keysFor], which is what the
     * store actually writes: the id spellings the signalling surface knew PLUS
     * the name key. That is how a dislike recorded on an add-on rail - which only
     * knows `tt...` - still drops the TMDB candidate for the same title, and the
     * other way round.
     */
    @Test
    fun `a disliked candidate is recognised under either id flavor`() {
        val fromAddonRail = TasteSignals.Snapshot(
            disliked = TasteSignals.keysFor("movie", listOf("tt0137523"), "Fight Club", 1999)
        )
        assertTrue(
            "a dislike recorded from an add-on rail has to drop the TMDB candidate",
            fromAddonRail.isDisliked(
                mediaType = "movie",
                title = "Fight Club",
                year = 1999,
                tmdbId = 603
            )
        )

        val fromTmdbRail = TasteSignals.Snapshot(
            disliked = TasteSignals.keysFor("movie", listOf("tmdb:603"), "Fight Club", 1999)
        )
        assertTrue(
            "and a dislike recorded from a TMDB rail has to drop the add-on one",
            fromTmdbRail.isDisliked(
                mediaType = "movie",
                title = "Fight Club",
                year = 1999,
                tmdbId = null,
                imdbId = "tt0137523"
            )
        )

        assertFalse(
            "an unrelated title is untouched",
            fromAddonRail.isDisliked(
                mediaType = "movie",
                title = "Se7en",
                year = 1995,
                tmdbId = 807
            )
        )
    }

    @Test
    fun `an empty or liked taste set drops nothing`() {
        assertTrue("a default snapshot says nothing", TasteSignals.Snapshot().isEmpty)
        assertFalse(
            "no signals means the blend's own rules stand, unchanged",
            TasteSignals.Snapshot().isDisliked("movie", "Fight Club", 1999, 603)
        )
        assertFalse(
            "and a LIKE is not a dislike: single-seed blends do not boost on it",
            TasteSignals.Snapshot(liked = TasteSignals.keysFor("movie", listOf("tmdb:603"), "Fight Club", 1999))
                .isDisliked("movie", "Fight Club", 1999, 603)
        )
    }

    // ── the multi-seed merge (Home's Top Picks row) ──────────────────────

    @Test
    fun `the merge interleaves the seeds instead of letting one fill the row`() {
        val seedOne = listOf(pick(1), pick(2), pick(3))
        val seedTwo = listOf(pick(11), pick(12), pick(13))
        val seedThree = listOf(pick(21), pick(22), pick(23))

        assertEquals(
            "seed order decides the ties: 1's first, then 2's, then 3's, then round again",
            listOf(1, 11, 21, 2, 12, 22, 3, 13, 23),
            mergeTopPicksSeeds(listOf(seedOne, seedTwo, seedThree), perSeedCap = 6, rowCap = 18)
                .map { it.tmdbId }
        )
    }

    @Test
    fun `a title two seeds both rank appears once, under the first seed that ranked it`() {
        val seedOne = listOf(pick(1), pick(2))
        val seedTwo = listOf(pick(2), pick(3))

        assertEquals(
            "the second copy is skipped, and does not consume the later seed's own cap",
            listOf(1, 2, 3),
            mergeTopPicksSeeds(listOf(seedOne, seedTwo), perSeedCap = 6, rowCap = 18)
                .map { it.tmdbId }
        )
    }

    @Test
    fun `the dedupe asks both flavors, not the TMDB id alone`() {
        // The same title arrives under `tmdb:<n>` from one seed's blend and a
        // resolved `tt...` from another's: one card, not two posters of it.
        val seedOne = listOf(pick(1, imdb = "tt1"))
        val seedTwo = listOf(pick(9, imdb = "tt1"))

        assertEquals(
            listOf(1),
            mergeTopPicksSeeds(listOf(seedOne, seedTwo), perSeedCap = 6, rowCap = 18)
                .map { it.tmdbId }
        )
    }

    @Test
    fun `the per-seed cap and the row cap both bind`() {
        val seedOne = (1..9).map { pick(it) }
        val seedTwo = (101..109).map { pick(it) }

        val capped = mergeTopPicksSeeds(listOf(seedOne, seedTwo), perSeedCap = 2, rowCap = 18)
        assertEquals(
            "two per seed is four cards, interleaved",
            listOf(1, 101, 2, 102),
            capped.map { it.tmdbId }
        )

        val shortRow = mergeTopPicksSeeds(listOf(seedOne, seedTwo), perSeedCap = 6, rowCap = 5)
        assertEquals("the row stops at its cap", 5, shortRow.size)
        assertEquals(
            "and stops interleaving, not draining one seed",
            listOf(1, 101, 2, 102, 3),
            shortRow.map { it.tmdbId }
        )
    }

    @Test
    fun `nothing to merge is nothing, not a placeholder`() {
        assertTrue(mergeTopPicksSeeds(emptyList(), perSeedCap = 6, rowCap = 18).isEmpty())
        assertTrue(
            "a row whose seeds all answered nothing stays empty",
            mergeTopPicksSeeds(listOf(emptyList(), emptyList()), perSeedCap = 6, rowCap = 18)
                .isEmpty()
        )
        assertTrue(
            "and a zero cap is never padded",
            mergeTopPicksSeeds(listOf(listOf(pick(1))), perSeedCap = 0, rowCap = 18).isEmpty()
        )
    }

    @Test
    fun `the completed-only mode is a mode of its own`() {
        // Which watched set a mode consults is decided inside
        // buildRecommendationBlend (the partial set is emptied for
        // DROP_COMPLETED and consulted for every other dropping mode), so what
        // is pinned here is that the mode exists and is distinct: a surface
        // that drops PARTIAL titles must not be able to ask for this one by
        // accident.
        val modes = BlendWatchedMode.entries.map { it.name }
        assertEquals(3, modes.size)
        assertTrue(modes.contains("DROP_COMPLETED"))
        assertTrue(
            "DROP_COMPLETED must not be spelled as one of the other two",
            BlendWatchedMode.DROP_COMPLETED != BlendWatchedMode.DROP &&
                BlendWatchedMode.DROP_COMPLETED != BlendWatchedMode.DEMOTE
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** One blended pick, for the merge's own cases. */
    private fun pick(id: Int, imdb: String? = null) = BlendPick(
        tmdbId = id,
        type = "movie",
        name = "Title $id",
        posterPath = "poster$id",
        imdbId = imdb
    )

    private val seedPeople = listOf(
        person(10, "A Director", BlendCreditRole.DIRECTOR),
        person(11, "A Writer", BlendCreditRole.WRITER),
        person(12, "A Star", BlendCreditRole.CAST)
    )

    private fun person(id: Int, name: String, role: BlendCreditRole) =
        BlendPerson(id = id, name = name, role = role)

    private fun candidate(
        id: Int,
        tiers: Set<BlendTier>,
        order: Int = id,
        genres: List<Int>? = null,
        people: List<BlendPerson> = emptyList(),
        keywords: List<Int> = emptyList(),
        keywordNames: List<String> = emptyList()
    ) = BlendCandidate(
        pick = BlendPick(
            tmdbId = id,
            type = "movie",
            name = "Title $id",
            posterPath = "poster$id"
        ),
        score = 0,
        order = order,
        genreIds = genres,
        tiers = tiers,
        people = people,
        keywordIds = keywords,
        keywordNames = keywordNames
    )
}
