package com.kennyb1201.kbstream.data.tvdb

/**
 * One of a series' season orders, as `/v4/series/{id}/extended` names it
 * (`seasonTypes`: `name` / `type` / `alternateName`).
 *
 * [name] is TVDB's own label ("DVD Order", "Absolute Order") and is what the
 * picker shows: inventing a friendlier one would drift from the site the viewer
 * can go and look at.
 */
internal data class TvdbSeasonType(
    val name: String,
    val type: String,
    val alternateName: String? = null
)

/** One episode of a series inside a *chosen* order, plus the TMDB episode it is the same episode as. */
internal data class TvdbOrderEpisode(
    /** Season number in the chosen order - what the viewer sees and the add-on is asked for. */
    val orderSeason: Int,
    /** Episode number in the chosen order. */
    val orderEpisode: Int,
    /** The TMDB season this episode is, or null when the match could not be made. */
    val tmdbSeason: Int? = null,
    /** The TMDB episode this episode is, or null when the match could not be made. */
    val tmdbEpisode: Int? = null,
    /** The chosen order's own air date, when TVDB carries one. */
    val airDate: String? = null
)

/**
 * A built mapping for one show under one chosen order: the order's episodes,
 * each bound to the TMDB episode it IS.
 *
 * This is the file the spec calls "the mapping ... kept alongside the list": the
 * chosen order decides the number a viewer sees and the number an add-on is
 * asked for, while every watched mark, resume point and tracker write keeps
 * speaking TMDB's number. The two are joined here, once, and the join is what
 * every write path resolves through (see [TvdbCatalogueJson] for the stored
 * form).
 *
 * Pure Kotlin on purpose - no JSON, no Android - so the join can be tested
 * without a device.
 */
internal data class TvdbCatalogue(
    val seasonType: String,
    val builtAtMs: Long,
    val episodes: List<TvdbOrderEpisode>
) {

    /** TMDB (season, episode) -> the chosen order's episode. */
    fun orderFor(tmdbSeason: Int, tmdbEpisode: Int): TvdbOrderEpisode? =
        byTmdb[episodeKey(tmdbSeason, tmdbEpisode)]

    /** Every mapped episode of one TMDB season, keyed by its TMDB episode number. */
    fun forTmdbSeason(tmdbSeason: Int): Map<Int, TvdbOrderEpisode> =
        episodes.mapNotNull { episode ->
            val season = episode.tmdbSeason ?: return@mapNotNull null
            val number = episode.tmdbEpisode ?: return@mapNotNull null
            if (season == tmdbSeason) number to episode else null
        }.toMap()

    /** True when at least one episode could be tied to a TMDB episode - a mapping with no join is no mapping. */
    val mapsAnything: Boolean get() = episodes.any { it.tmdbEpisode != null }

    private val byTmdb: Map<Int, TvdbOrderEpisode> by lazy {
        val out = HashMap<Int, TvdbOrderEpisode>(episodes.size * 2)
        episodes.forEach { episode ->
            val season = episode.tmdbSeason ?: return@forEach
            val number = episode.tmdbEpisode ?: return@forEach
            // First write wins: one TMDB episode cannot be two order episodes,
            // and a duplicate here would make the number depend on list order.
            out.putIfAbsent(episodeKey(season, number), episode)
        }
        out
    }

    companion object {
        fun episodeKey(season: Int, episode: Int): Int = season * 100_000 + episode
    }
}

/**
 * One episode as the chosen order's list names it, before the TMDB join.
 *
 * `aired` is the field the join uses first: TVDB's alternate orders renumber and
 * re-cut, but an episode still airs on one date, so the date is the identity the
 * two databases share.
 */
internal data class TvdbEpisodeRecord(
    val season: Int,
    val number: Int,
    val name: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null
)
