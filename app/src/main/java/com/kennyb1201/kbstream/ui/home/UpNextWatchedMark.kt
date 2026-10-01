package com.kennyb1201.kbstream.ui.home

/**
 * What one Continue Watching card's "Mark as Watched" action means.
 *
 * A card is either a whole title (a movie, or a show whose episode the rail
 * could not name) or a single episode of a show. The two need different
 * writes: a movie is a whole-title mark, while an episode has to leave the
 * rest of its show alone so the rail simply advances to the next episode.
 */
sealed interface UpNextWatchedTarget {

    /** The title id the mark is written under. */
    val parentId: String

    /** The display name to store on a completed marker row. */
    val title: String

    /** A movie, or a show marked as a whole. */
    data class WholeTitle(
        override val parentId: String,
        override val title: String,
        val type: String
    ) : UpNextWatchedTarget

    /** Exactly one episode of a show. */
    data class Episode(
        override val parentId: String,
        override val title: String,
        val season: Int,
        val episode: Int,
        val episodeStreamId: String?,
        val tmdbId: Int?,
        val poster: String?
    ) : UpNextWatchedTarget
}

/**
 * Resolves the mark for [item], or null when the card carries no usable
 * parent id (nothing to write the mark under).
 *
 * An episode target is chosen only when the card names a real episode
 * (a non-negative season and a positive episode number) — the same numbers
 * the player keys a resume row with. Everything else is a whole-title mark,
 * with the type split so a show whose episode number was dropped still marks
 * as a series rather than as a movie.
 */
fun upNextWatchedTarget(item: UpNextItem): UpNextWatchedTarget? {
    val parentId =
        item.parentId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

    val title =
        item.showTitle
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: item.title.trim()

    val season = item.season
    val episode = item.episode

    if (season != null && season >= 0 && episode != null && episode > 0) {
        return UpNextWatchedTarget.Episode(
            parentId = parentId,
            title = title,
            season = season,
            episode = episode,
            episodeStreamId =
                item.episodeStreamId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
            tmdbId = item.tmdbId,
            poster = item.poster
        )
    }

    return UpNextWatchedTarget.WholeTitle(
        parentId = parentId,
        title = title,
        type = if (upNextMediaType(item.parentType) == "series") "series" else "movie"
    )
}
