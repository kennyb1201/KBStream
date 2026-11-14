package com.kennyb1201.kbstream.ui.player

/**
 * The episode picker's long-press menu, as pure rules (spec 2026-11-13).
 *
 * The menu is Detail's episode menu - the same four actions, the same toggle
 * labels, the same descriptions - shown on the picker's own rows. The player is
 * a View-based Activity (see `ChannelGuideAdapter`'s note on why its panels are
 * Views), so the menu is the View twin of Compose's `PosterContextMenu`: it
 * renders the same actions with the same wording, and the wording is spelled
 * once, here, where both engines and the contract tests can reach it.
 *
 * Deliberately free of Android imports: the labels and the playing-episode rule
 * are the part that must never drift, so they are testable without a device.
 */
internal enum class PickerEpisodeMenuAction {
    /** One episode: the row that was long-pressed. */
    EPISODE,

    /** Every episode before the pressed one, in the same season. */
    PREVIOUS,

    /** Every episode of the listed season. */
    SEASON,

    /** Every episode of every season. */
    SERIES
}

/**
 * One menu row: what it does, the word on it, and which way it toggles.
 *
 * [marksWatched] is the direction, and it is carried rather than re-derived at
 * the press: the menu's label and the write must not disagree, and the playing
 * episode's row is the case where the two cannot be read off the same input.
 */
internal data class PickerEpisodeMenuRow(
    val action: PickerEpisodeMenuAction,
    val label: String,
    val description: String?,
    val marksWatched: Boolean
)

internal object PickerEpisodeMenuRules {

    const val MARK_EPISODE = "Mark as Watched"
    const val UNMARK_EPISODE = "Mark as Unwatched"
    const val MARK_PREVIOUS = "Mark Previous as Watched"
    const val UNMARK_PREVIOUS = "Mark Previous as Unwatched"
    const val MARK_SEASON = "Mark Season as Watched"
    const val UNMARK_SEASON = "Mark Season as Unwatched"
    const val MARK_SERIES = "Mark Entire Series as Watched"
    const val UNMARK_SERIES = "Mark Entire Series as Unwatched"

    /** The two whole-series rows' sub-lines, Detail's own wording. */
    const val MARK_SERIES_DESCRIPTION = "Every season at once, not just this one"
    const val UNMARK_SERIES_DESCRIPTION =
        "Clear every season on this device and your trackers"

    /**
     * The four actions, in Detail's order, for the row that was long-pressed.
     *
     * [isEpisodeWatched], [isSeasonWatched] and [isWholeSeriesWatched] are the
     * same watched set the rows' checkmarks are drawn from, so the menu can
     * never disagree with the row it came from. "Previous" is offered only from
     * an episode after the first, exactly as Detail does.
     *
     * [isPlaying] is the one player-only rule: the episode on screen right now
     * is being watched, and the progress writer will re-mark it on its next
     * write. So the playing row never offers to CLEAR itself - it offers
     * "Mark as Watched" (already true, a no-op) and nothing that would be a lie
     * for as long as the episode is playing. The clearing actions still reach it
     * through the season and series rows, and those exclude it instead (see
     * [numbersToChange]).
     */
    fun rows(
        episode: Int,
        isEpisodeWatched: Boolean,
        isSeasonWatched: Boolean,
        isWholeSeriesWatched: Boolean,
        isPlaying: Boolean
    ): List<PickerEpisodeMenuRow> = buildList {
        // Playing: the direction is forced to "watched", so the label reads
        // "Mark as Watched" and there is no unwatch row to press.
        val episodeDirection = !isEpisodeWatched || isPlaying
        add(
            PickerEpisodeMenuRow(
                action = PickerEpisodeMenuAction.EPISODE,
                label = if (episodeDirection) MARK_EPISODE else UNMARK_EPISODE,
                description = null,
                marksWatched = episodeDirection
            )
        )
        if (episode > 1) {
            add(
                PickerEpisodeMenuRow(
                    action = PickerEpisodeMenuAction.PREVIOUS,
                    label = if (isEpisodeWatched) UNMARK_PREVIOUS else MARK_PREVIOUS,
                    description = null,
                    marksWatched = !isEpisodeWatched
                )
            )
        }
        add(
            PickerEpisodeMenuRow(
                action = PickerEpisodeMenuAction.SEASON,
                label = if (isSeasonWatched) UNMARK_SEASON else MARK_SEASON,
                description = null,
                marksWatched = !isSeasonWatched
            )
        )
        add(
            PickerEpisodeMenuRow(
                action = PickerEpisodeMenuAction.SERIES,
                label = if (isWholeSeriesWatched) UNMARK_SERIES else MARK_SERIES,
                description = if (isWholeSeriesWatched) {
                    UNMARK_SERIES_DESCRIPTION
                } else {
                    MARK_SERIES_DESCRIPTION
                },
                marksWatched = !isWholeSeriesWatched
            )
        )
    }

    /**
     * The (season, episode numbers) pairs a row acts on.
     *
     * [seasonEpisodeNumbers] is the listed season's aired numbers - the list the
     * picker is showing - and [seriesEpisodeNumbers] is every season's, from the
     * cached season summaries (see `PlayerEpisodes.seriesEpisodeNumbers`).
     *
     * A CLEARING row never includes the playing episode: the episode on screen
     * cannot be un-watched from the menu, so the season and series rows step
     * around it and every other episode in the scope still clears. Marking
     * includes it (already watched - a no-op), which keeps the two directions
     * symmetric everywhere else.
     */
    fun numbersToChange(
        row: PickerEpisodeMenuRow,
        season: Int,
        pressedEpisode: Int,
        seasonEpisodeNumbers: List<Int>,
        seriesEpisodeNumbers: List<Pair<Int, List<Int>>>,
        playingSeason: Int?,
        playingEpisode: Int?
    ): List<Pair<Int, List<Int>>> {
        val aired = seasonEpisodeNumbers.toSet()
        val scope = when (row.action) {
            PickerEpisodeMenuAction.EPISODE -> listOf(season to listOf(pressedEpisode))
            PickerEpisodeMenuAction.PREVIOUS ->
                listOf(season to (1 until pressedEpisode).filter { it in aired })
            PickerEpisodeMenuAction.SEASON -> listOf(season to seasonEpisodeNumbers)
            PickerEpisodeMenuAction.SERIES -> seriesEpisodeNumbers
        }
        if (row.marksWatched) return scope
        return scope.mapNotNull { (scopeSeason, numbers) ->
            val kept = if (scopeSeason == playingSeason) {
                numbers.filterNot { it == playingEpisode }
            } else {
                numbers
            }
            kept.takeIf { it.isNotEmpty() }?.let { scopeSeason to it }
        }
    }

    /**
     * The picker's watched state after [row] is applied, for the press-time
     * repaint.
     *
     * The writer behind it writes locally first, but its RETURN waits for the
     * four trackers - and a dead network's timeouts are seconds. So the rows are
     * repainted from this same rule the moment the row is pressed, and the
     * writer's own read confirms it afterwards (see the activities'
     * `refreshEpisodePickerWatched`). Both go through [numbersToChange], so the
     * optimistic paint and the write cannot disagree about the scope.
     */
    fun preview(
        row: PickerEpisodeMenuRow,
        target: PickerEpisodeMenuTarget,
        watched: Set<Int>,
        watchedPairs: Set<Pair<Int, Int>>
    ): PickerEpisodeWatchedState {
        val pairs = numbersToChange(
            row = row,
            season = target.season,
            pressedEpisode = target.pressedEpisode,
            seasonEpisodeNumbers = target.seasonEpisodeNumbers,
            seriesEpisodeNumbers = target.seriesEpisodeNumbers,
            playingSeason = target.playingSeason,
            playingEpisode = target.playingEpisode
        )
        val changedPairs = pairs
            .flatMap { (scopeSeason, numbers) ->
                numbers.map { scopeEpisode -> scopeSeason to scopeEpisode }
            }
            .toSet()
        val changedListed = changedPairs
            .filter { (scopeSeason, _) -> scopeSeason == target.season }
            .map { (_, scopeEpisode) -> scopeEpisode }
            .toSet()
        return if (row.marksWatched) {
            PickerEpisodeWatchedState(
                seasonWatched = watched + changedListed,
                watchedPairs = watchedPairs + changedPairs
            )
        } else {
            PickerEpisodeWatchedState(
                seasonWatched = watched - changedListed,
                watchedPairs = watchedPairs - changedPairs
            )
        }
    }
}

/**
 * What the episode menu acts on: the pressed episode, the scope around it, and
 * the playing episode the two clearing rows have to step around.
 *
 * Assembled by each player activity from the picker's own state - the same
 * lists the rows were built from - so a menu press can never describe a
 * different show, season or episode than the row it was opened on.
 */
internal data class PickerEpisodeMenuTarget(
    val parentId: String,
    val parentType: String,
    val showName: String,
    val posterUrl: String?,
    val tmdbId: Int?,
    val season: Int,
    val pressedEpisode: Int,
    /** The listed season's aired numbers - the list the panel is showing. */
    val seasonEpisodeNumbers: List<Int>,
    /** Every season's aired numbers, from the cached season summaries. */
    val seriesEpisodeNumbers: List<Pair<Int, List<Int>>>,
    val playingSeason: Int?,
    val playingEpisode: Int?,
    /**
     * Episode number -> the stream id that episode's row was built from. It is
     * the progress-row identity the resume cleanup needs, because a source's
     * numbering and TMDB's can disagree.
     */
    val seasonStreamIds: Map<Int, String> = emptyMap()
)

/** The picker's two watched sets after a menu row: the listed season and the show. */
internal data class PickerEpisodeWatchedState(
    val seasonWatched: Set<Int>,
    val watchedPairs: Set<Pair<Int, Int>>
)
