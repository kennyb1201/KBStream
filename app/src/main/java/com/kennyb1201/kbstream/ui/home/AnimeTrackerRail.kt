package com.kennyb1201.kbstream.ui.home

import android.util.Log
import com.kennyb1201.kbstream.data.anime.AnimeTrackerEntry
import com.kennyb1201.kbstream.data.anime.AnimeTrackerStatus
import com.kennyb1201.kbstream.data.anime.animeTmdbTitleMatch
import com.kennyb1201.kbstream.data.anime.animeWatchedEpisodes
import com.kennyb1201.kbstream.data.anime.normalizeAnimeTitle
import com.kennyb1201.kbstream.data.tmdb.TmdbDetailLookup
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository

/**
 * The TMDB side of the anime legs (spec 2026-11-13, F2).
 *
 * Neither AniList nor Kitsu carries a TMDB id, so every anime entry has to be
 * joined to the catalogue this app is built on before it can become a card. That
 * join is one title match - exact after normalization, never fuzzy - and one
 * walk over TMDB's seasons to turn the service's ABSOLUTE episode progress into
 * the (season, episode) pairs the rest of the app speaks.
 *
 * Shared by Home's Continue Watching/Upcoming legs and the Library calendar, so
 * the two do not each pay for the same search, and so they cannot disagree about
 * which TMDB show an anime is.
 */
internal object AnimeTrackerRail {

    /**
     * Remembered title -> parent id, for the process.
     *
     * A null value means "no exact TMDB match", which is an answer worth
     * remembering: a rail rebuild and a profile switch ask about the same anime,
     * and a search costs a request.
     */
    private val parentIdCache = HashMap<String, String?>()

    /** The `tmdb:<id>` parent id an anime entry describes, or null. */
    suspend fun parentIdFor(
        tmdb: TmdbRepository,
        titles: List<String>
    ): String? {
        for (title in titles) {
            val key = normalizeAnimeTitle(title)
            if (key.isEmpty()) continue

            if (parentIdCache.containsKey(key)) {
                parentIdCache[key]?.let { remembered -> return remembered }
                continue
            }

            val results =
                runCatching { tmdb.searchTvOrNull(title) }.getOrNull().orEmpty()

            val match =
                results.firstOrNull { result ->
                    animeTmdbTitleMatch(titles, result.name ?: result.title)
                }

            val parentId = match?.let { "tmdb:${it.id}" }
            parentIdCache[key] = parentId
            if (parentId != null) return parentId
        }
        return null
    }

    /**
     * The (season, episode) pairs [progress] covers.
     *
     * AniList and Kitsu count an anime's episodes absolutely; TMDB files them in
     * seasons. Season 0 (specials) is not part of that count, so the walk skips
     * it - [animeWatchedEpisodes] does the arithmetic.
     */
    suspend fun watchedPairs(
        tmdb: TmdbRepository,
        parentId: String,
        progress: Int
    ): Set<Pair<Int, Int>> {
        val lookup =
            runCatching { tmdb.lookupDetailForRawId(parentId, "series") }.getOrNull()
                as? TmdbDetailLookup.Found ?: return emptySet()

        val counts =
            lookup.detail.seasons.map { season ->
                season.seasonNumber to (season.episodeCount ?: 0)
            }

        return animeWatchedEpisodes(progress, counts)
    }

    /**
     * The Upcoming candidates an anime leg's entries prove.
     *
     * A COMPLETED entry is a caught-up show: nothing left to resume, and its next
     * aired-or-upcoming episode is what the rail should advertise - the same rule
     * the Simkl caught-up leg applies. An IN_PROGRESS entry is Continue
     * Watching's, not Upcoming's, so it contributes nothing here.
     */
    suspend fun caughtUpCandidates(
        tmdb: TmdbRepository,
        entries: List<AnimeTrackerEntry>,
        source: String,
        max: Int
    ): List<CaughtUpShowCandidate> {
        val finished =
            entries
                .filter { entry ->
                    entry.status == AnimeTrackerStatus.COMPLETED && entry.progress > 0
                }
                .take(max)

        val out = ArrayList<CaughtUpShowCandidate>(finished.size)

        for (entry in finished) {
            val parentId =
                parentIdFor(tmdb, entry.titles) ?: continue

            val watched = watchedPairs(tmdb, parentId, entry.progress)
            if (watched.isEmpty()) {
                Log.d(
                    "HOME_UPNEXT",
                    "$source caught-up: no watched pair for '${entry.titles.firstOrNull()}'"
                )
                continue
            }

            out +=
                CaughtUpShowCandidate(
                    parentId = parentId,
                    watchedEpisodes = watched,
                    title = entry.titles.firstOrNull(),
                    source = source,
                    fromTracker = true
                )
        }

        return out
    }

    /** Test hook: forgets the remembered search answers. */
    internal fun clearCacheForTest() {
        parentIdCache.clear()
    }
}
