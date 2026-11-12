package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.ui.detail.formatEpisodeAirDate
import com.kennyb1201.kbstream.ui.detail.isEpisodeUnavailable

/**
 * Builds the in-player episode picker's rows, once for both engines.
 *
 * The picker is the stream picker's panel - same container, same list, same
 * focus handling (see `showPicker` in either activity) - so its rows are
 * [PickerItem]s. What differs from a track or source row is the content, and
 * that is all this file decides: a season strip on top, then one row per
 * episode carrying its still, its `S<n>E<n>` + title, and its overview as a
 * short description.
 *
 * Two rules live here rather than in the activities, because both must apply
 * them identically:
 *
 *  - an UNAIRED episode is listed (a season shows what it has) but is not
 *    playable, the same rule Detail's cards keep - the row keeps its still and
 *    its title and answers a press with nothing, rather than dropping out of
 *    the list or starting an episode nobody has;
 *  - the playing episode is the selected row, so the panel opens on the
 *    episode the viewer is watching and the activity can scroll to it, and it
 *    also carries the NOW PLAYING chip - the accent label alone was not
 *    readable at a glance next to the watched check.
 *
 * The seasons are NOT rows in that list any more (spec 2026-11-12): they are a
 * strip pinned above it (see [seasonStrip]), so switching season never means
 * scrolling past the season rows and the list is episodes only.
 *
 * The description is capped by the row layout (three lines, ellipsis), not here:
 * the full overview is handed over and the panel decides how much of it fits.
 */
internal object PlayerEpisodePicker {

    /** Added to the list while a season's episodes are being fetched. */
    const val LOADING_LABEL = "LOADING THIS SEASON…"

    /** `S2E07` style label, the same pair Detail prints on its cards. */
    fun episodeLabel(season: Int, episode: Int): String = "S${season}E${episode}"

    fun seasonLabel(season: Int): String = "Season $season"

    /**
     * The season strip's chips, pinned above the episode list.
     *
     * The same seasons the panel was handed, in the same order, as [PickerItem]s
     * - so both engines draw the strip from one builder and the chip a viewer
     * presses is the season the list will load. The selected season takes the
     * same selected treatment the episode rows do (the accent), and pressing
     * another chip goes through the panel's own [onSeason], which keeps the old
     * rows up while the new list loads.
     */
    fun seasonStrip(
        seasons: List<Int>,
        selectedSeason: Int?,
        onSeason: (Int) -> Unit
    ): List<PickerItem> = seasons.map { season ->
        PickerItem(
            label = seasonLabel(season),
            isSelected = season == selectedSeason,
            onClick = { if (season != selectedSeason) onSeason(season) }
        )
    }

    /**
     * The panel's episode rows for one season.
     *
     * [selectedSeason] is the season chip the viewer is on; [listedSeason] is
     * the season [episodes] actually belongs to. They differ for exactly one
     * frame - the moment a season switch starts - and keeping them apart is what
     * lets the previous season's episodes stay on screen while the new list
     * loads, with each row still labelled by the season it is really from.
     *
     * [playingSeason] and [playingEpisode] are what is on screen now, which may
     * belong to another season - the current-episode mark is only claimed when
     * the list being drawn is the one it is in.
     *
     * [loading] appends the loading row INSTEAD of dropping the rows already on
     * screen: the panel never flashes empty.
     */
    fun rows(
        selectedSeason: Int?,
        listedSeason: Int?,
        episodes: List<ResolvedEpisode>,
        watched: Set<Int>,
        playingSeason: Int?,
        playingEpisode: Int?,
        loading: Boolean,
        fallbackImageUrl: String?,
        onPick: (ResolvedEpisode) -> Unit
    ): List<PickerItem> {
        val episodeRows = episodes.map { episode ->
            val unavailable = isEpisodeUnavailable(episode.airDate)
            val isCurrent = listedSeason != null &&
                playingSeason == listedSeason &&
                playingEpisode == episode.episodeNumber
            PickerItem(
                label = buildString {
                    append(episodeLabel(listedSeason ?: selectedSeason ?: 0, episode.episodeNumber))
                    episode.name?.takeIf { it.isNotBlank() }?.let { name ->
                        append(" · ")
                        append(name)
                    }
                },
                isSelected = isCurrent,
                // The marker beside the accent label: what is PLAYING now is a
                // chip of its own, so it reads apart from the watched check and
                // from whichever row merely has focus.
                nowPlaying = isCurrent,
                // Read by the row layout: the still (with the title's art as
                // its fallback), the overview under the title, the checkmark
                // for an episode the history already calls watched.
                thumbnail = episode.thumbnail,
                fallbackImageUrl = fallbackImageUrl,
                description = episode.overview,
                // The air date on the label line, through Detail's own formatter
                // (spec 2026-11-12): the picker already reads this field for the
                // UNAVAILABLE check, so an unaired episode shows the future date
                // right beside the badge that says it is not playable yet. Blank
                // or absent means no date and no separator.
                date = episode.airDate
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw -> formatEpisodeAirDate(raw) },
                // The crowd's rating, the same number the episode cards show -
                // not the viewer's own (per-episode user ratings are a separate
                // commission on both trackers). Null on an episode TMDB has no
                // vote average for, and the row then draws no chip at all.
                rating = episode.voteAverage,
                watched = watched.contains(episode.episodeNumber),
                unavailable = unavailable,
                // An unaired episode still draws; a press on it does nothing.
                onClick = { if (!unavailable) onPick(episode) }
            )
        }
        val loadingRow = if (loading) {
            listOf(PickerItem(label = LOADING_LABEL, onClick = {}))
        } else {
            emptyList()
        }
        // No season rows (F1): the strip above the list owns them.
        return episodeRows + loadingRow
    }
}
