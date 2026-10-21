package com.kennyb1201.kbstream.ui.detail

/**
 * What the Detail page's Play button says, from the state the press will act on.
 *
 * The button used to be told a fixed string by whoever last touched the page -
 * `"Play"` for a movie whatever the resume row said, and `"Play S2E3"` for a
 * series even when that episode was a paused one - so a viewer with a
 * half-watched film was offered "Play" for a press that in fact resumed. The
 * label is a promise about what the press does, and this is the one place that
 * keeps it: [resuming] is the caller's answer to "will this press continue
 * something", not a second guess made here.
 *
 * [season] and [episode] are the pair the press will start, printed without
 * padding - `S2 E3`, not `S02E03` - because the label is read at a glance
 * rather than compared with another title's numbering. A series whose pair is
 * not known yet (the season listing has not landed) falls back to the bare verb
 * rather than guessing an episode.
 */
internal fun detailPlayLabel(
    isMovie: Boolean,
    resuming: Boolean,
    season: Int? = null,
    episode: Int? = null
): String {
    if (isMovie) return if (resuming) "Resume" else "Play"

    val pair = seasonEpisodeLabel(season, episode) ?: return "Play"
    return if (resuming) "Resume $pair" else "Play $pair"
}

/** `S2 E3`, or null when either half is missing - there is no half label here. */
internal fun seasonEpisodeLabel(
    season: Int?,
    episode: Int?
): String? {
    if (season == null || episode == null) return null
    return "S$season E$episode"
}
