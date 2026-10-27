package com.kennyb1201.kbstream.data.tmdb

import android.content.Context
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.library.TasteSignals
import com.kennyb1201.kbstream.data.library.isDisliked
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.KidsMode
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The one recommendation engine behind both recommendation surfaces.
 *
 * "More Like This" on Detail and "Because You Watched" at the end of a
 * playback used to be two different engines: Detail showed TMDB's own
 * `recommendations` list and nothing else, while the player blended four
 * signals of its own. Two engines meant two relevance rules, two places to fix
 * the same "why is this here?" report, and a player row that could suggest a
 * title Detail would never show.
 *
 * This file owns the blend. The tiers, the scoring, the ranking, the watched
 * handling, the kids ceiling and the reason labels all live here once; each
 * surface supplies only what is genuinely its own (the seed, how many picks it
 * paints, whether the franchise tier belongs, and whether a watched title is
 * dropped or pushed to the tail).
 *
 * The tiers, in weight order:
 *
 *  1. SAME FRANCHISE (100) - the seed's TMDB collection, release-date ordered,
 *     so the next entry of the saga leads. BYW only: on Detail the Collections
 *     rail sits directly above More Like This and already carries the saga, so
 *     repeating it there would double the same six posters.
 *  2. SAME KEY CREATIVES (60) - other works by the director(s), the writers and
 *     the top-billed cast, ranked by rating weight rather than popularity.
 *  3. TMDB RECOMMENDATIONS (30) - TMDB's own content engine.
 *  4. SAME KEYWORDS (25) - the seed's own TMDB keywords, five of them.
 *  5. `/similar` (15) - TMDB's similarity endpoint, fetched ONLY when tiers 1-4
 *     cannot fill the row.
 *
 * A candidate that several tiers agree on outscores one only a single tier
 * knows, which is what makes the row read like a recommendation rather than a
 * keyword dump, and each pick carries one short reason derived from the signal
 * that actually matched it.
 */

/**
 * How many picks the credits row fills in.
 *
 * Seven, not six: the row is built from a wrap-content set of cards packed to
 * the left, so the six it used to stop at left a visible gap at the end of the
 * line while the end-credits arrangement has the whole screen's width to fill.
 */
internal const val BYW_PICK_COUNT = 7

/**
 * How many survivors the kids walk aims to hand the ceiling: twice the row, so
 * candidates the ceiling turns away still leave room for the row to fill.
 */
internal const val BYW_KIDS_CERT_POOL = BYW_PICK_COUNT * 2

/** Animation (16) and Family (10751): the two genres a kids title's picks should
 * come from when the finished title is one of them. */
internal val BYW_KIDS_GENRES = setOf(16, 10751)

/** Ceiling on one candidate's certification fetch (see [blendKidsGate]). */
internal const val BYW_CERT_TIMEOUT_MS = 4_000L

/** One tier of the blend, and what agreeing with it is worth. */
internal enum class BlendTier(val weight: Int) {
    FRANCHISE(100),
    CREATIVES(60),
    RECOMMENDATIONS(30),
    KEYWORDS(25),
    SIMILAR(15)
}

/** Which slot of the seed's credits a matched person came from. */
internal enum class BlendCreditRole {
    DIRECTOR,
    WRITER,
    CAST
}

/** One person of the seed's own credits, with the role they are credited in. */
internal data class BlendPerson(
    val id: Int,
    val name: String,
    val role: BlendCreditRole
)

/**
 * One blended pick: everything a card and the PLAY handoff need, plus the one
 * reason line the card shows.
 *
 * Paths, not URLs: More Like This feeds a `TmdbRecommendationItem`, which is
 * also the shape the detail payload's own recommendations come in, and the
 * player builds its poster/backdrop URLs from the paths the same way it always
 * has.
 */
internal data class BlendPick(
    val tmdbId: Int,
    val type: String,
    val name: String,
    val posterPath: String?,
    val backdropPath: String? = null,
    val overview: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Double? = null,
    val genreIds: List<Int>? = null,
    /** One short line for the card ("Directed by ..."), never null in a rail. */
    val reason: String? = null,
    /** The resolved IMDB id, for the PLAY handoff. */
    var imdbId: String? = null
)

/**
 * One scored candidate.
 *
 * [tiers] is every tier that knows this title (a title can be both a franchise
 * entry and a director's credit), [people] the seed people whose credits
 * include it, and [keywordIds] the seed keywords that produced it - the three
 * quantities the score and the reason label are built from.
 *
 * [genreIds] is what the candidate's own list endpoint carried. Null means "not
 * known", never "neither": the franchise and keyword tiers ship no genres, and
 * an unknown genre set is scored as no overlap rather than a penalty.
 */
internal data class BlendCandidate(
    val pick: BlendPick,
    val score: Int,
    val order: Int,
    val genreIds: List<Int>? = null,
    val tiers: Set<BlendTier> = emptySet(),
    val people: List<BlendPerson> = emptyList(),
    val keywordIds: List<Int> = emptyList(),
    val keywordNames: List<String> = emptyList()
)

/** What a watched title means on one surface. */
internal enum class BlendWatchedMode {
    /** Because You Watched: a watched title is gone from the row. */
    DROP,

    /** More Like This: a watched title stays, pushed to the tail of the rail. */
    DEMOTE,

    /**
     * Home's "Top Picks for You" row: a title the profile has COMPLETED is gone
     * from the row, while one it has merely started stays (badged), because a
     * row built from finished titles should not spend its slots re-offering a
     * show already in progress - and it must not hide one either, since the
     * viewer is still watching it.
     *
     * The distinction is exactly the two watched sets: the completed one drops,
     * the partial one does not (see the partial-set handling in
     * [buildRecommendationBlend]). Every other dropping surface treats both as
     * watched, which is what [DROP] keeps.
     */
    DROP_COMPLETED
}

/** Everything a surface has to say about its own rail. */
internal data class BlendParams(
    val context: Context,
    val repo: TmdbRepository,
    val detail: TmdbDetail,
    /** Normalized: "movie" or "series". */
    val mediaType: String,
    val seedName: String,
    val pickCount: Int,
    val includeFranchise: Boolean,
    val watchedMode: BlendWatchedMode,
    /** The active profile's kids ceiling, or null for an ordinary profile. */
    val kidsMaxAge: Int? = null,
    /** True when the blend itself must drop hidden titles (BYW). */
    val dropHidden: Boolean = false,
    /**
     * The viewer's taste signals for this profile, when the surface has them.
     *
     * Default-empty, so a caller that knows nothing about taste blends exactly
     * as it did before the signals existed. On a SINGLE-seed blend its one
     * effect is the dislike drop (a title thumbed down is never recommended
     * back): a liked title is deliberately not boosted here, because the viewer
     * is already looking at one title and boosting its liked neighbours would
     * be a second, unasked ranking. The liked-side steering - the seed ordering
     * and the multi-seed overlap bonus - belongs to the multi-seed (Home row)
     * builder, which does not exist in this tree yet.
     */
    val taste: TasteSignals.Snapshot = TasteSignals.Snapshot(),
    /**
     * The certification lookup the kids gate injects, so this file stays free
     * of Android and of the repository's caching policy.
     */
    val certify: (suspend (BlendCandidate) -> String?)? = null
)

/**
 * Weighted-rating rank (IMDB-style) for one credit of a person: the rating
 * blended toward a 6.5 prior worth 200 votes. Ranking by `popularity` is how
 * "Because you watched Ted Lasso" filled up with talk shows - a guest spot on a
 * nightly show is very popular but rates ~5-6, so it outranked the scripted
 * work the actor is actually known for.
 */
internal fun personCreditRank(credit: TmdbPersonCredit): Double {
    val votes = (credit.voteCount ?: 0).coerceAtLeast(0).toDouble()
    val average = credit.voteAverage ?: 0.0
    return (average * votes + 6.5 * 200.0) / (votes + 200.0)
}

/**
 * True when a credit is the person appearing as themselves rather than playing
 * a role: talk and award shows, documentaries, archive-footage cameos. TMDB
 * credits these as "Himself" / "Herself" / "Self - Guest" / "(1998) (archive
 * footage)". Without this a person's four slots go to guest appearances.
 */
internal fun isSelfAppearance(character: String?): Boolean {
    val role = character?.lowercase()?.trim().orEmpty()
    if (role.isEmpty()) return false
    if (role.contains("archive footage")) return true
    return role.startsWith("self") ||
        role.startsWith("himself") ||
        role.startsWith("herself") ||
        role.startsWith("themselves")
}

/**
 * The blend's score: the sum of the tiers that know the candidate, plus a
 * capped bonus per extra signal it shares with the seed.
 *
 * Two people on one title is worth 10, three or more is worth 20; the same for
 * keywords and for genres. A candidate's FIRST match of a kind is free, so a
 * single-signal title scores exactly its tier weight - which is what keeps the
 * tier order (franchise > creatives > recommendations > keywords > similar)
 * intact while letting a title several tiers agree on climb above a title only
 * one of them knows.
 */
internal fun blendScore(candidate: BlendCandidate, seedGenreIds: Set<Int>): Int {
    var score = candidate.tiers.sumOf { it.weight }
    val sharedPeople = candidate.people.map { it.id }.distinct().size
    val sharedKeywords = candidate.keywordIds.distinct().size
    val sharedGenres = candidate.genreIds.orEmpty().count { it in seedGenreIds }
    score += 10 * (sharedPeople - 1).coerceIn(0, 2)
    score += 5 * (sharedKeywords - 1).coerceIn(0, 2)
    score += 5 * (sharedGenres - 1).coerceIn(0, 2)
    return score
}

/**
 * Is this candidate already watched, as far as the app can tell?
 *
 * Pure, so the rule is tested directly instead of through two screens. Every
 * flavor the app stores a title under is asked, cheapest first:
 *
 *  1. the TMDB flavor - `tmdb:<n>` and the bare digits - needs no lookup at
 *     all, and it is the flavor a route files history under whenever the IMDB
 *     bridge did not resolve (`PlaybackHistoryIds.canonicalParentId` keeps the
 *     route's own id when resolution times out or answers nothing);
 *  2. the resolved IMDB id, forward;
 *  3. the reverse direction: a history row filed under `tt...` names this
 *     candidate's TMDB id;
 *  4. the merged tracker/menu state the rest of the app badges from, under
 *     either flavor.
 *
 * A lookup that never ran or failed contributes nothing, so it can never read
 * as "watched" - and it can never clear a hit either, which is the whole point
 * of testing the TMDB flavor first.
 */
internal fun blendWatched(
    candidateTmdbId: Int,
    mediaType: String,
    imdbId: String?,
    localHistory: Set<String>,
    reverseHistoryTmdbIds: Set<Int>,
    repoWatchedKeys: Set<String>,
    repoPartialKeys: Set<String>
): Boolean {
    if (localHistory.contains("tmdb:$candidateTmdbId")) return true
    if (localHistory.contains("$candidateTmdbId")) return true
    if (imdbId != null && localHistory.contains(imdbId)) return true
    if (candidateTmdbId in reverseHistoryTmdbIds) return true
    val tmdbKey = WatchedStatusKeys.key("tmdb:$candidateTmdbId", mediaType)
    if (tmdbKey in repoWatchedKeys || tmdbKey in repoPartialKeys) return true
    if (WatchedStatusKeys.key("$candidateTmdbId", mediaType) in repoWatchedKeys) return true
    if (imdbId != null) {
        val imdbKey = WatchedStatusKeys.key(imdbId, mediaType)
        if (imdbKey in repoWatchedKeys || imdbKey in repoPartialKeys) return true
    }
    return false
}

/** The highest-weight tier a candidate belongs to, or null when it has none. */
internal fun BlendCandidate.strongestTier(): BlendTier? =
    tiers.maxByOrNull { it.weight }

/**
 * Highest score first; ties go to the candidate whose strongest tier is
 * earlier, then to source order (first seen wins).
 *
 * Franchise candidates are added in release-date order, so saga entries at
 * equal scores keep the "next entry first" order without a special case - and
 * an additive bonus may legitimately move a later entry ahead when it really
 * does share more signals with the seed.
 */
internal fun blendRank(
    candidates: List<BlendCandidate>,
    seedGenreIds: Set<Int>
): List<BlendCandidate> = candidates.sortedWith(
    compareByDescending<BlendCandidate> { blendScore(it, seedGenreIds) }
        .thenByDescending { it.strongestTier()?.weight ?: 0 }
        .thenBy { it.order }
)

/**
 * Selects the picks: at most `ceil(pickCount / 2)` per strongest tier, then a
 * second pass that fills any remaining slots ignoring the caps.
 *
 * The cap is what stops a franchise-heavy seed from turning the whole row into
 * saga entries, and the second pass is what stops the cap from producing a
 * short row while candidates remain. Selection counts a multi-signal candidate
 * once, under its strongest tier.
 */
internal fun blendSelect(
    ranked: List<BlendCandidate>,
    pickCount: Int
): List<BlendCandidate> {
    if (pickCount <= 0) return emptyList()
    val cap = (pickCount + 1) / 2
    val firstPass = LinkedHashSet<Int>()
    val perTier = mutableMapOf<BlendTier, Int>()
    ranked.forEachIndexed { index, candidate ->
        if (firstPass.size >= pickCount) return@forEachIndexed
        val tier = candidate.strongestTier() ?: return@forEachIndexed
        if ((perTier[tier] ?: 0) < cap) {
            perTier[tier] = (perTier[tier] ?: 0) + 1
            firstPass += index
        }
    }
    val chosen = firstPass.toMutableSet()
    if (chosen.size < pickCount) {
        ranked.forEachIndexed { index, _ ->
            if (chosen.size >= pickCount) return@forEachIndexed
            chosen += index
        }
    }
    // Back into ranked order, whatever path each slot arrived by.
    return chosen.sorted().take(pickCount).map { ranked[it] }
}

/**
 * The kids ceiling's cheap pre-filter: when the seed itself is Animation (16) or
 * Family (10751), a candidate sharing NEITHER genre is obviously not the same
 * kind of thing the child was watching, so it is dropped before anything is
 * spent certifying it.
 *
 * A pre-filter, not the gate - the certification decides. Candidates whose
 * genres are unknown are kept: "unknown" is not "neither".
 */
internal fun blendKidsGenrePool(
    candidates: List<BlendCandidate>,
    parentGenreIds: List<Int>
): List<BlendCandidate> {
    if (parentGenreIds.none { it in BYW_KIDS_GENRES }) return candidates
    return candidates.filter { candidate ->
        val genres = candidate.genreIds
        genres.isNullOrEmpty() || genres.any { it in BYW_KIDS_GENRES }
    }
}

/**
 * The kids ceiling's gate over an already-scored, already-filtered list:
 * certify every candidate concurrently and keep the ones the profile's ceiling
 * allows, in the order they arrived.
 *
 * A GATE, not a selector: the row is cut by [blendSelect] afterwards, so the
 * per-tier cap still applies to a kids row. Fewer survivors than the row needs
 * simply means a shorter row - it is never backfilled with content above the
 * ceiling, because the whole point of the ceiling is that the alternative is
 * worse than a gap.
 *
 * A null ceiling is not this profile's business: the list comes back exactly as
 * it went in and [certify] is never called, which is what keeps an ordinary
 * profile's row identical - and free of certification requests.
 */
internal suspend fun blendKidsGate(
    candidates: List<BlendCandidate>,
    kidsMaxAge: Int?,
    certify: suspend (BlendCandidate) -> String?
): List<BlendCandidate> {
    if (kidsMaxAge == null) return candidates
    return coroutineScope {
        val ratings = candidates
            .map { candidate ->
                async(Dispatchers.IO) {
                    // A lookup that fails or runs out of time reads as
                    // "unrated", which KidsMode.allowed resolves with its
                    // known-unknown rule: one unresolvable certification must
                    // not take the whole row down with it.
                    runCatchingCancellable { certify(candidate) }.getOrNull()
                }
            }
            .awaitAll()
        candidates
            .filterIndexed { index, _ -> KidsMode.allowed(kidsMaxAge, ratings[index]) }
    }
}

/**
 * The reason line one pick shows, from the signals that actually matched it.
 *
 * Precedence is franchise, then the first matched person in the seed's own
 * credits order (director, then writer, then cast), then the first matched
 * keyword, then the seed itself. A name is only ever a source the candidate
 * really matched through - never free text.
 */
internal fun blendReason(
    candidate: BlendCandidate,
    seedPeople: List<BlendPerson>,
    seedName: String
): String {
    if (BlendTier.FRANCHISE in candidate.tiers) return "Same series"
    val matched = candidate.people.map { it.id }.toSet()
    seedPeople.firstOrNull { it.id in matched }?.let { person ->
        return when (person.role) {
            BlendCreditRole.DIRECTOR -> "Directed by ${person.name}"
            BlendCreditRole.WRITER -> "Written by ${person.name}"
            BlendCreditRole.CAST -> "Starring ${person.name}"
        }
    }
    candidate.keywordNames.firstOrNull()?.let { keyword ->
        return "Similar theme: $keyword"
    }
    return "Similar to $seedName"
}

/**
 * Builds one recommendation rail.
 *
 * The order of operations is the point, and every step is here once:
 * fetch the tiers, keep the seed's own kind, refuse unposter-ed entries, refuse
 * the seed, drop hidden titles where the surface says so, score and rank, apply
 * the watched rule, gate on the kids ceiling, fill from `/similar` if the row
 * is still short, cap per tier, take the pick count.
 */
internal suspend fun buildRecommendationBlend(params: BlendParams): List<BlendPick> {
    val appContext = params.context.applicationContext
    val repo = params.repo
    val detail = params.detail
    val mediaType = params.mediaType
    val seedGenreIds = detail.genres.map { it.id }.toSet()
    val parentIsUnscripted = detail.genres.any { it.id in UNSCRIPTED_TV_GENRES }
    val seedName = params.seedName.takeIf { it.isNotBlank() }
        ?: detail.title
        ?: detail.name.orEmpty()
    val keepScripted: (List<Int>?) -> Boolean = { genreIds ->
        keepRecommendedGenre(genreIds, parentIsUnscripted)
    }

    // The seed's own people, in the order the reason label prefers them.
    val seedPeople: List<BlendPerson> = buildList {
        detail.credits?.crew.orEmpty()
            .filter { it.job.equals("Director", ignoreCase = true) }
            .forEach { add(BlendPerson(it.id, it.name, BlendCreditRole.DIRECTOR)) }
        detail.credits.writers()
            .forEach { add(BlendPerson(it.id, it.name, BlendCreditRole.WRITER)) }
        detail.credits?.cast.orEmpty()
            .sortedBy { it.order }
            .take(3)
            .forEach { add(BlendPerson(it.id, it.name, BlendCreditRole.CAST)) }
    }.distinctBy { it.id }.take(4)

    val candidates = LinkedHashMap<Int, BlendCandidate>()
    var order = 0

    fun add(
        tmdbId: Int,
        type: String,
        name: String,
        posterPath: String?,
        backdropPath: String? = null,
        overview: String? = null,
        releaseDate: String? = null,
        firstAirDate: String? = null,
        voteAverage: Double? = null,
        genreIds: List<Int>? = null,
        tier: BlendTier,
        people: List<BlendPerson> = emptyList(),
        keywordId: Int? = null,
        keywordName: String? = null
    ) {
        if (tmdbId <= 0) return
        if (tmdbId == detail.id) return
        if (posterPath.isNullOrBlank()) return
        if (!keepScripted(genreIds)) return
        val existing = candidates[tmdbId]
        if (existing != null) {
            // Keep the higher score but original position: a candidate several
            // tiers agree on is one candidate that accumulates signals, not
            // several copies.
            val merged = existing.copy(
                tiers = existing.tiers + tier,
                people = (existing.people + people).distinctBy { it.id },
                keywordIds = if (keywordId != null) {
                    (existing.keywordIds + keywordId).distinct()
                } else {
                    existing.keywordIds
                },
                keywordNames = if (keywordName != null) {
                    (existing.keywordNames + keywordName).distinct()
                } else {
                    existing.keywordNames
                },
                genreIds = existing.genreIds ?: genreIds,
                // The description fields live on the candidate's PICK, not on
                // the candidate itself: they are what the card and the PLAY
                // handoff render, and a tier that knows the title but not its
                // synopsis must not blank the one an earlier tier supplied.
                pick = existing.pick.copy(
                    backdropPath = existing.pick.backdropPath ?: backdropPath,
                    overview = existing.pick.overview ?: overview,
                    releaseDate = existing.pick.releaseDate ?: releaseDate,
                    firstAirDate = existing.pick.firstAirDate ?: firstAirDate,
                    voteAverage = existing.pick.voteAverage ?: voteAverage
                )
            )
            if (merged != existing) candidates[tmdbId] = merged
            return
        }
        candidates[tmdbId] = BlendCandidate(
            pick = BlendPick(
                tmdbId = tmdbId,
                type = type,
                name = name,
                posterPath = posterPath,
                backdropPath = backdropPath,
                overview = overview,
                releaseDate = releaseDate,
                firstAirDate = firstAirDate,
                voteAverage = voteAverage,
                genreIds = genreIds
            ),
            score = 0,
            order = order++,
            genreIds = genreIds,
            tiers = setOf(tier),
            people = people.distinctBy { it.id },
            keywordIds = listOfNotNull(keywordId),
            keywordNames = listOfNotNull(keywordName)
        )
    }

    // T1: franchise - the seed's collection, release-date ordered so the next
    // entry of the saga leads. BYW only (see the class comment).
    if (params.includeFranchise) {
        detail.belongsToCollection?.id?.let { collectionId ->
            runCatchingCancellable { repo.getKBCollectionItems(collectionId) }
                .getOrNull()
                .orEmpty()
                .sortedBy { it.releaseDate.orEmpty() }
                .forEach { part ->
                    add(
                        tmdbId = part.id,
                        type = "movie",
                        name = part.title ?: part.name.orEmpty(),
                        posterPath = part.posterPath,
                        releaseDate = part.releaseDate,
                        voteAverage = part.voteAverage,
                        tier = BlendTier.FRANCHISE
                    )
                }
        }
    }

    // T2: key creatives - directors, then the writers, then the top-billed
    // cast, each contributing their best-rated few works.
    if (seedPeople.isNotEmpty()) {
        val fetched = coroutineScope {
            seedPeople.map { person ->
                async(Dispatchers.IO) {
                    person to runCatchingCancellable { repo.getPerson(person.id) }.getOrNull()
                }
            }.awaitAll()
        }
        fetched.forEach { (seedPerson, person) ->
            val credits = person?.combinedCredits ?: return@forEach
            val roleFiltered: (TmdbPersonCredit) -> Boolean = when (seedPerson.role) {
                BlendCreditRole.DIRECTOR ->
                    { credit -> credit.job.equals("Director", ignoreCase = true) }

                BlendCreditRole.WRITER ->
                    { credit ->
                        credit.department == "Writing" ||
                            credit.job == "Writer" ||
                            credit.job == "Screenplay"
                    }

                // A performer is credited in the cast list; their crew rows are
                // other people's jobs.
                BlendCreditRole.CAST -> { _ -> false }
            }
            val rows = buildList {
                addAll(credits.cast)
                addAll(credits.crew.filter(roleFiltered))
            }.distinctBy { it.id }
                .filter { credit ->
                    val type = credit.mediaType.orEmpty()
                    (type == "movie" || type == "tv") &&
                        !credit.posterPath.isNullOrBlank() &&
                        !isSelfAppearance(credit.character) &&
                        keepScripted(credit.genreIds)
                }
                .sortedByDescending { personCreditRank(it) }
                .take(4)
            rows.forEach { credit ->
                add(
                    tmdbId = credit.id,
                    type = if (credit.mediaType == "tv") "series" else "movie",
                    name = credit.title ?: credit.name.orEmpty(),
                    posterPath = credit.posterPath,
                    releaseDate = credit.releaseDate,
                    firstAirDate = credit.firstAirDate,
                    voteAverage = credit.voteAverage,
                    genreIds = credit.genreIds,
                    tier = BlendTier.CREATIVES,
                    people = listOf(seedPerson)
                )
            }
        }
    }

    // T3: TMDB's own recommendation engine.
    detail.recommendations?.results.orEmpty()
        .filter { !it.posterPath.isNullOrBlank() && keepScripted(it.genreIds) }
        .take(maxOf(10, params.pickCount))
        .forEach { rec ->
            add(
                tmdbId = rec.id,
                type = mediaType,
                name = (rec.title ?: rec.name).orEmpty(),
                posterPath = rec.posterPath,
                backdropPath = rec.backdropPath,
                overview = rec.overview,
                releaseDate = rec.releaseDate,
                firstAirDate = rec.firstAirDate,
                voteAverage = rec.voteAverage,
                genreIds = rec.genreIds,
                tier = BlendTier.RECOMMENDATIONS
            )
        }

    // T4: the seed's keyword neighbours.
    val seedKeywords = detail.keywords.list().take(5)
    seedKeywords.forEach { keyword ->
        runCatchingCancellable { repo.getKeywordItems(keyword.id, mediaType) }
            .getOrNull()
            .orEmpty()
            .take(6)
            .forEach { item ->
                add(
                    tmdbId = item.id,
                    type = mediaType,
                    name = item.name ?: item.title.orEmpty(),
                    posterPath = item.posterPath,
                    backdropPath = item.backdropPath,
                    overview = item.overview,
                    tier = BlendTier.KEYWORDS,
                    keywordId = keyword.id,
                    keywordName = keyword.name
                )
            }
    }

    // T5: TMDB's `/similar`, and only as fill. The row is short after the tiers
    // above have been filtered - not merely before scoring - and TMDB's
    // similarity endpoint is the last thing asked for, never a fourth opinion
    // when three tiers already agreed.
    if (candidates.size < params.pickCount) {
        runCatchingCancellable { repo.getSimilar(detail.id, mediaType) }
            .getOrNull()
            ?.results
            .orEmpty()
            .filter { !it.posterPath.isNullOrBlank() && keepScripted(it.genreIds) }
            .forEach { item ->
                add(
                    tmdbId = item.id,
                    type = mediaType,
                    name = (item.title ?: item.name).orEmpty(),
                    posterPath = item.posterPath,
                    backdropPath = item.backdropPath,
                    overview = item.overview,
                    releaseDate = item.releaseDate,
                    firstAirDate = item.firstAirDate,
                    voteAverage = item.voteAverage,
                    genreIds = item.genreIds,
                    tier = BlendTier.SIMILAR
                )
            }
    }

    if (candidates.isEmpty()) return emptyList()

    // Hidden titles: BYW drops them (Detail's own rail already does).
    val hiddenKeys = if (params.dropHidden) {
        runCatchingCancellable { HiddenTitles.keys(appContext) }.getOrNull().orEmpty()
    } else {
        emptySet()
    }

    val taste = params.taste
    val scored = candidates.values
        .filter { candidate ->
            hiddenKeys.isEmpty() || !HiddenTitles.hides(
                hiddenKeys,
                candidate.pick.type,
                candidate.pick.name,
                yearOf(candidate.pick),
                "tmdb:${candidate.pick.tmdbId}",
                candidate.pick.imdbId
            )
        }
        // Taste: a title the viewer thumbed down is never recommended back to
        // them, whatever the seed and on both surfaces - the whole of taste's
        // effect on a single-seed blend. Filtered here, with the hidden-title
        // drop and before ranking, so a disliked title cannot consume a pick
        // slot the way a merely watched one used to.
        .filterNot { candidate ->
            taste.isDisliked(
                mediaType = candidate.pick.type,
                title = candidate.pick.name,
                year = yearOf(candidate.pick),
                tmdbId = candidate.pick.tmdbId,
                imdbId = candidate.pick.imdbId
            )
        }
        .map { it.copy(score = blendScore(it, seedGenreIds)) }

    val ranked = blendRank(scored, seedGenreIds)

    // What this profile has already watched, in the two shapes the app stores
    // it in: the local history rows (which name whatever flavor started
    // playback) and the merged tracker/menu state the rest of the app badges
    // from. The repository call resolves the reverse mapping itself and issues
    // no new sync - it reuses the sets Home's badges already pay for.
    val localHistory = runCatchingCancellable {
        WatchHistoryDatabase.getInstanceScoped(appContext)
            .watchHistoryDao()
            .getAll()
            .map { it.parentId }
            .toHashSet()
    }.getOrNull().orEmpty()
    val reverseHistory = runCatchingCancellable {
        historyTmdbIds(localHistory, mediaType, repo)
    }.getOrNull().orEmpty()
    // Home's Top Picks row drops COMPLETIONS only (see
    // [BlendWatchedMode.DROP_COMPLETED]): the partial set is consulted under
    // every other mode and is emptied for that one, so a title the viewer has
    // merely started stays in the row.
    val completedOnlyDrop = params.watchedMode == BlendWatchedMode.DROP_COMPLETED
    val dropWatched = params.watchedMode != BlendWatchedMode.DEMOTE
    val watchedRepository = WatchedStatusRepository(appContext)
    val watchedPairs = ranked.map { rankedCandidate ->
        "tmdb:${rankedCandidate.pick.tmdbId}" to mediaType
    }
    val repoWatchedKeys = runCatchingCancellable {
        watchedRepository.preloadAndGetWatchedKeys(watchedPairs)
    }.getOrNull().orEmpty()
    val repoPartialKeys = runCatchingCancellable {
        watchedRepository.preloadAndGetPartiallyWatchedKeys(watchedPairs)
    }.getOrNull().orEmpty()

    fun watched(candidate: BlendCandidate, imdbId: String?): Boolean = blendWatched(
        candidateTmdbId = candidate.pick.tmdbId,
        mediaType = mediaType,
        imdbId = imdbId,
        localHistory = localHistory,
        reverseHistoryTmdbIds = reverseHistory,
        repoWatchedKeys = repoWatchedKeys,
        repoPartialKeys = if (completedOnlyDrop) emptySet() else repoPartialKeys
    )

    // Walk the ranked order and filter, resolving a candidate's IMDB id only
    // when the cheap checks did not already answer. Resolve calls are therefore
    // bounded by how many candidates have to be examined to fill the row
    // (picks + dropped + gate-rejected + the ones the per-tier cap defers),
    // never by the whole candidate list - and the row is taken AFTER the
    // filter, so a watched title can no longer consume a slot and shorten the
    // rail.
    val walkTarget = if (params.kidsMaxAge != null) {
        // The ceiling can turn a survivor away after this walk, so the walk
        // hands it a deeper pool than the row needs.
        BYW_KIDS_CERT_POOL
    } else {
        // Twice the row: enough headroom for the per-tier cap's second pass to
        // have something to reach for, without resolving the entire list.
        params.pickCount * 2
    }
    // The forward IMDB check is the third of the three flavors and the only one
    // that costs a lookup, so it is paid for where being wrong costs a pick -
    // the surface that DROPS watched titles. The demoting surface never runs
    // it: its own tracker read answers the same question under both flavors.
    val resolveInWalk = dropWatched
    val survivors = ArrayList<BlendCandidate>(walkTarget)
    val demoted = HashSet<Int>()
    for (candidate in ranked) {
        if (survivors.size >= walkTarget) break
        // Cheap checks first; the resolve only runs when they say nothing.
        var imdbId: String? = null
        var isWatched = watched(candidate, imdbId = null)
        if (!isWatched && resolveInWalk) {
            imdbId = runCatchingCancellable {
                repo.resolveImdbId(candidate.pick.tmdbId, candidate.pick.type)
            }.getOrNull()
            isWatched = watched(candidate, imdbId)
        }
        if (isWatched) {
            if (dropWatched) continue
            demoted += candidate.pick.tmdbId
        }
        // The id lands on the picks that survive, and only those: PLAY resolves
        // streams from it, and a null one falls back to the TMDB flavor exactly
        // as it always has.
        candidate.pick.imdbId = imdbId
        survivors += candidate
    }

    // A watched title is demoted, not dropped, on More Like This: the two bands
    // keep score order, and the rail still shows what the viewer has seen
    // (badged) rather than pretending it does not exist.
    val ordered = if (params.watchedMode == BlendWatchedMode.DEMOTE) {
        val (fresh, seen) = survivors.partition { it.pick.tmdbId !in demoted }
        fresh + seen
    } else {
        survivors
    }

    val kidsCeiling = params.kidsMaxAge
    val certify = params.certify
    val gated = if (kidsCeiling != null && certify != null) {
        blendKidsGate(
            candidates = blendKidsGenrePool(
                candidates = ordered,
                parentGenreIds = detail.genres.map { it.id }
            ),
            kidsMaxAge = kidsCeiling,
            certify = certify
        )
    } else {
        ordered
    }

    return blendSelect(gated, params.pickCount).map { candidate ->
        candidate.pick.copy(
            reason = blendReason(candidate, seedPeople, seedName)
        )
    }
}

/**
 * Interleaves one blend per seed into Home's "Top Picks for You" row.
 *
 * A single-seed rail is one ranked list; a row built from several seeds is a
 * merge, and the merge is where a personal row goes wrong in three ways at
 * once - one seed eating it, the same title arriving twice, or a title the
 * viewer has finished being offered back to them. So the order is explicit:
 *
 *  1. **Round-robin by seed** - seed 1's #1, seed 2's #1, seed 3's #1, seed
 *     1's #2, ... - so the row opens with a reason to trust each seed and no
 *     single seed fills it. Seed order is the caller's (recency, with the
 *     viewer's liked titles first - see HomeViewModel's Top Picks build).
 *  2. **Dedupe across seeds by both flavors** - a candidate two seeds both rank
 *     appears once, under the FIRST seed that ranked it, and is skipped rather
 *     than counted against the later seed's own cap.
 *  3. **Caps** - at most [perSeedCap] cards from any one seed, and at most
 *     [rowCap] in the row. The candidate the blend already dropped for being
 *     watched or disliked is not this function's business: it never arrives.
 *
 * Pure, so the interleave and the caps are tested directly rather than through
 * a Home build.
 */
internal fun mergeTopPicksSeeds(
    perSeed: List<List<BlendPick>>,
    perSeedCap: Int,
    rowCap: Int
): List<BlendPick> {
    if (rowCap <= 0 || perSeedCap <= 0 || perSeed.isEmpty()) return emptyList()
    val cursor = IntArray(perSeed.size)
    val added = IntArray(perSeed.size)
    val seenTmdbIds = HashSet<Int>()
    val seenImdbIds = HashSet<String>()
    val merged = ArrayList<BlendPick>(rowCap)

    while (merged.size < rowCap) {
        var progressed = false
        perSeed.forEachIndexed { seedIndex, picks ->
            if (merged.size >= rowCap) return@forEachIndexed
            if (added[seedIndex] >= perSeedCap) return@forEachIndexed
            val pick = picks.getOrNull(cursor[seedIndex]) ?: return@forEachIndexed
            cursor[seedIndex] += 1
            progressed = true
            if (!seenTmdbIds.add(pick.tmdbId)) return@forEachIndexed
            pick.imdbId?.let { imdbId ->
                if (!seenImdbIds.add(imdbId)) return@forEachIndexed
            }
            added[seedIndex] += 1
            merged += pick
        }
        // No seed had anything left to offer (every cursor exhausted or every
        // per-seed cap reached): the row is finished, short or not.
        if (!progressed) break
    }
    return merged
}

/** The release year a pick carries, for the hidden-title name match. */
private fun yearOf(pick: BlendPick): Int? =
    (pick.releaseDate ?: pick.firstAirDate)?.take(4)?.toIntOrNull()

/**
 * The TMDB ids the local history's "tt..." rows resolve to.
 *
 * Only rows in the IMDB namespace need this: a row filed as `tmdb:<n>` or as
 * bare digits is compared directly. The resolution table already holds most of
 * these pairs (they were written when the title was played), so this is
 * normally a disk read, and a row that cannot be resolved contributes nothing.
 */
private suspend fun historyTmdbIds(
    history: Set<String>,
    type: String,
    repo: TmdbRepository
): Set<Int> {
    val rows = history.filter { it.startsWith("tt") }
    if (rows.isEmpty()) return emptySet()
    val ids = HashSet<Int>(rows.size)
    rows.forEach { row ->
        runCatchingCancellable { repo.resolveTmdbId(row, type) }
            .getOrNull()
            ?.let { ids += it }
    }
    return ids
}
