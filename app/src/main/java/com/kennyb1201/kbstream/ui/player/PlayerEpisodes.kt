package com.kennyb1201.kbstream.ui.player

import android.content.Context
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.data.watched.WatchedKeysRebuild
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.namedEpisodesOnly
import com.kennyb1201.kbstream.ui.detail.DetailSeasonEpisodes
import com.kennyb1201.kbstream.ui.home.localHistoryParentIdsForShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The in-player season/episode picker's data, once for both engines.
 *
 * The player has never carried an episode list of its own - only the Up Next
 * countdown, which knows the ONE pair it is about to play. So the picker reads
 * the list the way Detail does, through the same repository calls and therefore
 * the same caches: a show's seasons from the enriched meta the player already
 * fetches for its clearlogo and its Up Next runtime, and a season's episodes
 * from [TmdbRepository.getSeasonEpisodes] - the twelve-hour memory / seven-day
 * disk season cache Detail's season browser reads. Switching back to a season
 * the viewer already opened is therefore a cache hit, not a second fetch.
 *
 * Deliberately NOT a DetailViewModel: that class is a screen's state machine
 * (episode chips, resume rows, mark-watched actions), and a player has none of
 * those. This is the data half only - seasons, one season's [ResolvedEpisode]
 * list, and which of them Detail's watched union already ticks - so the two
 * activities build rows from identical answers instead of drifting apart.
 *
 * `name`, `overview`, `thumbnail` and `airDate` all come from the same place
 * the Detail cards take them from, which is what the spec means by "the
 * description and thumbnail are already in the episode data": nothing here
 * adds a data source, an endpoint, or a field.
 */
internal object PlayerEpisodes {

    /**
     * The show's seasons, ascending, specials (season 0) dropped.
     *
     * Season 0 is TMDB's bucket for trailers and recaps - the same exclusion
     * [randomAiredEpisode] makes - and a picker that offers it would be
     * offering clips. A title TMDB lists no seasons for still answers the
     * season playing now, so a sparse entry browses the season it is in rather
     * than opening an empty panel.
     */
    suspend fun seasons(
        context: Context,
        parentId: String,
        parentType: String,
        playingSeason: Int?
    ): List<Int> = withContext(Dispatchers.IO) {
        if (parentId.isBlank()) return@withContext emptyList()
        val listed = runCatchingCancellable {
            TmdbRepository.getInstance(context.applicationContext)
                .fetchEnrichedMetaCached(parentId, parentType)
        }.getOrNull()
            ?.seasons
            .orEmpty()
            .mapNotNull { summary -> summary.seasonNumber }
            .filter { number -> number > 0 }
            .distinct()
            .sorted()
        listed.ifEmpty { listOfNotNull(playingSeason?.takeIf { it > 0 }) }
    }

    /**
     * One season's episodes, in TMDB's own order.
     *
     * The same list Detail's season browser shows, from the same cache: an
     * empty answer is a miss (see `TmdbRepository`'s "empty is a miss" rule),
     * so a failed lookup answers an empty list here and the picker keeps the
     * season rows rather than claiming the season has no episodes.
     */
    suspend fun season(
        context: Context,
        parentId: String,
        parentType: String,
        season: Int
    ): List<ResolvedEpisode> = withContext(Dispatchers.IO) {
        if (parentId.isBlank() || season < 0) return@withContext emptyList()
        val repository = TmdbRepository.getInstance(context.applicationContext)
        val tmdbId = runCatchingCancellable {
            PlaybackHistoryIds.resolveTmdbId(context.applicationContext, parentId, parentType)
        }.getOrNull() ?: return@withContext emptyList()
        runCatchingCancellable {
            repository.getSeasonEpisodes(tmdbId, season, parentId)
        }.getOrDefault(emptyList()).namedEpisodesOnly()
    }

    /**
     * The episodes of [season] Detail would tick - the same watched union
     * (spec 2026-11-13, F1).
     *
     * Detail's checkmarks are local completed rows PLUS the three tracker legs
     * (Simkl, MDBList, Trakt), merged through [WatchedEpisodeState] and fenced
     * against in-flight removals ([WatchedKeysRebuild]). The picker used to read
     * the local half only, so an episode watched on a tracker - or synced from
     * another device - was ticked on Detail and blank on the picker's row.
     *
     * The legs are read once per show and kept for the session (see
     * [watchedLegs]): opening the panel, scrolling it, and switching back to a
     * season already visited read nothing. The set itself is re-derived on every
     * season load, which is what makes a mark made in Detail before playing
     * visible on the first open.
     *
     * [waitForTrackerLegs] is the two-phase read the season load makes. False -
     * the first paint - uses the legs only if they are already cached, so the
     * rows are never held behind three tracker reads (an offline box would
     * otherwise sit on "LOADING THIS SEASON…" for a tracker timeout). True
     * reads them once, and the load repaints the rows when they land.
     */
    suspend fun watchedEpisodes(
        context: Context,
        parentId: String,
        parentType: String,
        season: Int,
        waitForTrackerLegs: Boolean = false
    ): Set<Int> = withContext(Dispatchers.IO) {
        if (parentId.isBlank()) return@withContext emptySet()
        WatchedEpisodeState.effectiveWatchedEpisodesForSeason(
            parentId = parentId,
            season = season,
            // The merged key set already carries Simkl's pairs (it is one of
            // the legs), so the pairs parameter stays empty - the same shape
            // the picker's local-only read had.
            simklWatchedEpisodes = emptySet(),
            watchedEpisodeKeys = unionKeys(
                parentId,
                watchedInputs(context, parentId, parentType, waitForTrackerLegs)
            )
        )
    }

    /**
     * The show's watched (season, episode) pairs, union and all.
     *
     * What the picker's whole-series label needs, built from the same inputs as
     * [watchedEpisodes] so the label and the rows cannot disagree. Kept as pairs
     * rather than keys because the menu compares them with the season numbers
     * [seriesEpisodeNumbers] returns.
     */
    suspend fun watchedEpisodePairs(
        context: Context,
        parentId: String,
        parentType: String,
        waitForTrackerLegs: Boolean = false
    ): Set<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        if (parentId.isBlank()) return@withContext emptySet()
        val inputs = watchedInputs(context, parentId, parentType, waitForTrackerLegs)
        val localPairs = completedPairs(inputs.completed)
        val paired =
            localPairs + inputs.legs.simkl + inputs.legs.mdbList + inputs.legs.trakt
        paired - fencedPairs(parentId, localPairs)
    }

    // ── The union's inputs ────────────────────────────────────────────────

    /** One show's local completed rows and the three tracker legs. */
    private class WatchedInputs(
        val completed: List<WatchHistoryEntity>,
        val legs: WatchedLegs
    )

    /**
     * The tracker legs for one show. A leg that is not connected, has no token,
     * or fails contributes the empty set and never blanks the others - the same
     * rule Detail's rebuild keeps.
     */
    private class WatchedLegs(
        val simkl: Set<Pair<Int, Int>>,
        val mdbList: Set<Pair<Int, Int>>,
        val trakt: Set<Pair<Int, Int>>
    )

    /**
     * The legs, keyed by show, kept for the session.
     *
     * Detail gates its identical reads per title (`simklKeysReadFor`, ...) and
     * this is that gate for the player: the first season load for a show reads
     * the three legs, every later load - a season switch, a reopen, the refresh
     * after a menu press - reuses them. Guarded by a coroutine mutex so two
     * concurrent loads cannot fetch the same show twice, and held across the
     * read so the second caller waits for the first instead of starting one.
     */
    private val legsMutex = Mutex()
    private val legsByParent = HashMap<String, WatchedLegs>()

    private suspend fun watchedLegs(
        context: Context,
        parentId: String,
        historyIds: List<String>,
        tmdbId: Int?
    ): WatchedLegs = legsMutex.withLock {
        legsByParent[parentId]
            ?: readWatchedLegs(context, parentId, historyIds, tmdbId)
                .also { legs -> legsByParent[parentId] = legs }
    }

    /**
     * The three tracker reads Detail makes for a show.
     *
     * Each is the same call Detail's own load uses, with the same gate in front
     * of it, so the picker and the Detail page cannot disagree about which
     * account's marks they are reading.
     */
    private suspend fun readWatchedLegs(
        context: Context,
        parentId: String,
        historyIds: List<String>,
        tmdbId: Int?
    ): WatchedLegs {
        val simkl = runCatchingCancellable {
            val repository = SimklRepository.getInstance(context)
            if (!repository.isConfigured() || !repository.hasToken()) {
                emptySet<Pair<Int, Int>>()
            } else {
                repository.getWatchedEpisodesForShowByImdb(
                    imdbId = parentId,
                    tmdbId = tmdbId
                )
            }
        }.getOrDefault(emptySet())
        val mdbList = runCatchingCancellable {
            if (!MdbListClient.isConfigured(context)) {
                emptySet<Pair<Int, Int>>()
            } else {
                MdbListClient.watchedEpisodesForShow(
                    context = context,
                    showKeys = historyIds
                )
            }
        }.getOrDefault(emptySet())
        val trakt = runCatchingCancellable {
            val repository = TraktRepository.getInstance(context)
            if (!repository.isConfigured() || !repository.hasToken()) {
                emptySet<Pair<Int, Int>>()
            } else {
                repository.watchedEpisodesForShow(showKeys = historyIds)
            }
        }.getOrDefault(emptySet())
        return WatchedLegs(simkl = simkl, mdbList = mdbList, trakt = trakt)
    }

    private suspend fun watchedInputs(
        context: Context,
        parentId: String,
        parentType: String,
        waitForTrackerLegs: Boolean
    ): WatchedInputs {
        val appContext = context.applicationContext
        val tmdbId = runCatchingCancellable {
            PlaybackHistoryIds.resolveTmdbId(appContext, parentId, parentType)
        }.getOrNull()
        // Every id flavor this show's rows can be filed under - the list
        // Detail's own read uses, so a completed row filed under "tmdb:<n>"
        // is seen from an "tt..." session and vice versa.
        val historyIds = localHistoryParentIdsForShow(
            parentId = parentId,
            tmdbShowId = tmdbId,
            imdbId = parentId.takeIf { it.startsWith("tt") }
        ).ifEmpty { listOf(parentId) }
        val completed = runCatchingCancellable {
            WatchHistoryDatabase.getInstanceScoped(appContext)
                .watchHistoryDao()
                .getCompletedForParents(historyIds)
        }.getOrDefault(emptyList())
        return WatchedInputs(
            completed = completed,
            legs = if (waitForTrackerLegs) {
                watchedLegs(appContext, parentId, historyIds, tmdbId)
            } else {
                // Already read for this show? Use them - a warm picker paints
                // the full union on its first frame. Never read here.
                cachedWatchedLegs(parentId) ?: noWatchedLegs
            }
        )
    }

    /** The legs already read for [parentId], or null. Never fetches. */
    private suspend fun cachedWatchedLegs(parentId: String): WatchedLegs? =
        legsMutex.withLock { legsByParent[parentId] }

    /** What a leg that has not been read yet contributes: nothing at all. */
    private val noWatchedLegs = WatchedLegs(
        simkl = emptySet(),
        mdbList = emptySet(),
        trakt = emptySet()
    )

    /**
     * Detail's own union construction, leg for leg: the merged builder anchors
     * the local rows to THIS screen's id and folds Simkl in, then MDBList and
     * Trakt are keyed the same way - and the picker's removal fence is applied
     * through the same merge the rebuild uses.
     */
    private fun unionKeys(parentId: String, inputs: WatchedInputs): Set<String> {
        val built = WatchedEpisodeState.buildMergedWatchedKeys(
            parentId = parentId,
            localCompletedEntries = inputs.completed,
            simklCompletedEpisodes = inputs.legs.simkl
        ) +
            trackedKeys(parentId, inputs.legs.mdbList) +
            trackedKeys(parentId, inputs.legs.trakt)
        val localPairs = completedPairs(inputs.completed)
        return WatchedKeysRebuild.merge(
            built = built,
            addedInFlight = emptySet(),
            removedInFlight = trackedKeys(parentId, fencedPairs(parentId, localPairs))
        )
    }

    private fun trackedKeys(
        parentId: String,
        pairs: Set<Pair<Int, Int>>
    ): Set<String> = pairs.mapNotNull { (season, episode) ->
        WatchedEpisodeState.buildEpisodeKey(parentId, season, episode)
    }.toSet()

    /** The (season, episode) pairs the title's own completed rows cover. */
    private fun completedPairs(completed: List<WatchHistoryEntity>): Set<Pair<Int, Int>> =
        completed.mapNotNull { row ->
            val season = row.season
            val episode = row.episode
            if (season != null && episode != null && episode > 0) season to episode else null
        }.toSet()

    // ── The picker's removal fence ─────────────────────────────────────────

    /**
     * Episodes cleared from the picker's own menu, until they are marked again.
     *
     * The cached tracker legs still carry an episode the viewer just cleared,
     * so without this a season re-switch would union its ✓ straight back
     * (spec 2026-11-13, F1). A LOCAL completed row for the pair drops the fence
     * immediately - local wins, so a later mark from Detail re-ticks it, and
     * [PickerEpisodeMarks] clears the entry when the same episode is marked
     * from the menu again.
     */
    private val pickerRemovalLock = Any()
    private val pickerRemovals = HashMap<String, MutableSet<Pair<Int, Int>>>()

    /** Records a clear from the picker's menu, for the fence above. */
    fun notePickerRemoval(parentId: String, season: Int, episodes: Collection<Int>) {
        if (parentId.isBlank() || season < 0) return
        val pairs = episodes.filter { it > 0 }.map { episode -> season to episode }
        if (pairs.isEmpty()) return
        synchronized(pickerRemovalLock) {
            pickerRemovals.getOrPut(parentId) { mutableSetOf() }.addAll(pairs)
        }
    }

    /** Records a mark from the picker's menu: the episode is no longer fenced. */
    fun notePickerMark(parentId: String, season: Int, episodes: Collection<Int>) {
        if (parentId.isBlank() || season < 0) return
        val pairs = episodes.filter { it > 0 }.map { episode -> season to episode }
        if (pairs.isEmpty()) return
        synchronized(pickerRemovalLock) {
            pickerRemovals[parentId]?.removeAll(pairs.toSet())
        }
    }

    private fun fencedPairs(
        parentId: String,
        localPairs: Set<Pair<Int, Int>>
    ): Set<Pair<Int, Int>> {
        val removed = synchronized(pickerRemovalLock) {
            pickerRemovals[parentId]?.toSet().orEmpty()
        }
        return removed.filterNot { pair -> pair in localPairs }.toSet()
    }

    /**
     * Every season's aired episode numbers, for the menu's whole-series row.
     *
     * The cheapest correct source, as the spec asks: the season SUMMARIES the
     * panel already loads (its season strip comes from the same cached meta)
     * carry each season's episode count and air date, and TMDB's
     * `next_episode_to_air` says where a still-airing season stopped. Not one
     * season's full episode list is fetched for it - the one loaded season is
     * passed in, because the panel already holds it and its per-episode air
     * dates are the precise answer for that season.
     *
     * [DetailSeasonEpisodes] is Detail's own rule for exactly this question, so
     * a whole-series mark from the player and one from Detail cover the same
     * episodes: a season that has not premiered contributes none, and a season
     * in progress contributes only what has aired.
     */
    suspend fun seriesEpisodeNumbers(
        context: Context,
        parentId: String,
        parentType: String,
        listedSeason: Int? = null,
        listedEpisodes: List<ResolvedEpisode> = emptyList()
    ): List<Pair<Int, List<Int>>> = withContext(Dispatchers.IO) {
        if (parentId.isBlank()) return@withContext emptyList()
        val detail = runCatchingCancellable {
            TmdbRepository.getInstance(context.applicationContext)
                .fetchEnrichedMetaCached(parentId, parentType)
        }.getOrNull() ?: return@withContext emptyList()
        val stale = DetailSeasonEpisodes.staleSeasons(detail)
        detail.seasons
            .mapNotNull { summary ->
                val season = summary.seasonNumber
                if (season <= 0) return@mapNotNull null
                val numbers = DetailSeasonEpisodes.numbers(
                    season = season,
                    loadedSeason = listedSeason,
                    episodes = listedEpisodes,
                    episodeCount = summary.episodeCount,
                    seasonAirDate = summary.airDate,
                    nextEpisodeToAir = detail.nextEpisodeToAir,
                    airDatesTrusted = season !in stale
                ).orEmpty()
                numbers.takeIf { it.isNotEmpty() }?.let { season to it }
            }
            .sortedBy { (season, _) -> season }
    }
}
