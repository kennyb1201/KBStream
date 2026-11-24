package com.kennyb1201.kbstream.data.cast

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.PlaybackHistoryWriter
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.ui.player.PlaybackHistoryIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The cast session's own history, written from the phone while the television
 * plays.
 *
 * A cast is the app's second "playback happens somewhere else" path - the
 * External engine is the first - and it carries the same obligation: the row
 * that makes Continue Watching resume where the viewer actually got to. The
 * players write it from their own position ticks; nothing on the television can
 * write anything, so the sender polls the receiver and writes here.
 *
 * The row is the SAME row the in-app players would write for that title:
 * [PlaybackHistoryIds.historyId] derives the id from the launch's own identity
 * (episode stream id, then show + season/episode, then the title), and
 * [PlaybackHistoryWriter.write] resolves the profile at write time - so a cast
 * started on profile 1 and left running through a switch still files under
 * profile 1, exactly like a player session.
 *
 * Scrobbling to the trackers is deliberately NOT here yet: the players' tracker
 * fan-out is a per-engine pipeline (Trakt + Simkl + MDBList, each with its own
 * id resolution and progress policy), and a second, thinner copy of it in the
 * sender would be worse than none. That is the remaining half of this spec.
 */
internal object CastWatchSession {

    private const val TAG = "CAST_WATCH"

    /** How often the receiver's position is read and filed. */
    private const val TICK_MS = 10_000L

    /** Where a title counts as watched, the players' own rule. */
    private const val COMPLETION_FRACTION = 0.9

    /**
     * What one cast needs to write its row. Built from the launch, so the card
     * the rail draws is the same card a local playback would have made.
     */
    internal data class Seed(
        val parentId: String,
        val type: String,
        val name: String,
        val poster: String?,
        val season: Int?,
        val episode: Int?,
        val episodeStreamId: String?,
        val streamUrl: String?,
        val startPositionMs: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var seed: Seed? = null

    /** The last position the receiver reported, for the closing write. */
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var completionFiled = false

    /** Starts watching a cast that has just been loaded onto the receiver. */
    fun start(context: Context, seed: Seed) {
        stop(context)
        this.seed = seed
        lastPositionMs = seed.startPositionMs
        lastDurationMs = 0L
        completionFiled = false
        // The start is filed immediately: a cast the viewer leaves inside the
        // first tick would otherwise leave no row at all, and the app's whole
        // contract is that a title you started is one you can resume.
        file(context, seed.startPositionMs, 0L, completed = false)
        val attached = CastSender.watchPosition(
            context.applicationContext,
            TICK_MS
        ) { position, playing, duration ->
            lastPositionMs = position
            lastDurationMs = duration
            if (playing) {
                file(
                    context.applicationContext,
                    position,
                    duration,
                    completed = isComplete(position, duration)
                )
            }
        }
        if (!attached) {
            Log.w(TAG, "cast position watch unavailable; the start row is all there is")
        }
    }

    /** Ends the watch and files where the viewer actually stopped. */
    fun stop(context: Context) {
        CastSender.stopWatching(context.applicationContext)
        if (seed == null) return
        if (lastPositionMs > 0L || lastDurationMs > 0L) {
            file(
                context.applicationContext,
                lastPositionMs,
                lastDurationMs,
                completed = isComplete(lastPositionMs, lastDurationMs)
            )
            Log.d(TAG, "cast ended at ${lastPositionMs}ms of ${lastDurationMs}ms")
        }
        seed = null
    }

    private fun isComplete(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs >= (durationMs * COMPLETION_FRACTION).toLong()

    private fun file(context: Context, positionMs: Long, durationMs: Long, completed: Boolean) {
        val s = seed ?: return
        if (completed && completionFiled) return
        if (completed) completionFiled = true
        val entry = WatchHistoryEntity(
            id = PlaybackHistoryIds.historyId(
                parentId = s.parentId,
                season = s.season,
                episode = s.episode,
                episodeStreamId = s.episodeStreamId
            ),
            parentId = s.parentId,
            type = s.type,
            name = s.name,
            poster = s.poster,
            streamUrl = s.streamUrl,
            season = s.season,
            episode = s.episode,
            // The receiver reports no duration before it starts, so a resumed
            // row keeps the duration it already had rather than overwriting it
            // with 0 - the writer preserves completion, it cannot preserve a
            // field the caller zeroed.
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = if (durationMs > 0L) durationMs else positionMs.coerceAtLeast(1L),
            updatedAt = System.currentTimeMillis(),
            isCompleted = completed,
            completedAt = if (completed) System.currentTimeMillis() else null
        )
        val profileId = PlaybackHistoryWriter.profileIdForNewSession(context)
        scope.launch {
            PlaybackHistoryWriter.write(context.applicationContext, profileId, entry)
        }
    }
}
