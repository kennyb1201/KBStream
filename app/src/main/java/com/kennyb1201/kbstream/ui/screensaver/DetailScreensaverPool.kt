package com.kennyb1201.kbstream.ui.screensaver

import com.kennyb1201.kbstream.data.addon.MetaPreview

/**
 * How many related titles Detail offers the show. The "More Like This" rail
 * itself draws at most thirty (see DetailScreen), and the pool must not be
 * wider than the rail it mirrors.
 */
internal const val DETAIL_SCREENSAVER_RELATED_LIMIT = 30

/**
 * One related title, as the pool needs it: TMDB's number, the name the rail
 * shows, and which medium it is. A plain triple rather than the wire model so
 * the pool's rule stays a pure function its test can pin.
 */
internal data class ScreensaverRelated(
    val tmdbId: Int,
    val name: String?,
    val series: Boolean
)

/**
 * TV Detail's screensaver pool: the title the viewer is on, then the same
 * related titles the page's own rail shows.
 *
 * Candidates only - each one still needs the clean backdrop + clearlogo the
 * caller's resolve pass looks up, because that pair is the art gate and the
 * whole picture (see hasScreensaverArt). The current title comes first because
 * it is what the viewer was looking at; the rest keep the rail's order.
 *
 * No filtering happens here. Detail's recommendations are already through the
 * screen's own ceiling, digital-release and hidden-title rules, and a second
 * filter would make the show draw titles the rail does not. [type] is the
 * screen's normalized media type ("movie" / "series").
 */
internal fun detailScreensaverCandidates(
    tmdbId: Int?,
    title: String?,
    type: String,
    related: List<ScreensaverRelated>
): List<MetaPreview> {
    val pool = ArrayList<MetaPreview>(1 + DETAIL_SCREENSAVER_RELATED_LIMIT)

    if (tmdbId != null && tmdbId > 0 && !title.isNullOrBlank()) {
        pool += MetaPreview(id = "tmdb:$tmdbId", type = type, name = title)
    }

    related.asSequence()
        // TMDB numbers films and shows separately, so the pair - not the number
        // alone - is what makes two entries the same title.
        .distinctBy { it.tmdbId to it.series }
        .filter {
            it.tmdbId > 0 && !(it.tmdbId == tmdbId && it.series == (type == "series"))
        }
        .mapNotNull { rec ->
            val name = rec.name
            if (name.isNullOrBlank()) return@mapNotNull null
            MetaPreview(
                id = "tmdb:${rec.tmdbId}",
                // TMDB numbers films and shows separately, so the same number
                // in both lists is two titles, not a repeat.
                type = if (rec.series) "series" else "movie",
                name = name
            )
        }
        .take(DETAIL_SCREENSAVER_RELATED_LIMIT)
        .toCollection(pool)

    return pool
}
