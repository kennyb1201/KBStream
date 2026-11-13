package com.kennyb1201.kbstream.data.tvdb

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The rules of alternate episode orders, with no I/O (spec 2026-11-13, F2/F3).
 *
 * Three jobs, all pure so a test can pin them without a network or a device:
 *
 *  1. Which orders a series may be offered. Only the ones TVDB says it HAS
 *     (`seasonTypes`), never a menu of every type that exists - a show with no
 *     DVD order must not be offered one, and S3's whole point is that a type the
 *     series lacks cannot renumber anything.
 *  2. How a chosen order's episodes join to TMDB's. Air date first: an episode
 *     airs once, and TVDB's DVD/absolute orders renumber the same broadcasts
 *     rather than inventing new ones, so the date is the identity the two
 *     databases share. Count-and-position is the fallback for a date either side
 *     is missing.
 *  3. What a viewer is shown. Inside a chosen order the number displayed IS the
 *     order's number, and it is also the number the add-ons are asked for - while
 *     the TMDB number on the same row stays the identity every watched mark,
 *     resume point and tracker write uses.
 */

/** One order a show offers, as the picker presents it. */
internal data class TvdbOrderOption(
    val type: String,
    val label: String,
    /** True for the aired/default order: the zero-state, which stores nothing. */
    val official: Boolean
)

/** A TMDB episode as the join sees it. */
internal data class TvdbTmdbEpisode(
    val season: Int,
    val episode: Int,
    val airDate: String? = null
)

internal object TvdbOrders {

    /** The label the picker gives the aired order - the one every show has. */
    const val OFFICIAL_LABEL = "Aired order"

    /**
     * The orders this show may be offered, aired order first.
     *
     * The aired order is always present: it is the zero-state, and S6 needs a way
     * back to it. Every entry TVDB lists that is not an official spelling is
     * offered under TVDB's own name. Duplicates by `type` are dropped - the same
     * order listed twice would be two identical rows.
     */
    fun options(types: List<TvdbSeasonType>?): List<TvdbOrderOption> {
        val out = arrayListOf(TvdbOrderOption("", OFFICIAL_LABEL, official = true))
        val seen = HashSet<String>()
        types.orEmpty().forEach { type ->
            val key = type.type.trim().lowercase()
            if (key.isEmpty() || key in TvdbConfig.OFFICIAL_TYPES) return@forEach
            if (!seen.add(key)) return@forEach
            out += TvdbOrderOption(
                type = type.type.trim(),
                label = type.name.trim().ifEmpty { type.type.trim() },
                official = false
            )
        }
        return out
    }

    /** Whether [seasonType] means the aired/default order, i.e. "store nothing". */
    fun isOfficial(seasonType: String?): Boolean =
        seasonType.isNullOrBlank() || seasonType.trim().lowercase() in TvdbConfig.OFFICIAL_TYPES

    /** The stored spelling of a choice, or null when it is the official order. */
    fun normalise(seasonType: String?): String? =
        if (isOfficial(seasonType)) null else seasonType?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Joins one order's episodes to TMDB's.
     *
     * Pass 1 is the date join (exact, then one day either side - the two
     * databases disagree about broadcast dates often enough that an exact-only
     * join silently halves a season). Pass 2 is the shape join: for a TMDB season
     * whose episode count matches a chosen-order season of the same number, the
     * episode numbers line up. Anything still unmatched keeps a null TMDB side,
     * and every read treats that as "keep TMDB's number" - never as a renumber.
     *
     * TMDB's episodes are walked in (season, episode) order, so two order
     * episodes that share a date cannot both take the same TMDB row.
     */
    fun catalogue(
        seasonType: String,
        orderEpisodes: List<TvdbEpisodeRecord>,
        tmdbEpisodes: List<TvdbTmdbEpisode>,
        nowMs: Long = System.currentTimeMillis()
    ): TvdbCatalogue {
        val type = seasonType.trim()
        if (orderEpisodes.isEmpty() || tmdbEpisodes.isEmpty()) {
            return TvdbCatalogue(type, nowMs, emptyList())
        }

        val used = BooleanArray(orderEpisodes.size)
        val assigned = HashMap<Int, Int>(tmdbEpisodes.size)
        val sortedTmdb = tmdbEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))

        // Pass 1a: exact air date.
        for (tmdb in sortedTmdb) {
            val date = parseDate(tmdb.airDate) ?: continue
            val index = orderEpisodes.indices.firstOrNull { candidate ->
                !used[candidate] && parseDate(orderEpisodes[candidate].airDate) == date
            } ?: continue
            used[index] = true
            assigned[key(tmdb.season, tmdb.episode)] = index
        }

        // Pass 1b: one day either side.
        for (tmdb in sortedTmdb) {
            if (assigned.containsKey(key(tmdb.season, tmdb.episode))) continue
            val date = parseDate(tmdb.airDate) ?: continue
            var best = -1
            var bestDays = 2L
            orderEpisodes.indices.forEach { candidate ->
                if (used[candidate]) return@forEach
                val other = parseDate(orderEpisodes[candidate].airDate) ?: return@forEach
                val days = abs(ChronoUnit.DAYS.between(date, other))
                if (days <= 1L && days < bestDays) {
                    best = candidate
                    bestDays = days
                }
            }
            if (best < 0) continue
            used[best] = true
            assigned[key(tmdb.season, tmdb.episode)] = best
        }

        // Pass 2: the shape join, per season, only where both sides agree on how
        // many episodes that season has.
        val orderBySeason = orderEpisodes.indices.groupBy { orderEpisodes[it].season }
        sortedTmdb.groupBy { it.season }.forEach { (season, tmdbSeasonEpisodes) ->
            val candidates = orderBySeason[season] ?: return@forEach
            if (candidates.size != tmdbSeasonEpisodes.size) return@forEach
            tmdbSeasonEpisodes.forEach { tmdb ->
                val tmdbKey = key(tmdb.season, tmdb.episode)
                if (assigned.containsKey(tmdbKey)) return@forEach
                val index = candidates.firstOrNull { candidate ->
                    !used[candidate] && orderEpisodes[candidate].number == tmdb.episode
                } ?: return@forEach
                used[index] = true
                assigned[tmdbKey] = index
            }
        }

        val tmdbByOrderIndex = HashMap<Int, Int>(assigned.size)
        assigned.forEach { (tmdbKey, orderIndex) -> tmdbByOrderIndex[orderIndex] = tmdbKey }

        val joined = orderEpisodes.mapIndexed { index, source ->
            val tmdbKey = tmdbByOrderIndex[index]
            TvdbOrderEpisode(
                orderSeason = source.season,
                orderEpisode = source.number,
                tmdbSeason = tmdbKey?.let { it / KEY_STRIDE },
                tmdbEpisode = tmdbKey?.let { it % KEY_STRIDE },
                airDate = source.airDate
            )
        }.sortedWith(compareBy({ it.orderSeason }, { it.orderEpisode }))

        return TvdbCatalogue(type, nowMs, joined)
    }

    /**
     * The stream id an add-on should be asked for, translated into the chosen
     * order.
     *
     * The id is `"<root>:<season>:<episode>"` exactly as the app builds it, and
     * only the two numbers are rewritten - the root is the show and does not
     * move. An id that does not parse, a show with no chosen order, an episode
     * with no mapping, or a translation that would equal the original all return
     * the input untouched: this is a translation, never a reason to fail a source
     * lookup.
     */
    fun translatedStreamId(streamId: String, catalogue: TvdbCatalogue?): String {
        val cat = catalogue ?: return streamId
        val parts = streamId.split(':')
        if (parts.size != 3) return streamId
        val season = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return streamId
        val episode = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return streamId
        val orderEpisode = cat.orderFor(season, episode) ?: return streamId
        return "${parts[0]}:${orderEpisode.orderSeason}:${orderEpisode.orderEpisode}"
    }

    private const val KEY_STRIDE = 100_000

    private fun key(season: Int, episode: Int): Int = season * KEY_STRIDE + episode

    private fun parseDate(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty()
        if (text.length < 10) return null
        return try {
            LocalDate.parse(text.take(10))
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
