package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.data.library.TasteSignals
import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * The viewer's affinity profile: what this profile's evidence says it likes, by
 * person, genre and keyword - the half of taste that the signal store cannot
 * express and the blend cannot guess.
 *
 * [TasteSignals] records a verdict about ONE title ("I liked this"), and the
 * blend's seed-order and overlap rules read it one title at a time. Neither can
 * answer the question a recommendation actually needs: given everything this
 * profile has signalled, is a director / a genre / a theme worth more or less?
 * This file answers it, and [RecommendationBlend] spends the answer as one
 * bounded bonus per candidate.
 *
 * Everything here is PURE and Android-free: the evidence titles (with their
 * cached detail payloads) come in, the entries come out. That is what makes the
 * arithmetic testable without a device, and what keeps the profile build off
 * every hot path - the store gathers evidence, this file scores it.
 *
 * Three decisions are worth stating where the code is, because they are the
 * ones a later reader is most likely to "fix":
 *
 *  - **Three dimensions only.** Person (director / writer / cast), genre and
 *    keyword. Production companies and networks sit on the evidence payload but
 *    a candidate never carries them - the blend only ever computed its matched
 *    people, its genre ids and its matched keywords - so an affinity in either
 *    could never fire. A dimension that cannot score a candidate is not a
 *    dimension.
 *  - **No decay.** Taste is stable on the timescale this profile measures, and
 *    recency already lives where it belongs (seed order in Top Picks). Staleness
 *    is bounded by the evidence cap instead: the profile reflects the newest
 *    [AFFINITY_EVIDENCE_CAP] evidence titles and older evidence falls out as new
 *    evidence arrives.
 *  - **Role is part of a person's key.** A director affinity never boosts that
 *    person's acting credits. A person who both directed and acted in one
 *    evidence title contributes to both sub-dimensions, at their own weights.
 */

/** A person's affinity, as the director of the evidence titles. */
internal const val AFFINITY_PERSON_DIRECTOR = "PERSON_DIRECTOR"

/** A person's affinity, as the writer of the evidence titles. */
internal const val AFFINITY_PERSON_WRITER = "PERSON_WRITER"

/** A person's affinity, as one of the top-billed cast of the evidence titles. */
internal const val AFFINITY_PERSON_CAST = "PERSON_CAST"

/** A TMDB genre id's affinity. */
internal const val AFFINITY_GENRE = "GENRE"

/** A TMDB keyword id's affinity. */
internal const val AFFINITY_KEYWORD = "KEYWORD"

/**
 * What one evidence title is worth to one dimension it carries.
 *
 * A LIKE is six - one keyword tier's weight divided down to a unit the profile
 * can stack without a single title swamping a tier. A completion is a third of
 * that, deliberately: finishing something is weak evidence of taste next to
 * saying you liked it, and a bulk mark (a season applied at once) is half again.
 * A DISLIKE mirrors a LIKE exactly: the profile is symmetric, and the disliked
 * title's own drop remains the only veto.
 */
internal const val AFFINITY_LIKE = 6
internal const val AFFINITY_PLAYBACK_COMPLETION = 2
internal const val AFFINITY_MARKED_COMPLETION = 1
internal const val AFFINITY_DISLIKE = -6

/**
 * One dimension's score is clamped here, in both directions.
 *
 * Thirty is the TMDB-recommendations tier's weight: no single affinity may ever
 * outweigh a whole tier on its own, so a viewer who has liked thirty films by
 * one director gets a strong pull - not an automatic number one. The evidence
 * counts beside the score keep accruing past the clamp; only the score stops.
 */
internal const val AFFINITY_SCORE_CAP = 30

/**
 * How many evidence titles the profile is built from, signals first.
 *
 * Sixty is the largest set whose cache-only detail reads stay trivial (sixty
 * disk parses, zero network) while still covering years of a normal viewer's
 * explicit taste. Older evidence falls out of the profile but keeps its thumb:
 * losing profile influence is not losing the signal.
 */
internal const val AFFINITY_EVIDENCE_CAP = 60

/** Per-dimension entry caps: the profile blob's size bound. */
internal const val AFFINITY_PERSON_DIMENSION_CAP = 20
internal const val AFFINITY_GENRE_CAP = 40
internal const val AFFINITY_KEYWORD_CAP = 60

/**
 * The affinity bands the blend's bonus is read from.
 *
 * A dimension inside +/-3 is noise (one completion, one mark) and buys nothing at
 * all; four to eleven is a weak pull, twelve and up a strong one. Twelve is two
 * independent LIKEs - the point at which one title's verdict is no longer the
 * reason the dimension is there.
 */
internal const val AFFINITY_WEAK_FLOOR = 4
internal const val AFFINITY_STRONG_FLOOR = 12

/**
 * What a matched affinity is worth to a candidate, by band.
 *
 * A person match is worth double a theme match, because a person is a much
 * narrower statement than a genre: the viewer liked three things by THIS person,
 * rather than three things that happen to share a shelf. The whole bonus is
 * clamped to [AFFINITY_BONUS_CAP], which is the blend's own multi-signal overlap
 * cap - the profile may never steer a candidate further than agreement between
 * tiers already does.
 */
internal const val AFFINITY_PERSON_BONUS_STRONG = 10
internal const val AFFINITY_PERSON_BONUS_WEAK = 5
internal const val AFFINITY_THEME_BONUS_STRONG = 5
internal const val AFFINITY_THEME_BONUS_WEAK = 2
internal const val AFFINITY_BONUS_CAP = 20

/** One dimension's cap, by the dimension's own spelling. */
internal fun affinityDimensionCap(dimension: String): Int = when (dimension) {
    AFFINITY_PERSON_DIRECTOR, AFFINITY_PERSON_WRITER, AFFINITY_PERSON_CAST ->
        AFFINITY_PERSON_DIMENSION_CAP

    AFFINITY_GENRE -> AFFINITY_GENRE_CAP
    else -> AFFINITY_KEYWORD_CAP
}

/** The dimension a blend person's role scores under - role is part of the key. */
internal fun affinityDimensionFor(role: BlendCreditRole): String = when (role) {
    BlendCreditRole.DIRECTOR -> AFFINITY_PERSON_DIRECTOR
    BlendCreditRole.WRITER -> AFFINITY_PERSON_WRITER
    BlendCreditRole.CAST -> AFFINITY_PERSON_CAST
}

/**
 * The canonical evidence key of one title: its TMDB id WITH its media type,
 * because a TMDB id is a number movies and shows share.
 *
 * This is the only key an evidence title can contribute under, which is what
 * makes the blend's leave-the-seeds-out rule an equality check rather than an
 * approximation: a title can only contribute if its cached detail was found,
 * and that read is by TMDB id in the first place. `tt...`-only evidence is
 * resolved to its TMDB id before it becomes evidence, and the seed of a blend
 * build is a TMDB detail.
 */
internal fun affinityEvidenceKey(mediaType: String, tmdbId: Int): String =
    "tmdb:${if (mediaType == "series") "series" else "movie"}:$tmdbId"

/**
 * One dimension's contribution from one evidence title: WHICH title it came
 * from, and what it was.
 *
 * The evidence key is carried per contribution, not per entry, because the
 * blend's leave-the-seeds-out rule (§ the blend's own comment) has to remove
 * exactly the evidence a build's own seed supplied - recomputed from evidence,
 * never approximated from scores. [signal], [completed] and [marked] are the
 * facts; the value is derived from them so a contribution can never disagree
 * with the arithmetic.
 */
@Serializable
internal data class AffinityContribution(
    /** [affinityEvidenceKey] of the title this came from. */
    val evidenceKey: String = "",
    /** The same title's media type, so a seed match is exact. */
    val evidenceType: String = "",
    /** [TasteSignals.LIKE] or [TasteSignals.DISLIKE], null for a completion. */
    val signal: String? = null,
    /** The title was finished, whatever else it carries. */
    val completed: Boolean = false,
    /** The completion was a bulk mark rather than a playback. */
    val marked: Boolean = false
) {
    /** What this contribution is worth (see the AFFINITY_* weights). */
    val value: Int
        get() = when {
            signal == TasteSignals.DISLIKE -> AFFINITY_DISLIKE
            signal == TasteSignals.LIKE ->
                AFFINITY_LIKE + if (completed) completionValue(marked) else 0

            completed -> completionValue(marked)
            else -> 0
        }

    /** True when this contribution is the explicit verdict, not the watch. */
    val isSignal: Boolean get() = signal == TasteSignals.LIKE || signal == TasteSignals.DISLIKE
}

private fun completionValue(marked: Boolean): Int =
    if (marked) AFFINITY_MARKED_COMPLETION else AFFINITY_PLAYBACK_COMPLETION

/**
 * One affinity: a person, genre or keyword this profile leans toward or away
 * from, with the evidence behind it.
 *
 * [score] is the clamped sum of [contributions]; the counts are kept beside it
 * (uncapped, past the clamp) because they are the record a future merge and a
 * future viewer surface need, and because pruning wants a tie-break that is not
 * the score it is pruning by. [lastUpdated] is the newest evidence the entry has
 * ever seen: a pruning tie-break and a diagnostic, never a ranking input - the
 * profile does not decay.
 */
@Serializable
internal data class AffinityEntry(
    /** One of the AFFINITY_* dimensions. */
    val dimension: String = "",
    /** Person / genre / keyword id, in its string form. */
    val key: String = "",
    /** The person / genre / keyword name, for diagnostics and a future viewer. */
    val displayName: String = "",
    /** Clamped to +/-[AFFINITY_SCORE_CAP]. */
    val score: Int = 0,
    val likeCount: Int = 0,
    val completionCount: Int = 0,
    val dislikeCount: Int = 0,
    val lastUpdated: Long = 0L,
    /** Every evidence title behind [score]; what leave-out subtracts from. */
    val contributions: List<AffinityContribution> = emptyList()
)

/** The evidence the stored profile was built from, in the cheapest possible form. */
@Serializable
internal data class EvidenceFingerprint(
    val signalCount: Int = 0,
    val signalMaxUpdatedAt: Long = 0L,
    val completionCount: Int = 0,
    val completionMaxAt: Long = 0L
)

/**
 * One evidence title: a fact about it ([signal] / [completed] / [marked]) plus
 * the cached detail payload its dimensions are read from.
 *
 * A null [detail] is a cache miss (or a title the kids ceiling turned away): the
 * title contributes no dimensions and still occupies its evidence slot, so a
 * cold cache cannot silently rewrite the profile from older history.
 */
internal data class TasteEvidenceTitle(
    val tmdbId: Int,
    /** "movie" or "series". */
    val mediaType: String,
    /** [TasteSignals.LIKE] / [TasteSignals.DISLIKE], or null for a completion. */
    val signal: String? = null,
    val completed: Boolean = false,
    val markedWatched: Boolean = false,
    /** When the newest fact about this title happened. */
    val stamp: Long = 0L,
    val detail: TmdbDetail? = null
) {
    /** The key its contributions are filed under (see [affinityEvidenceKey]). */
    val evidenceKey: String get() = affinityEvidenceKey(mediaType, tmdbId)
}

/**
 * The profile a blend build consumes: the entries by dimension and id, plus the
 * evidence keys they were built from.
 *
 * Plain and Android-free; the store owns persistence. A dimension missing from
 * the outer map, and an id missing from a dimension, are both simply "no
 * affinity" - which is why an empty profile needs no caller-side special case.
 */
internal data class TasteProfile(
    val entries: Map<String, Map<String, AffinityEntry>> = emptyMap(),
    /** [AffinityContribution.evidenceKey]s behind the entries. */
    val evidence: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** The affinity for one id under one dimension, or null. */
    fun entry(dimension: String, key: Any): AffinityEntry? =
        entries[dimension]?.get(key.toString())

    /**
     * This profile with one build's seed evidence removed - the blend's
     * leave-the-seeds-out rule.
     *
     * The multi-seed overlap bonus and this profile both derive from LIKE
     * evidence, and paying it twice for the same evidence would make a seed's own
     * taste dominate its own neighbourhood. So a build's snapshot drops every
     * contribution the build's own seed supplied and re-derives the affected
     * entries from what is left - exact, because the removal is by evidence key
     * rather than by score, and an entry left with no evidence at all is dropped
     * outright.
     */
    fun withoutEvidence(seedKeys: Set<String>): TasteProfile? {
        if (isEmpty || seedKeys.none { it.isNotBlank() }) return this
        val rebuilt = LinkedHashMap<String, Map<String, AffinityEntry>>()
        entries.forEach { (dimension, byKey) ->
            val kept = LinkedHashMap<String, AffinityEntry>(byKey.size)
            byKey.forEach { (key, entry) ->
                val contributions = entry.contributions.filterNot { it.evidenceKey in seedKeys }
                when {
                    contributions.size == entry.contributions.size -> kept[key] = entry
                    contributions.isNotEmpty() ->
                        kept[key] = entry.copy(
                            score = clampAffinityScore(contributions.sumOf { it.value }),
                            likeCount = contributions.count { it.signal == TasteSignals.LIKE },
                            dislikeCount = contributions.count {
                                it.signal == TasteSignals.DISLIKE
                            },
                            completionCount = contributions.count {
                                it.completed && it.signal != TasteSignals.DISLIKE
                            },
                            contributions = contributions
                        )
                }
            }
            if (kept.isNotEmpty()) rebuilt[dimension] = kept
        }
        return TasteProfile(
            entries = rebuilt,
            evidence = evidence.filterNot { it in seedKeys }
        )
    }

    companion object {
        /** The profile with nothing in it; the pre-profile blend's own case. */
        val EMPTY = TasteProfile()
    }
}

/** One affinity, clamped. */
internal fun clampAffinityScore(score: Int): Int =
    score.coerceIn(-AFFINITY_SCORE_CAP, AFFINITY_SCORE_CAP)

/**
 * Builds the profile from the evidence, or [TasteProfile.EMPTY] when there is
 * nothing explicit to build from.
 *
 * The explicit-signal gate is the whole reason a completions-only profile does
 * not exist: completions alone duplicate what the seed order already does, and a
 * bulk mark would steer the row on titles the viewer never chose to watch. One
 * LIKE or DISLIKE anywhere opens the profile; a completion then counts too (for
 * the titles the profile already has, and as its own weak evidence beside them).
 *
 * Evidence arrives newest-first, signals before completions, already capped by
 * the caller; the cap is applied here again so the builder cannot be fed more
 * than it was designed for.
 */
internal fun buildTasteProfile(evidence: List<TasteEvidenceTitle>): TasteProfile {
    if (evidence.none { it.signal == TasteSignals.LIKE || it.signal == TasteSignals.DISLIKE }) {
        return TasteProfile.EMPTY
    }

    val accumulators = LinkedHashMap<String, EntryAccumulator>()
    for (title in evidence.take(AFFINITY_EVIDENCE_CAP)) {
        val contribution = AffinityContribution(
            evidenceKey = title.evidenceKey,
            evidenceType = title.mediaType,
            signal = title.signal,
            completed = title.completed,
            marked = title.markedWatched
        )
        if (contribution.value == 0) continue
        for ((dimension, key, name) in dimensionsOf(title.detail)) {
            val accumulator = accumulators.getOrPut("$dimension|$key") {
                EntryAccumulator(dimension, key, name)
            }
            if (name.isNotBlank()) accumulator.displayName = name
            // One title contributes at most once per dimension key: a payload
            // that lists the same person or keyword twice is still one title.
            accumulator.addOnce(contribution, title.stamp)
        }
    }

    val byDimension = LinkedHashMap<String, MutableMap<String, AffinityEntry>>()
    accumulators.values
        .map { it.toEntry() }
        .groupBy { it.dimension }
        .forEach { (dimension, entries) ->
            val kept = entries.sortedWith(
                compareByDescending<AffinityEntry> { abs(it.score) }
                    .thenByDescending { it.lastUpdated }
                    .thenBy { it.key }
            ).take(affinityDimensionCap(dimension))
            val byKey = LinkedHashMap<String, AffinityEntry>(kept.size)
            kept.sortedBy { it.key }.forEach { entry -> byKey[entry.key] = entry }
            byDimension[dimension] = byKey
        }

    return TasteProfile(
        entries = byDimension,
        evidence = byDimension.values
            .flatMap { it.values }
            .flatMap { entry -> entry.contributions.map { it.evidenceKey } }
            .distinct()
            .sorted()
    )
}

/**
 * The dimensions one evidence title carries, as (dimension, key, display name).
 *
 * The person reads are the blend's own: every directing credit (not just the
 * first), the same writer rule, and the same top-three billed cast. Genres are
 * all of them; keywords are the same five the blend's keyword tier uses, because
 * the profile may only score what the blend computed for a candidate - a
 * candidate carries membership in the seed's five keywords, never the long tail.
 */
private fun dimensionsOf(detail: TmdbDetail?): List<Triple<String, String, String>> {
    if (detail == null) return emptyList()
    return buildList {
        detail.credits?.crew.orEmpty()
            .filter { it.job.equals("Director", ignoreCase = true) }
            .forEach { add(Triple(AFFINITY_PERSON_DIRECTOR, it.id.toString(), it.name)) }
        detail.credits.writers()
            .forEach { add(Triple(AFFINITY_PERSON_WRITER, it.id.toString(), it.name)) }
        detail.credits?.cast.orEmpty()
            .sortedBy { it.order }
            .take(3)
            .forEach { add(Triple(AFFINITY_PERSON_CAST, it.id.toString(), it.name)) }
        detail.genres.forEach { add(Triple(AFFINITY_GENRE, it.id.toString(), it.name)) }
        detail.keywords.list()
            .take(5)
            .forEach { add(Triple(AFFINITY_KEYWORD, it.id.toString(), it.name)) }
    }
}

/** One dimension key's evidence, while the profile is being built. */
private class EntryAccumulator(
    private val dimension: String,
    private val key: String,
    var displayName: String
) {
    private val contributions = ArrayList<AffinityContribution>()
    private var lastUpdated = 0L

    fun addOnce(contribution: AffinityContribution, stamp: Long) {
        if (contributions.any { it.evidenceKey == contribution.evidenceKey }) return
        contributions += contribution
        if (stamp > lastUpdated) lastUpdated = stamp
    }

    fun toEntry(): AffinityEntry = AffinityEntry(
        dimension = dimension,
        key = key,
        displayName = displayName,
        score = clampAffinityScore(contributions.sumOf { it.value }),
        likeCount = contributions.count { it.signal == TasteSignals.LIKE },
        completionCount = contributions.count {
            it.completed && it.signal != TasteSignals.DISLIKE
        },
        dislikeCount = contributions.count { it.signal == TasteSignals.DISLIKE },
        lastUpdated = lastUpdated,
        contributions = contributions.toList()
    )
}

/**
 * What the profile is worth to one candidate: its strongest role-matched person
 * match, its strongest matched keyword, its strongest matched genre, summed and
 * clamped to +/-[AFFINITY_BONUS_CAP].
 *
 * A match only counts through what the blend already computed FOR THAT CANDIDATE
 * - its matched people, its matched keyword ids, its genre ids - so the profile
 * costs no lookup of its own and can never claim a person or theme a candidate
 * did not actually match through. One bonus per kind: several people or several
 * keywords do not stack, or one candidate could harvest a whole cast list.
 *
 * The bonus is a demotion's worth at worst: a negative total is applied but the
 * candidate's own score is floored at zero by the caller, so the profile can
 * sink a title and never drop one.
 */
internal fun affinityBonus(candidate: BlendCandidate, profile: TasteProfile?): Int {
    if (profile == null || profile.isEmpty) return 0

    val personMatch = candidate.people
        .mapNotNull { person ->
            profile.entry(affinityDimensionFor(person.role), person.id)?.score
        }
        .maxByOrNull { abs(it) }
    val keywordMatch = candidate.keywordIds
        .mapNotNull { keywordId -> profile.entry(AFFINITY_KEYWORD, keywordId)?.score }
        .maxByOrNull { abs(it) }
    val genreMatch = candidate.genreIds.orEmpty()
        .mapNotNull { genreId -> profile.entry(AFFINITY_GENRE, genreId)?.score }
        .maxByOrNull { abs(it) }

    var total = 0
    personMatch?.let {
        total += bandBonus(it, AFFINITY_PERSON_BONUS_STRONG, AFFINITY_PERSON_BONUS_WEAK)
    }
    keywordMatch?.let {
        total += bandBonus(it, AFFINITY_THEME_BONUS_STRONG, AFFINITY_THEME_BONUS_WEAK)
    }
    genreMatch?.let {
        total += bandBonus(it, AFFINITY_THEME_BONUS_STRONG, AFFINITY_THEME_BONUS_WEAK)
    }
    return total.coerceIn(-AFFINITY_BONUS_CAP, AFFINITY_BONUS_CAP)
}

/** One affinity score, as the bonus its band buys. */
private fun bandBonus(score: Int, strong: Int, weak: Int): Int = when {
    score >= AFFINITY_STRONG_FLOOR -> strong
    score >= AFFINITY_WEAK_FLOOR -> weak
    score <= -AFFINITY_STRONG_FLOOR -> -strong
    score <= -AFFINITY_WEAK_FLOOR -> -weak
    else -> 0
}
