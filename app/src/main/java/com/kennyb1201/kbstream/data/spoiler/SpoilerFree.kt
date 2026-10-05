package com.kennyb1201.kbstream.data.spoiler

/**
 * Spoiler-free mode.
 *
 * Episode names, stills and synopses give away deaths, returns and twists
 * constantly, and the episode list is where a viewer is most exposed to it:
 * browsing season 3 of a show they are two episodes into shows every title and
 * a frame from every episode still to come. With the mode on, an episode the
 * viewer has not started is presented without its own identity - a numbered
 * placeholder instead of its name, the still covered, the synopsis replaced -
 * so the list can be browsed without the reveal.
 *
 * The rule lives here once, so every surface that shows an episode agrees on
 * what counts as a spoiler:
 *
 *  - already watched -> never hidden. It is behind the viewer, and hiding what
 *    they have seen would make the list harder to read for no gain.
 *  - started, not finished -> never hidden. Its own title is the thing they
 *    left off at; hiding it would also hide the row they resume from.
 *  - not started -> hidden while the mode is on.
 *
 * The `enabled` flag is the profile's preference, read by the caller rather
 * than here, so this stays a pure function of the three facts that decide it
 * and needs no Android types to test.
 */
object SpoilerFree {

    /**
     * What a hidden episode's synopsis column reads as. Deliberately not blank:
    * an empty column under the episode number reads as "this episode has no
     * description", which is a different - and wrong - claim.
     */
    const val HIDDEN_SYNOPSIS = "Hidden until you watch it"

    /** The pill drawn over a hidden still. */
    const val HIDDEN_STILL_LABEL = "SPOILER HIDDEN"

    /**
     * True when this episode's own identity must not be shown.
     *
     * [enabled] is the profile's toggle, [watched] the episode's watched state
     * and [started] whether any progress is recorded for it. Movies and live
     * channels never reach here: this is about episodes of a series, where a
     * name is a fact about a plot the viewer has not reached yet.
     */
    fun hidesIdentity(
        enabled: Boolean,
        watched: Boolean,
        started: Boolean
    ): Boolean = enabled && !watched && !started

    /**
     * The label an episode is listed under. When [hidden], the episode's own
     * name is never used - not even truncated, blurred or in the accessibility
     * description - because a name like "The Funeral" is the spoiler by itself.
     */
    fun episodeLabel(
        hidden: Boolean,
        episodeNumber: Int,
        realTitle: String?
    ): String {
        val numbered = if (episodeNumber > 0) "Episode $episodeNumber" else "Episode"
        if (hidden) return numbered
        return realTitle?.trim()?.takeIf { it.isNotBlank() } ?: numbered
    }
}
