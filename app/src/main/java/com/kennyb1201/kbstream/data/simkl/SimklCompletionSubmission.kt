package com.kennyb1201.kbstream.data.simkl

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.BackgroundWork
import com.kennyb1201.kbstream.data.sync.ProfileManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Delivers the queued Simkl history writes once the playback park lifts.
 *
 * The queue ([SimklCompletionQueue]) is what survives a session; this is what
 * empties it. It replays each entry through the SAME impl that would have sent
 * it live ([SimklHistoryWrites]), so the side effects a landed write is supposed
 * to have - `invalidateWatchedSnapshots`, closing the open playback session so
 * the episode cannot resurface in Continue Watching - happen on a drained write
 * exactly as they do on an immediate one.
 *
 * Triggers, and only these:
 *  - app start (see MainApplication);
 *  - a park-lift wake, scheduled here when the remaining park is short enough
 *    for a coroutine to sit through, and otherwise left to the next trigger
 *    (a 397-minute park outlives any session);
 *  - the next Simkl write that actually goes out (a landed write means the leg
 *    is open again, which is the moment a queue should move).
 *
 * Re-entrant-safe: one drain at a time, and a trigger that arrives mid-drain asks
 * for one more pass instead of being dropped.
 */
object SimklCompletionSubmission {

    private const val TAG = "SIMKL_COMPLETIONS"

    /**
     * The longest park this process will wait out inside a coroutine.
     *
     * A `Retry-After` is minutes or hours; an idling coroutine does not survive
     * the viewer leaving the app, so anything longer is left to the next app
     * start rather than pretending a timer exists.
     */
    private const val MAX_INLINE_WAIT_MS = 15L * 60_000L

    private val drainMutex = Mutex()

    /** Set when a trigger arrived while a drain was running. */
    private val drainAgain = AtomicBoolean(false)

    /** At most one park-lift wake in flight. */
    private val wakeScheduled = AtomicBoolean(false)

    /** Ask for a drain because something may now be deliverable. */
    fun requestDrain(context: Context) {
        val app = context.applicationContext
        if (SimklCompletionQueue.depth(app) == 0) return
        BackgroundWork.launch { drain(app) }
    }

    /** Sends everything due, oldest first, once. */
    suspend fun drain(context: Context) {
        val app = context.applicationContext
        if (!drainMutex.tryLock()) {
            drainAgain.set(true)
            return
        }
        try {
            do {
                drainAgain.set(false)
                drainOnce(app)
            } while (drainAgain.get())
        } finally {
            drainMutex.unlock()
        }
    }

    /** One pass over the queue; the caller holds [drainMutex]. */
    private suspend fun drainOnce(app: Context) {
        val entries = SimklCompletionQueue.entries(app)
        if (entries.isEmpty()) return
        val repo = SimklRepository.getInstance(app)
        if (!repo.isConfigured() || !repo.hasToken()) return

        val profileAtStart = activeProfileId()
        // A park in force means nothing in this pass can be sent: the impls
        // would each answer "parked", and the only useful thing to do is wait
        // for the park to lift (F1 + F2).
        val parkedMs = simklPlaybackParkMs()
        if (parkedMs > 0L) {
            Log.i(TAG, "drain held: playback leg parked for ${parkedMs}ms")
            scheduleWake(app, parkedMs)
            return
        }

        for (entry in entries) {
            // Departing profile: the entries belong to the profile that queued
            // them, and Simkl auth is per profile.
            if (activeProfileId() != profileAtStart) return
            if (entry.profileId.isNotEmpty() && entry.profileId != profileAtStart) continue

            val now = System.currentTimeMillis()
            if (entry.kindOrNull == null) continue
            if (SimklCompletionQueueRules.isSpent(entry)) {
                Log.w(
                    TAG,
                    "giving up on ${entry.kind} for ${entry.title ?: entry.imdbId} after " +
                        "${entry.attempts} attempts (${entry.lastError ?: "no detail"})"
                )
                SimklCompletionQueue.dropEntry(
                    app,
                    entry,
                    now,
                    "gave up after ${entry.attempts} attempts"
                )
                continue
            }
            if (!SimklCompletionQueueRules.isDue(entry, now)) {
                scheduleWake(app, SimklCompletionQueueRules.dueAt(entry) - now)
                continue
            }

            val landed = send(repo, entry)
            if (landed) {
                SimklCompletionQueue.removeEntry(app, entry)
                Log.i(
                    TAG,
                    "${entry.kind} drained for ${entry.title ?: entry.imdbId} " +
                        "(s=${entry.season} eps=${entry.episodes.size})"
                )
                continue
            }

            // A 429 mid-drain re-parks the leg; that is not an attempt, and the
            // rest of the queue must not be walked against a closed door.
            val reopenedPark = simklPlaybackParkMs()
            if (reopenedPark > 0L) {
                Log.i(TAG, "drain stopped: 429 re-parked the leg for ${reopenedPark}ms")
                scheduleWake(app, reopenedPark)
                return
            }
            if (!SimklCompletionQueue.markAttempt(app, entry, now, "simkl.com did not answer")) {
                Log.w(
                    TAG,
                    "${entry.kind} dropped for ${entry.title ?: entry.imdbId} after " +
                        "${entry.attempts} attempts"
                )
            }
        }
    }

    /**
     * Replays one entry through the impl that would have sent it live.
     *
     * Queueing is switched off for these replays, which is what makes the answer
     * usable: the impl then answers false instead of re-queueing itself, so a
     * false here means "not delivered" and the drain knows to keep the entry.
     */
    private suspend fun send(repo: SimklRepository, entry: SimklCompletionEntry): Boolean =
        when (entry.kindOrNull) {
            SimklCompletionKind.PUSH_MOVIE -> repo.pushWatchedMovieImpl(
                imdbId = entry.imdbId,
                title = entry.title,
                tmdbId = entry.tmdbId,
                queueWhenParked = false
            )

            SimklCompletionKind.PUSH_SHOW -> repo.pushWatchedShowImpl(
                showImdbId = entry.imdbId,
                title = entry.title,
                tmdbId = entry.tmdbId,
                queueWhenParked = false
            )

            SimklCompletionKind.PUSH_EPISODE -> repo.pushWatchedEpisodeImpl(
                showImdbId = entry.imdbId,
                season = entry.season,
                episode = entry.episodes.firstOrNull() ?: 0,
                title = entry.title,
                tmdbId = entry.tmdbId,
                queueWhenParked = false
            )

            SimklCompletionKind.PUSH_SEASON -> repo.pushWatchedSeasonImpl(
                showImdbId = entry.imdbId,
                season = entry.season,
                episodes = entry.episodes,
                title = entry.title,
                tmdbId = entry.tmdbId,
                queueWhenParked = false
            )

            // A kind this build does not know: the blob outlived a rename, and
            // there is nothing to send. The drain skips those entries before it
            // gets here, so this arm is unreachable rather than a decision.
            null -> false
        }

    /**
     * Wakes once, [waitMs] from now, to try again - if the wait is short enough
     * for this process to sit through.
     */
    private fun scheduleWake(app: Context, waitMs: Long) {
        if (waitMs <= 0L || waitMs > MAX_INLINE_WAIT_MS) return
        if (!wakeScheduled.compareAndSet(false, true)) return
        BackgroundWork.launch {
            try {
                delay(waitMs.coerceAtLeast(1_000L))
                drain(app)
            } finally {
                wakeScheduled.set(false)
            }
        }
    }

    private fun activeProfileId(): String = ProfileManager.activeProfile.value?.id ?: ""

    /**
     * The COPY DIAGNOSTICS line (F3).
     *
     * The field paste could not prove why three completions never reached Simkl:
     * the playback park is invisible in the export (only the RATINGS park prints),
     * and a queued completion did not exist to count. Both are on one line now,
     * because they are one question - is the leg held, and what is waiting on it.
     */
    fun statusLine(context: Context): String {
        val app = context.applicationContext
        val parkedMs = simklPlaybackParkMs()
        val park = if (parkedMs > 0L) {
            val label = simklPlaybackParkLimitLabel()?.let { " (429 $it)" }.orEmpty()
            "parked ${(parkedMs / 60_000L).coerceAtLeast(0L)}min$label"
        } else {
            "free"
        }
        val queued = SimklCompletionQueue.depth(app)
        val failure = SimklCompletionQueue.failure(app)
        val tail = failure
            ?.takeIf { it.message.isNotBlank() }
            ?.let { " · last drain error ${it.message}" }
            .orEmpty()
        return "simkl playback: $park · completions queued=$queued$tail"
    }
}
