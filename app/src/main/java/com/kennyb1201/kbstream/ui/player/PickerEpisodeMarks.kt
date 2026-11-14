package com.kennyb1201.kbstream.ui.player

import android.content.Context
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository

/**
 * Runs one episode-menu row through the shared watch writer (spec 2026-11-13).
 *
 * The player carries no watch writer of its own: this is the same
 * [WatchedStatusRepository] Detail's actions and the Continue Watching card use,
 * so a menu press marks locally AND on Simkl, MDBList, Trakt and BingeCat, drops
 * the episodes' resume rows by both identities, and announces the change on the
 * badge bus - exactly as if the same action had been taken from Detail.
 *
 * The playing-episode rule is enforced upstream in [PickerEpisodeMenuRules]
 * ([PickerEpisodeMenuRules.numbersToChange] drops it from every clearing
 * scope), with one exception this layer has to keep: a whole-series clear is the
 * shared whole-title unmark, which takes every row with it, so the playing
 * episode is re-marked straight after it. The viewer sees the one row they are
 * watching keep its checkmark, which is the whole point of the rule.
 */
internal object PickerEpisodeMarks {

    suspend fun apply(
        context: Context,
        row: PickerEpisodeMenuRow,
        target: PickerEpisodeMenuTarget
    ) {
        if (target.parentId.isBlank()) return
        val repository = WatchedStatusRepository(context.applicationContext)
        val pairs = PickerEpisodeMenuRules.numbersToChange(
            row = row,
            season = target.season,
            pressedEpisode = target.pressedEpisode,
            seasonEpisodeNumbers = target.seasonEpisodeNumbers,
            seriesEpisodeNumbers = target.seriesEpisodeNumbers,
            playingSeason = target.playingSeason,
            playingEpisode = target.playingEpisode
        )

        // The picker's own fence (spec 2026-11-13, F1): the set is the watched
        // union now, and its tracker legs are cached for the session - so a
        // clear must be recorded or the next season load would union the ✓
        // straight back. A mark lifts a fence left by an earlier clear. Done
        // before the write, so a concurrent load cannot slip between them.
        pairs.forEach { (season, numbers) ->
            if (row.marksWatched) {
                PlayerEpisodes.notePickerMark(target.parentId, season, numbers)
            } else {
                PlayerEpisodes.notePickerRemoval(target.parentId, season, numbers)
            }
        }

        if (row.action == PickerEpisodeMenuAction.SERIES && !row.marksWatched) {
            // The whole-show clear: the one shared call that also removes the
            // show from the trackers' completed lists. It takes the playing
            // episode's row with it, so that episode is put back below.
            repository.markUnwatchedLocal(target.parentId, target.parentType)
            val playingSeason = target.playingSeason
            val playingEpisode = target.playingEpisode
            if (playingSeason != null && playingEpisode != null && playingEpisode > 0) {
                repository.markEpisodesWatchedLocal(
                    id = target.parentId,
                    type = target.parentType,
                    season = playingSeason,
                    episodes = listOf(playingEpisode),
                    showName = target.showName,
                    posterUrl = target.posterUrl,
                    tmdbId = target.tmdbId
                )
            }
            return
        }

        pairs.forEach { (season, numbers) ->
            if (numbers.isEmpty()) return@forEach
            if (row.marksWatched) {
                repository.markEpisodesWatchedLocal(
                    id = target.parentId,
                    type = target.parentType,
                    season = season,
                    episodes = numbers,
                    episodeStreamIds = target.streamIdsFor(season, numbers),
                    showName = target.showName,
                    posterUrl = target.posterUrl,
                    tmdbId = target.tmdbId
                )
            } else {
                repository.markEpisodesUnwatchedLocal(
                    id = target.parentId,
                    type = target.parentType,
                    season = season,
                    episodes = numbers,
                    showName = target.showName,
                    tmdbId = target.tmdbId
                )
            }
        }

        // The whole-series mark also records the title-level mark, which is
        // what paints the poster's checkmark and pushes the show's own record
        // to the trackers - the same pair of writes Detail's series action does.
        if (row.action == PickerEpisodeMenuAction.SERIES && row.marksWatched) {
            repository.markWatchedLocal(target.parentId, target.parentType)
        }
    }

    /** The stream ids known for [numbers] of [season] (only the listed season has them). */
    private fun PickerEpisodeMenuTarget.streamIdsFor(
        season: Int,
        numbers: List<Int>
    ): List<String> {
        if (season != this.season) return emptyList()
        return numbers.mapNotNull { number -> seasonStreamIds[number] }
    }
}
