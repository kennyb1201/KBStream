package com.kennyb1201.kbstream.data.tmdb

/**
 * Which TMDB video is "the trailer" for a title.
 *
 * Extracted so the button that OFFERS a trailer and the code that PLAYS it
 * cannot disagree. They used to each demand `type == "Trailer"` inline, which
 * is a real gap for series: TMDB files plenty of TV promos as "Teaser" (and
 * occasionally as "Clip"), so a show with a perfectly playable video showed no
 * trailer control at all, while the same rule on a movie usually matched.
 *
 * Order of preference — the first entry that exists wins:
 *
 *  1. Trailer
 *  2. Teaser
 *  3. Clip
 *  4. Featurette
 *  5. Opening Credits / Behind the Scenes
 *  6. any other YouTube video (last resort — a promo the app can still play
 *     beats a control that silently does not exist)
 *
 * YouTube only, and the key must be usable: the player resolves a
 * youtube.com/watch?v=<key> URL, so a video without one is not a candidate.
 * Ties keep TMDB's own order, so the same title always picks the same video.
 */
object TrailerPick {

    private val PREFERENCE = listOf(
        "trailer",
        "teaser",
        "clip",
        "featurette",
        "opening credits",
        "behind the scenes"
    )

    /** The video to play for "watch the trailer", or null when there is none. */
    fun best(videos: List<TmdbVideo>?): TmdbVideo? =
        videos.orEmpty()
            .filter { video ->
                video.key.isNotBlank() &&
                    video.site.equals("YouTube", ignoreCase = true)
            }
            .minByOrNull { video -> rank(video.type) }

    /** Lower is better; unrecognized types rank behind every known one. */
    private fun rank(type: String): Int {
        val index = PREFERENCE.indexOf(type.trim().lowercase())
        return if (index < 0) PREFERENCE.size else index
    }
}
