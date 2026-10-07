package com.kennyb1201.kbstream.ui.player

import android.content.Context
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The launch-time resume rule: a player handed NO position of its own asks the
 * watch history before it opens the file.
 *
 * A picker launches without a position on purpose. "Play Manually" on a
 * poster's long-press menu opens the streams picker, and the picker's own pick
 * is the press that starts playback - by which time the target may carry no
 * resume point at all (the Detail screen's manual route fires as soon as the
 * metadata is ready rather than waiting on a resume lookup, and a session
 * restored after BACK carries only the position it started with). The player is
 * therefore the last place that can still answer "where was the viewer", and it
 * must: without it, picking a source for a title with history on Continue
 * Watching replays it from the beginning.
 *
 * It lived inline in the ExoPlayer engine, which is why the same press resumed
 * in one engine and restarted the title in the others - MPV runs precisely the
 * sessions ExoPlayer cannot (a decoder handoff, an anime profile), and the
 * external engine hands the position to another app. One rule, one file, so the
 * three cannot drift again.
 *
 * [savedPositionMs] is a read of the LOCAL history only, deliberately: it sits
 * in front of a launch, so it stays a local lookup with no tracker round-trip.
 * A missing row, a finished one, a position at 0 and a failed read all mean the
 * same thing - nothing to resume, leave the launch as it was - and none of them
 * throws.
 *
 * The lookup is WIDENED across id flavors, which is the second half of the same
 * bug. A row is stored under the id flavor that launched it ([PlaybackHistoryIds
 * .historyId]) - a title reached as "tmdb:<n>" once and as "tt..." later files
 * TWO rows, and an episode filed by its stream id reopens under the numbered
 * key - but the launch only knows its own flavor. Asking for exactly
 * [historyId] therefore missed a row the title already had and restarted it.
 * The exact row is still asked for first (one indexed read, the whole answer
 * when the flavor never changed); only when it is absent does the lookup fall
 * back to the title's rows, matched by episode (see [WatchHistoryEntity
 * .namesSameEpisode]). That fallback needs the parent id in the flavor the rows
 * were stored under, so it canonicalizes it - bounded, once, on the miss path
 * only.
 */
internal object PlaybackResume {

    /**
     * Whether a launch may pick the save up from the watch history.
     *
     * Two things disqualify it, and both are explicit statements that win over
     * the saved position: the launch carried a position of its own (someone
     * already knows where this should start), and the viewer asked for the
     * beginning - [startFromBeginning], which is the one flag that also tells
     * the player not to fall back later.
     */
    fun mayResumeFromHistory(
        startPositionMs: Long,
        startFromBeginning: Boolean,
        historyId: String
    ): Boolean = startPositionMs <= 0L && !startFromBeginning && historyId.isNotBlank()

    /**
     * The saved in-progress position for this launch, or null when there is
     * nothing to resume to.
     *
     * [historyId] is the same row id the session writes itself under (see
     * [PlaybackHistoryIds.historyId]), so it is asked for first. The rest of the
     * launch's identity - [parentId] plus its [parentType], [season], [episode]
     * and [episodeStreamId] - is the fallback: it names the SAME title+episode
     * under whichever flavor the existing row happens to use.
     */
    suspend fun savedPositionMs(
        context: Context,
        historyId: String,
        parentId: String,
        parentType: String,
        season: Int?,
        episode: Int?,
        episodeStreamId: String?
    ): Long? {
        if (historyId.isBlank() && parentId.isBlank()) return null
        return runCatchingCancellable {
            withContext(Dispatchers.IO) {
                val dao = WatchHistoryDatabase.getInstanceScoped(context).watchHistoryDao()

                // The exact row: the common case, one indexed read, and the
                // only answer when the title never changed id flavor. A row
                // that is there but completed (or at 0) is the answer too -
                // "start over" - so the wider search is skipped rather than
                // second-guessing it with a stale row under another flavor.
                if (historyId.isNotBlank()) {
                    val exact = dao.getById(historyId)
                    if (exact != null) return@withContext exact.resumablePositionMs()
                }

                // The row was filed under another flavor. Ask for the title's
                // in-progress rows under both the route's own parent id and its
                // canonical twin, then keep the one naming this episode.
                val parents = lookupParentIds(context, parentId, parentType)
                if (parents.isEmpty()) return@withContext null
                dao.getInProgressForParents(parents)
                    .firstOrNull { it.namesSameEpisode(season, episode, episodeStreamId) }
                    ?.resumablePositionMs()
            }
        }.getOrNull()
    }

    /**
     * The parent ids a row for this title could be stored under: the route's
     * own id, and the canonical id rows are written with (see
     * [PlaybackHistoryIds.canonicalParentId]). Both are included because a row
     * stored before the id could be resolved kept the route's raw id, and the
     * canonical twin is what finds a row written from the other flavor.
     *
     * Reached only on the exact-row miss, so the bounded resolve behind
     * [PlaybackHistoryIds.canonicalParentId] is not paid on the common path.
     */
    private suspend fun lookupParentIds(
        context: Context,
        parentId: String,
        parentType: String
    ): List<String> {
        val raw = parentId.trim()
        if (raw.isBlank()) return emptyList()
        val canonical = PlaybackHistoryIds.canonicalParentId(
            context = context,
            rawParentId = raw,
            parentType = parentType
        )
        return listOf(raw, canonical).distinct()
    }

    /**
     * The position this row offers to a launch, or null when it offers none:
     * a finished row and a row at 0 both mean "start where the launch said".
     */
    private fun WatchHistoryEntity.resumablePositionMs(): Long? =
        positionMs.takeIf { !isCompleted && it > 0L }
}

/**
 * Whether [this] history row names the same episode as a launch carrying
 * [season]/[episode]/[episodeStreamId].
 *
 * The stream id names the exact episode and is independent of the parent id
 * flavor, so it is the first thing to compare. When the ids differ (an addon's
 * video id against the numbered key, a re-resolved title) the season/episode
 * pair states the same fact. A launch with no episode identity at all is a
 * movie (or a show-level row), whose only in-progress row is the title's own -
 * scoped already to the parent, so it matches.
 */
internal fun WatchHistoryEntity.namesSameEpisode(
    season: Int?,
    episode: Int?,
    episodeStreamId: String?
): Boolean = when {
    !episodeStreamId.isNullOrBlank() && this.episodeStreamId == episodeStreamId -> true
    season != null && episode != null -> this.season == season && this.episode == episode
    else -> true
}
