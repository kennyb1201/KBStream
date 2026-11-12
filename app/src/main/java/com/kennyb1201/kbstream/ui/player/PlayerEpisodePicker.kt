package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
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
 *    episode the viewer is watching and the activity can scroll to it.
 *
 * The description is capped by the row layout (two lines, ellipsis), not here:
 * the full overview is handed over and the panel decides how much of it fits.
 */
internal object PlayerEpisodePicker {

    /** Added to the list while a season's episodes are being fetched. */
    const val LOADING_LABEL = "LOADING THIS SEASON…"

    /** `S2E07` style label, the same pair Detail prints on its cards. */
    fun episodeLabel(season: Int, episode: Int): String = "S${season}E${episode}"

    fun seasonLabel(season: Int): String = "Season $season"

    /**
     * The panel's rows for one season.
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
        seasons: List<Int>,
        selectedSeason: Int?,
        listedSeason: Int?,
        episodes: List<ResolvedEpisode>,
        watched: Set<Int>,
        playingSeason: Int?,
        playingEpisode: Int?,
        loading: Boolean,
        fallbackImageUrl: String?,
        onSeason: (Int) -> Unit,
        onPick: (ResolvedEpisode) -> Unit
    ): List<PickerItem> {
        val seasonRows = seasons.map { season ->
            PickerItem(
                label = seasonLabel(season),
                isSelected = season == selectedSeason,
                onClick = { if (season != selectedSeason) onSeason(season) }
            )
        }
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
                // Read by the row layout: the still (with the title's art as
                // its fallback), the overview under the title, the checkmark
                // for an episode the history already calls watched.
                thumbnail = episode.thumbnail,
                fallbackImageUrl = fallbackImageUrl,
                description = episode.overview,
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
        return seasonRows + episodeRows + loadingRow
    }
}
