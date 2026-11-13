package com.kennyb1201.kbstream.data.tvdb

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository

/**
 * Ties the TVDB client to the store: what the picker may offer, what is chosen,
 * and the built catalogue the read paths resolve through (spec 2026-11-13).
 *
 * Every call here is on demand - the picker asks, the viewer picks, and the join
 * is built then. Nothing sweeps the library, nothing runs in the background, and
 * a show that never opens its Detail screen is never mentioned to TVDB.
 *
 * Fail-soft is the shape of the whole object: no key, no tvdb id, a refused
 * login, a transport failure or an empty answer all end in "no orders" or
 * "nothing changed", and the app behaves exactly as it did before this feature
 * (F4). The one thing a failure never does is overwrite a good catalogue, so an
 * outage degrades to the numbers that were already on screen rather than to
 * aired order silently.
 */
internal object TvdbOrderRepository {

    private const val TAG = "TvdbOrders"

    /**
     * The orders the picker may show for this show, aired order first.
     *
     * Empty means "no picker": the build has no key, the show has no TVDB id, the
     * series lists no alternate order, or TVDB could not be asked. All four are
     * the same to the viewer on purpose (F4).
     */
    suspend fun options(
        context: Context,
        stableShowId: String?,
        tvdbId: Int?
    ): List<TvdbOrderOption> {
        if (!TvdbConfig.isConfigured) return emptyList()
        val id = tvdbId?.takeIf { it > 0 } ?: return emptyList()
        val key = stableShowId?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()

        val cached = TvdbOrderStore.seasonTypes(context, key)
            ?: fetchSeasonTypes(context, key, id)
            ?: return emptyList()

        val options = TvdbOrders.options(cached)
        // One row - the aired order - is not a choice. A show with no alternate
        // order gets NO picker (S2), not a menu with one item.
        return if (options.size > 1) options else emptyList()
    }

    /** The chosen season type for this show, or null for the aired order. */
    fun chosen(context: Context?, stableShowId: String?): String? =
        TvdbOrderStore.chosen(context, stableShowId)

    /**
     * Stores [seasonType] for this show and rebuilds the join.
     *
     * Returns true when the choice is in effect. A null/blank type means the
     * aired order, which always succeeds; an alternate order only takes effect
     * once its episodes have arrived AND joined to at least one TMDB episode -
     * a response that could not be joined would renumber nothing, and storing it
     * would leave the picker claiming a choice the screens do not reflect.
     */
    suspend fun select(
        context: Context,
        stableShowId: String?,
        tvdbId: Int?,
        tmdbId: Int?,
        seasons: List<Int>,
        seasonType: String?
    ): Boolean {
        val key = stableShowId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val normalised = TvdbOrders.normalise(seasonType)
        if (normalised == null) {
            TvdbOrderStore.setChosen(context, key, null)
            return true
        }
        val id = tvdbId?.takeIf { it > 0 } ?: return false

        val catalogue = build(context, key, id, tmdbId, seasons, normalised) ?: return false
        if (!catalogue.mapsAnything) {
            Log.i(TAG, "order '$normalised' joined nothing to TMDB; keeping the aired order")
            return false
        }

        TvdbOrderStore.setChosen(context, key, normalised)
        TvdbOrderStore.putCatalogue(context, key, catalogue)
        TvdbOrderStore.clearFailed(context, key)
        return true
    }

    /**
     * Makes sure a chosen order still has a usable join, rebuilding it when the
     * cache has aged out.
     *
     * Called when a Detail screen opens a show that already has a choice: the
     * screens must not depend on the viewer re-picking the order for a week-old
     * cache to refresh. A failure here leaves whatever was cached in place.
     */
    suspend fun ensureCatalogue(
        context: Context,
        stableShowId: String?,
        tvdbId: Int?,
        tmdbId: Int?,
        seasons: List<Int>
    ) {
        val key = stableShowId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val chosen = TvdbOrderStore.chosen(context, key) ?: return
        if (TvdbOrderStore.catalogue(context, key) != null) return
        val id = tvdbId?.takeIf { it > 0 } ?: return
        build(context, key, id, tmdbId, seasons, chosen)?.let { catalogue ->
            if (catalogue.mapsAnything) TvdbOrderStore.putCatalogue(context, key, catalogue)
        }
    }

    // ── Building ──────────────────────────────────────────────────────────

    private suspend fun fetchSeasonTypes(
        context: Context,
        key: String,
        tvdbId: Int
    ): List<TvdbSeasonType>? {
        if (TvdbOrderStore.recentlyFailed(context, key)) return null
        val types = TvdbApiClient.seriesSeasonTypes(context, tvdbId)
        if (types == null) {
            TvdbOrderStore.markFailed(context, key)
            return null
        }
        TvdbOrderStore.clearFailed(context, key)
        TvdbOrderStore.putSeasonTypes(context, key, types)
        return types
    }

    /**
     * Fetch one order's episodes and join them to this show's TMDB episodes.
     *
     * The TMDB side is read through [TmdbRepository.lookupSeasonEpisodes] - the
     * plain, uncached season read - rather than the app's season reader, because
     * that reader is the thing this join is FOR: asking it here would hand back
     * rows already carrying the previous order's display numbers.
     */
    private suspend fun build(
        context: Context,
        key: String,
        tvdbId: Int,
        tmdbId: Int?,
        seasons: List<Int>,
        seasonType: String
    ): TvdbCatalogue? {
        if (seasons.isEmpty()) return null

        val read = TvdbApiClient.seriesEpisodes(context, tvdbId, seasonType)
        when (read) {
            is TvdbEpisodesRead.Ok -> Unit
            TvdbEpisodesRead.Empty -> {
                // The invalid-type trap: an empty 200 is not "this order has no
                // episodes", and it must not renumber, hide or delete anything.
                Log.i(TAG, "TVDB answered no episodes for '$seasonType'; refusing to renumber")
                return null
            }
            TvdbEpisodesRead.Parked -> return null
            TvdbEpisodesRead.NotConfigured -> return null
            is TvdbEpisodesRead.Failed -> {
                Log.w(TAG, "TVDB episodes failed: ${read.reason}")
                return null
            }
        }
        val orderEpisodes = (read as TvdbEpisodesRead.Ok).episodes
        if (orderEpisodes.isEmpty()) return null

        val id = tmdbId?.takeIf { it > 0 } ?: return null
        val tmdb = TmdbRepository.getInstance(context)
        val tmdbEpisodes = ArrayList<TvdbTmdbEpisode>()
        seasons.filter { it >= 0 }.distinct().forEach { season ->
            val episodes = tmdb.lookupSeasonEpisodes(id, season) ?: return@forEach
            episodes.forEach { episode ->
                val number = episode.episodeNumber
                if (number <= 0) return@forEach
                tmdbEpisodes += TvdbTmdbEpisode(
                    season = season,
                    episode = number,
                    airDate = episode.airDate
                )
            }
        }
        if (tmdbEpisodes.isEmpty()) return null

        return TvdbOrders.catalogue(
            seasonType = seasonType,
            orderEpisodes = orderEpisodes,
            tmdbEpisodes = tmdbEpisodes
        )
    }
}
