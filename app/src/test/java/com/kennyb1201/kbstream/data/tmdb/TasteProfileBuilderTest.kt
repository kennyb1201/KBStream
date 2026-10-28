package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.data.library.TasteSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The affinity profile's arithmetic and what it does to a blend.
 *
 * The profile is the layer between the taste signals and the recommendation
 * blend: signals say "I liked this title", the profile says "I lean toward this
 * director / genre / theme", and the blend spends that as one bounded bonus per
 * candidate. All of it is pure, so all of it is pinned here - the store's own
 * reads and the three surfaces are pinned on the sources by
 * [TasteProfileWiringContractTest], because no JVM test can drive a device.
 *
 * The three rules worth stating up front, because they are the ones a later
 * change is most likely to break silently:
 *
 *  - a dimension inside +/-3 is noise and buys nothing, a weak band pulls a
 *    little, a strong band pulls hard;
 *  - the bonus demotes but never drops: a negative total is floored at zero;
 *  - a build's own seed is left out of its snapshot, so a seed's taste cannot
 *    double-pay itself through the blend's overlap bonus.
 */
class TasteProfileBuilderTest {

    // ── the arithmetic ──────────────────────────────────────────────────

    @Test
    fun `a like is worth six to every dimension it carries`() {
        val profile = profileOf(
            evidence(1, TasteSignals.LIKE, detail = detail(1, directors = listOf(10), genres = listOf(28), keywords = listOf(5)))
        )
        assertEquals(6, profile.entry(AFFINITY_PERSON_DIRECTOR, 10)?.score)
        assertEquals(6, profile.entry(AFFINITY_GENRE, 28)?.score)
        assertEquals(6, profile.entry(AFFINITY_KEYWORD, 5)?.score)
        assertEquals("Director 10", profile.entry(AFFINITY_PERSON_DIRECTOR, 10)?.displayName)
        assertEquals(1, profile.entry(AFFINITY_GENRE, 28)?.likeCount)
        assertEquals(0, profile.entry(AFFINITY_GENRE, 28)?.completionCount)
    }

    @Test
    fun `a completion is a third of a like, and a bulk mark half of that`() {
        val profile = profileOf(
            // The profile needs one explicit signal to exist at all.
            evidence(1, TasteSignals.LIKE, detail = detail(1, genres = listOf(99))),
            evidence(2, completed = true, detail = detail(2, genres = listOf(28))),
            evidence(3, completed = true, marked = true, detail = detail(3, genres = listOf(35)))
        )
        assertEquals(AFFINITY_PLAYBACK_COMPLETION, profile.entry(AFFINITY_GENRE, 28)?.score)
        assertEquals(AFFINITY_MARKED_COMPLETION, profile.entry(AFFINITY_GENRE, 35)?.score)
        assertEquals(1, profile.entry(AFFINITY_GENRE, 28)?.completionCount)
        assertEquals(0, profile.entry(AFFINITY_GENRE, 28)?.likeCount)
    }

    @Test
    fun `a dislike mirrors a like, so a flip swings the dimension by twelve`() {
        val liked = profileOf(evidence(1, TasteSignals.LIKE, detail = detail(1, genres = listOf(28))))
        val disliked =
            profileOf(evidence(1, TasteSignals.DISLIKE, detail = detail(1, genres = listOf(28))))

        assertEquals(AFFINITY_LIKE, liked.entry(AFFINITY_GENRE, 28)?.score)
        assertEquals(AFFINITY_DISLIKE, disliked.entry(AFFINITY_GENRE, 28)?.score)
        assertEquals(
            "one title, one contribution set: the flip is not both signals at once",
            -12,
            (disliked.entry(AFFINITY_GENRE, 28)?.score ?: 0) -
                (liked.entry(AFFINITY_GENRE, 28)?.score ?: 0)
        )
        assertEquals(1, disliked.entry(AFFINITY_GENRE, 28)?.dislikeCount)
        assertEquals(0, disliked.entry(AFFINITY_GENRE, 28)?.likeCount)
    }

    @Test
    fun `a title both liked and completed contributes eight`() {
        val profile = profileOf(
            evidence(1, TasteSignals.LIKE, completed = true, detail = detail(1, genres = listOf(28)))
        )
        assertEquals(8, profile.entry(AFFINITY_GENRE, 28)?.score)
        assertEquals(1, profile.entry(AFFINITY_GENRE, 28)?.likeCount)
        assertEquals(1, profile.entry(AFFINITY_GENRE, 28)?.completionCount)
    }

    @Test
    fun `a disliked completion contributes only the dislike`() {
        val profile = profileOf(
            evidence(1, TasteSignals.DISLIKE, completed = true, detail = detail(1, genres = listOf(28)))
        )
        assertEquals(
            "the explicit verdict supersedes the implicit one for that title",
            AFFINITY_DISLIKE,
            profile.entry(AFFINITY_GENRE, 28)?.score
        )
        assertEquals(0, profile.entry(AFFINITY_GENRE, 28)?.completionCount)
    }

    @Test
    fun `the score clamps at thirty while the evidence keeps accruing`() {
        val profile = profileOf(
            (1..5).map { id ->
                evidence(id, TasteSignals.LIKE, detail = detail(id, genres = listOf(28)))
            }
        )
        val entry = profile.entry(AFFINITY_GENRE, 28)!!
        assertEquals(AFFINITY_SCORE_CAP, entry.score)
        assertEquals(
            "counts are the merge/diagnostic record, not the score",
            5,
            entry.likeCount
        )
    }

    @Test
    fun `one title counts once per dimension key, however often the payload lists it`() {
        val profile = profileOf(
            evidence(
                1,
                TasteSignals.LIKE,
                detail = detail(1, directors = listOf(10, 10), keywords = listOf(5, 5))
            )
        )
        assertEquals(AFFINITY_LIKE, profile.entry(AFFINITY_PERSON_DIRECTOR, 10)?.score)
        assertEquals(AFFINITY_LIKE, profile.entry(AFFINITY_KEYWORD, 5)?.score)
        assertEquals(1, profile.entry(AFFINITY_KEYWORD, 5)?.likeCount)
    }

    @Test
    fun `a person's roles are separate entries`() {
        val profile = profileOf(
            evidence(
                1,
                TasteSignals.LIKE,
                detail = detail(1, directors = listOf(10), cast = listOf(20))
            )
        )
        assertEquals(6, profile.entry(AFFINITY_PERSON_DIRECTOR, 10)?.score)
        assertNull(
            "a director affinity is not an acting affinity for the same person",
            profile.entry(AFFINITY_PERSON_CAST, 10)
        )
        assertNull(profile.entry(AFFINITY_PERSON_DIRECTOR, 20))
        assertEquals(6, profile.entry(AFFINITY_PERSON_CAST, 20)?.score)
    }

    @Test
    fun `only the top three billed cast count`() {
        val profile = profileOf(
            evidence(1, TasteSignals.LIKE, detail = detail(1, cast = listOf(20, 21, 22, 23)))
        )
        listOf(20, 21, 22).forEach { id ->
            assertEquals(6, profile.entry(AFFINITY_PERSON_CAST, id)?.score)
        }
        assertNull("the fourth billed credit is not the profile's business", profile.entry(AFFINITY_PERSON_CAST, 23))
    }

    // ── evidence cap, entry caps, cold start ────────────────────────────

    @Test
    fun `the oldest evidence falls out at sixty titles`() {
        val profile = profileOf(
            (1..AFFINITY_EVIDENCE_CAP + 1).map { id ->
                evidence(
                    id,
                    TasteSignals.LIKE,
                    stamp = id.toLong(),
                    detail = detail(id, genres = listOf(28))
                )
            }
        )
        assertEquals(
            "newest sixty only: the sixty-first title is not profile evidence",
            AFFINITY_EVIDENCE_CAP,
            profile.entry(AFFINITY_GENRE, 28)?.likeCount
        )
    }

    @Test
    fun `a dimension keeps its weakest entries out, oldest first on a tie`() {
        var stamp = 0L
        val profile = profileOf(
            buildList {
                // The two-like director outranks every one-like one.
                add(
                    evidence(
                        100,
                        TasteSignals.LIKE,
                        stamp = ++stamp,
                        detail = detail(100, directors = listOf(1))
                    )
                )
                add(
                    evidence(
                        101,
                        TasteSignals.LIKE,
                        stamp = ++stamp,
                        detail = detail(101, directors = listOf(1))
                    )
                )
                (2..AFFINITY_PERSON_DIMENSION_CAP + 2).forEach { personId ->
                    add(
                        evidence(
                            1000 + personId,
                            TasteSignals.LIKE,
                            stamp = ++stamp,
                            detail = detail(1000 + personId, directors = listOf(personId))
                        )
                    )
                }
            }
        )
        val directors = profile.entries[AFFINITY_PERSON_DIRECTOR].orEmpty()
        assertEquals(AFFINITY_PERSON_DIMENSION_CAP, directors.size)
        assertTrue("the strongest entry survives pruning", directors.containsKey("1"))
        assertEquals(12, directors["1"]?.score)
        assertFalse(
            "and the oldest of the weakest go first: persons 2 and 3 carried one like each",
            directors.containsKey("2") || directors.containsKey("3")
        )
        assertTrue(directors.containsKey("4"))
    }

    @Test
    fun `no explicit signal is no profile, however much was watched`() {
        val completions = buildList {
            (1..10).forEach { id ->
                add(evidence(id, completed = true, detail = detail(id, genres = listOf(28))))
            }
        }
        assertTrue(
            "completions alone duplicate the seed order and would be steered by bulk marks",
            buildTasteProfile(completions).isEmpty
        )
        assertTrue(buildTasteProfile(emptyList()).isEmpty)
    }

    @Test
    fun `a cache miss contributes nothing`() {
        val profile = profileOf(
            evidence(1, TasteSignals.LIKE, detail = detail(1, genres = listOf(28))),
            evidence(2, TasteSignals.LIKE, detail = null)
        )
        assertEquals(1, profile.entries[AFFINITY_GENRE]?.size)
        assertEquals(
            "the miss gave no dimension, so it is not evidence anything was left out of",
            listOf("tmdb:movie:1"),
            profile.evidence
        )
    }

    // ── the bonus ───────────────────────────────────────────────────────

    @Test
    fun `an empty profile and no profile at all are the same build`() {
        val candidates = listOf(
            candidate(1, setOf(BlendTier.CREATIVES), genres = listOf(28), people = listOf(person(10))),
            candidate(2, setOf(BlendTier.RECOMMENDATIONS), genres = listOf(35)),
            candidate(3, setOf(BlendTier.KEYWORDS), keywords = listOf(5))
        )
        val seedGenres = setOf(28)
        val without = blendRank(candidates, seedGenres)
        val empty = blendRank(candidates, seedGenres) { affinityBonus(it, TasteProfile.EMPTY) }

        assertEquals(
            "same picks, same order",
            without.map { it.pick.tmdbId },
            empty.map { it.pick.tmdbId }
        )
        candidates.forEach { candidate ->
            assertEquals(
                "and the same score",
                blendTotalScore(candidate, seedGenres),
                blendTotalScore(candidate, seedGenres) { affinityBonus(it, TasteProfile.EMPTY) }
            )
            assertEquals(0, affinityBonus(candidate, null))
            assertEquals(0, affinityBonus(candidate, TasteProfile.EMPTY))
        }
        assertEquals(
            "and the same reason lines",
            without.map { blendReason(it, emptyList(), "Seed") },
            empty.map { blendReason(it, emptyList(), "Seed") }
        )
    }

    @Test
    fun `bands steer by strength and the noise band buys nothing`() {
        fun bonusFor(score: Int): Int {
            val profile = profileWith(directorScore = score)
            return affinityBonus(
                candidate(1, emptySet(), people = listOf(person(10))),
                profile
            )
        }
        assertEquals("one like is one weak step", 0, bonusFor(0))
        assertEquals("three - a completion and a mark - is still noise", 0, bonusFor(3))
        assertEquals(AFFINITY_PERSON_BONUS_WEAK, bonusFor(AFFINITY_WEAK_FLOOR))
        assertEquals(AFFINITY_PERSON_BONUS_WEAK, bonusFor(11))
        assertEquals(AFFINITY_PERSON_BONUS_STRONG, bonusFor(AFFINITY_STRONG_FLOOR))
        assertEquals(-AFFINITY_PERSON_BONUS_WEAK, bonusFor(-AFFINITY_WEAK_FLOOR))
        assertEquals(-AFFINITY_PERSON_BONUS_STRONG, bonusFor(-AFFINITY_STRONG_FLOOR))
    }

    @Test
    fun `a candidate collects one bonus of each kind and never more than twenty`() {
        val profile = profileWith(directorScore = 12, keywordScore = 12, genreScore = 12)
        val candidate = candidate(
            1,
            setOf(BlendTier.CREATIVES),
            genres = listOf(28, 35),
            people = listOf(person(10), person(11)),
            keywords = listOf(5, 6)
        )
        assertEquals(
            "two strong people do not stack, and the sum is the cap",
            AFFINITY_BONUS_CAP,
            affinityBonus(candidate, profile)
        )
    }

    @Test
    fun `a negative affinity demotes but never drops or empties`() {
        val profile = profileWith(directorScore = -12)
        val strong = candidate(1, setOf(BlendTier.CREATIVES), people = listOf(person(10)))
        val bare = candidate(2, emptySet(), people = listOf(person(10)))

        assertEquals(-AFFINITY_PERSON_BONUS_STRONG, affinityBonus(strong, profile))
        assertEquals(
            "sunk by ten, still in the row",
            BlendTier.CREATIVES.weight - AFFINITY_PERSON_BONUS_STRONG,
            blendTotalScore(strong, emptySet()) { affinityBonus(it, profile) }
        )
        assertEquals(
            "a demotion is floored at zero, never a negative score",
            0,
            blendTotalScore(bare, emptySet()) { affinityBonus(it, profile) }
        )
    }

    @Test
    fun `role decides whether a person affinity fires`() {
        val profile = profileWith(directorScore = 12)
        assertEquals(
            AFFINITY_PERSON_BONUS_STRONG,
            affinityBonus(candidate(1, emptySet(), people = listOf(person(10, BlendCreditRole.DIRECTOR))), profile)
        )
        assertEquals(
            "a director affinity never boosts that person's acting credits",
            0,
            affinityBonus(candidate(1, emptySet(), people = listOf(person(10, BlendCreditRole.CAST))), profile)
        )
    }

    @Test
    fun `a genre affinity steers order silently, with no label invented`() {
        val profile = profileWith(directorScore = 0, genreScore = 12)
        val candidate = candidate(1, setOf(BlendTier.RECOMMENDATIONS), genres = listOf(28))
        assertEquals(AFFINITY_THEME_BONUS_STRONG, affinityBonus(candidate, profile))
        assertEquals(
            "the fallback reason stands: a genre has no name on a candidate",
            "Similar to Seed",
            blendReason(candidate, emptyList(), "Seed")
        )
    }

    // ── leave the seeds out ─────────────────────────────────────────────

    @Test
    fun `a seed's own evidence cannot boost the seed's own neighbourhood`() {
        val profile = profileOf(
            evidence(1, TasteSignals.LIKE, detail = detail(1, directors = listOf(10))),
            evidence(2, TasteSignals.LIKE, detail = detail(2, directors = listOf(10)))
        )
        assertEquals(12, profile.entry(AFFINITY_PERSON_DIRECTOR, 10)?.score)

        val seededByEvidence = profile.withoutEvidence(setOf("tmdb:movie:1"))!!
        assertEquals(
            "one like left: the seed's own lifted the dimension to weak only",
            AFFINITY_LIKE,
            seededByEvidence.entry(AFFINITY_PERSON_DIRECTOR, 10)?.score
        )
        assertEquals(1, seededByEvidence.entry(AFFINITY_PERSON_DIRECTOR, 10)?.likeCount)

        val seededElsewhere = profile.withoutEvidence(setOf("tmdb:movie:99"))!!
        assertEquals(12, seededElsewhere.entry(AFFINITY_PERSON_DIRECTOR, 10)?.score)

        val candidate = candidate(1, setOf(BlendTier.CREATIVES), people = listOf(person(10)))
        assertEquals(
            "the same candidate in a build seeded by other evidence gets the full pull",
            AFFINITY_PERSON_BONUS_STRONG,
            affinityBonus(candidate, seededElsewhere)
        )
        assertEquals(
            AFFINITY_PERSON_BONUS_WEAK,
            affinityBonus(candidate, seededByEvidence)
        )
    }

    @Test
    fun `an entry whose evidence was all the seed is dropped, not left at zero`() {
        val profile = profileOf(
            evidence(1, TasteSignals.LIKE, detail = detail(1, genres = listOf(28)))
        )
        val leftOut = profile.withoutEvidence(setOf("tmdb:movie:1"))!!
        assertNull(leftOut.entry(AFFINITY_GENRE, 28))
        assertTrue(leftOut.isEmpty)
        assertTrue(leftOut.evidence.isEmpty())
    }

    // ── cap interaction ─────────────────────────────────────────────────

    @Test
    fun `the bonus reorders inside the cap and never changes the tier`() {
        val profile = profileWith(genreScore = 12)
        val first = candidate(1, setOf(BlendTier.FRANCHISE), order = 1, genres = listOf(35))
        val second = candidate(2, setOf(BlendTier.FRANCHISE), order = 2, genres = listOf(28))
        val ranked = blendRank(listOf(first, second), setOf(35)) { affinityBonus(it, profile) }

        assertEquals(
            "the affinity's candidate climbs",
            listOf(2, 1),
            ranked.map { it.pick.tmdbId }
        )
        ranked.forEach { candidate ->
            assertEquals(
                "the profile is not tier membership",
                BlendTier.FRANCHISE,
                candidate.strongestTier()
            )
        }
        assertEquals(
            "and the per-tier cap still applies to the tier the candidate is already in",
            listOf(2, 1),
            blendSelect(ranked, pickCount = 2).map { it.pick.tmdbId }
        )
    }

    @Test
    fun `ranking with a profile still breaks ties on the stronger tier`() {
        val profile = profileWith(genreScore = 12)
        // 60 + 30 + 15 = 105, no affinity match.
        val manyTiers = candidate(
            1,
            setOf(BlendTier.CREATIVES, BlendTier.RECOMMENDATIONS, BlendTier.SIMILAR),
            order = 1,
            genres = listOf(35)
        )
        // 100 + the affinity's 5 = 105 too, and it arrives later.
        val franchise = candidate(2, setOf(BlendTier.FRANCHISE), order = 2, genres = listOf(28))

        assertEquals(
            blendTotalScore(manyTiers, emptySet()) { affinityBonus(it, profile) },
            blendTotalScore(franchise, emptySet()) { affinityBonus(it, profile) }
        )
        assertEquals(
            "equal after the bonus: the stronger tier wins over source order",
            listOf(2, 1),
            blendRank(listOf(manyTiers, franchise), emptySet()) { affinityBonus(it, profile) }
                .map { it.pick.tmdbId }
        )
        assertFalse(
            "and a candidate with no genres is scored as no overlap, not a penalty",
            blendTotalScore(candidate(3, emptySet(), genres = null), emptySet()) < 0
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun profileOf(vararg evidence: TasteEvidenceTitle): TasteProfile =
        buildTasteProfile(evidence.toList())

    /**
     * The same profile from evidence a case assembled as a LIST - the cap and
     * ranking cases build their titles with a `map` or a `buildList`, which is
     * one argument rather than one per title.
     */
    private fun profileOf(evidence: List<TasteEvidenceTitle>): TasteProfile =
        buildTasteProfile(evidence)

    /** A profile with exactly one strong-or-weak entry per asked-for dimension. */
    private fun profileWith(
        directorScore: Int = 0,
        keywordScore: Int = 0,
        genreScore: Int = 0
    ): TasteProfile {
        val entries = LinkedHashMap<String, Map<String, AffinityEntry>>()
        fun put(dimension: String, key: Int, score: Int) {
            if (score == 0) return
            entries[dimension] = mapOf(
                key.toString() to AffinityEntry(
                    dimension = dimension,
                    key = key.toString(),
                    displayName = "Name $key",
                    score = score,
                    lastUpdated = 1L,
                    contributions = emptyList()
                )
            )
        }
        put(AFFINITY_PERSON_DIRECTOR, 10, directorScore)
        put(AFFINITY_KEYWORD, 5, keywordScore)
        put(AFFINITY_GENRE, 28, genreScore)
        return TasteProfile(entries = entries, evidence = emptyList())
    }

    private fun evidence(
        id: Int,
        signal: String? = null,
        completed: Boolean = false,
        marked: Boolean = false,
        stamp: Long = 0L,
        detail: TmdbDetail?
    ) = TasteEvidenceTitle(
        tmdbId = id,
        mediaType = "movie",
        signal = signal,
        completed = completed,
        markedWatched = marked,
        stamp = stamp,
        detail = detail
    )

    private fun detail(
        id: Int,
        directors: List<Int> = emptyList(),
        cast: List<Int> = emptyList(),
        genres: List<Int> = emptyList(),
        keywords: List<Int> = emptyList()
    ) = TmdbDetail(
        id = id,
        genres = genres.map { TmdbGenre(it, "Genre $it") },
        keywords = TmdbKeywords(keywords = keywords.map { TmdbKeyword(it, "Keyword $it") }),
        credits = TmdbCredits(
            cast = cast.mapIndexed { index, personId ->
                TmdbCastMember(id = personId, name = "Actor $personId", order = index)
            },
            crew = directors.map { personId ->
                TmdbCrewMember(
                    id = personId,
                    name = "Director $personId",
                    job = "Director",
                    department = "Directing"
                )
            }
        )
    )

    private fun person(id: Int, role: BlendCreditRole = BlendCreditRole.DIRECTOR) =
        BlendPerson(id = id, name = "Person $id", role = role)

    private fun candidate(
        id: Int,
        tiers: Set<BlendTier>,
        order: Int = id,
        genres: List<Int>? = null,
        people: List<BlendPerson> = emptyList(),
        keywords: List<Int> = emptyList()
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
        keywordNames = keywords.map { "Keyword $it" }
    )
}
