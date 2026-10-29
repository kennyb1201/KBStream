package com.kennyb1201.kbstream.data.library

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.BackgroundWork
import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.mdblist.MDBLIST_RATINGS_ENABLED
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListRatingOutcome
import com.kennyb1201.kbstream.data.mdblist.mdbListKeyGrant
import com.kennyb1201.kbstream.data.mdblist.submitRatingImpl as submitMdbListRating
import com.kennyb1201.kbstream.data.simkl.SIMKL_RATINGS_ENABLED
import com.kennyb1201.kbstream.data.simkl.SimklRatingOutcome
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.simkl.simklRatingPauseMs
import com.kennyb1201.kbstream.data.simkl.submitRatingImpl as submitSimklRating
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.trakt.TRAKT_RATINGS_ENABLED
import com.kennyb1201.kbstream.data.trakt.TraktRatingOutcome
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import com.kennyb1201.kbstream.data.trakt.submitRatingImpl as submitTraktRating
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What carries a taste signal the last few feet: the queue's drain.
 *
 * ONE place enqueues ([onSignalWritten], called from the taste store's own
 * set/clear path) and nothing else in the app talks to a tracker about taste: no
 * thumbs row, menu, dialog or ViewModel knows this exists, so a surface added
 * later gets submission for free by writing through the store, and the viewer's
 * "Liked: <title>" feedback keeps firing from the local write alone - a tracker
 * outage must never read as a failed thumb press.
 *
 * The drain triggers are the three the spec pins and no others: a signal change
 * (immediately, plus a re-drain that retries whatever is pending), a tracker
 * (re)connect, and the short follow-up described in [scheduleFollowUp]. There is
 * no worker, no periodic poll and no Home-load coupling - a rating that survives
 * to the next press or reconnect is not lost, it is in the durable queue.
 *
 * Failures are a log line, an entry in the queue blob (so COPY DIAGNOSTICS can
 * report the last error per tracker) and nothing the viewer can see. There is no
 * toast, no banner and no snackbar here, on purpose: the local write already
 * succeeded, and only the tracker is behind.
 *
 * The legs are independent in every sense this class can enforce: separate
 * entries, separate attempt counts, separate guards, separate closed states, and
 * one leg's failure never blocks, delays or drops another's.
 *
 * Three legs today (Simkl, Trakt, MDBList), sharing the mapping, the queue and
 * this drain. MDBList differs in one way worth knowing here: it has no connect
 * or disconnect hook, because there is no OAuth flow - its grant IS the pasted
 * per-profile API key, so a changed key re-opens a closed leg by itself and a
 * removed key is a pause (nothing can be sent), never a refusal.
 */
object TasteRatingSubmission {

    private const val TAG = "TASTE_RATING_SUBMIT"

    /**
     * A pause longer than this is left to the next trigger instead of being
     * waited out here: a leg's spacing window is a second or two (worth waiting,
     * or a second thumb in a row would strand until the viewer pressed something
     * else), while a 429's `Retry-After` is minutes or hours and idling a
     * coroutine for it is exactly what the next press and the reconnect are for.
     */
    private const val FOLLOW_UP_MAX_WAIT_MS = 3_000L

    /** One drain at a time, process-wide. */
    private val drainMutex = Mutex()

    /** At most one deferred re-drain in flight. */
    private val followUpScheduled = AtomicBoolean(false)

    /**
     * The grant each leg was closed for.
     *
     * A 401/403 drops its entry and closes the leg, and the leg re-opens only
     * when the profile holds a DIFFERENT grant - i.e. when it reconnects. That is
     * the reconnect doing the re-enabling, as pinned, and it costs no call-site
     * contract: no retry can reopen a retired grant, and no automatic re-auth
     * exists to loop on.
     */
    private val closedGrants = ConcurrentHashMap<TasteRatingTracker, String>()

    /** Legs whose closure has been logged once this process (no per-attempt spam). */
    private val loggedClosures = ConcurrentHashMap.newKeySet<TasteRatingTracker>()

    /** Whether a leg may send anything at all - the landing gate, per tracker. */
    fun enabled(tracker: TasteRatingTracker): Boolean = when (tracker) {
        TasteRatingTracker.SIMKL -> SIMKL_RATINGS_ENABLED
        TasteRatingTracker.TRAKT -> TRAKT_RATINGS_ENABLED
        TasteRatingTracker.MDBLIST -> MDBLIST_RATINGS_ENABLED
    }

    /** True when at least one leg is open, i.e. when queuing can lead anywhere. */
    fun anyEnabled(): Boolean = TasteRatingTracker.entries.any(::enabled)

    /**
     * The store's submission hook: queue this title's new signal for every open
     * leg and start a drain.
     *
     * [rating] is what to write on the trackers' 1-10 scale, or null for a
     * REMOVAL (a cleared signal is the viewer taking their rating back, not a
     * low rating). [lastRating] is the number the cleared entry carried, kept
     * only for the remove body's presence value.
     *
     * Synchronous and cheap (one blob write per leg, coalescing with anything
     * already pending for this title), so a thumb press never waits on it; an
     * empty build (every leg closed by its landing gate) does nothing at all.
     */
    fun onSignalWritten(
        context: Context,
        keys: List<String>,
        mediaType: String,
        title: String,
        rating: Int?,
        lastRating: Int?,
        signal: String,
        atMs: Long = System.currentTimeMillis()
    ) {
        if (!anyEnabled()) return
        val app = context.applicationContext
        var queued = false
        TasteRatingTracker.entries.filter(::enabled).forEach { tracker ->
            val wrote = TasteRatingQueue.enqueue(
                context = app,
                tracker = tracker,
                keys = keys,
                mediaType = mediaType,
                title = title,
                rating = rating,
                lastRating = lastRating,
                signal = signal,
                atMs = atMs
            )
            queued = queued || wrote
        }
        if (queued) drainSoon(app)
    }

    /**
     * A tracker finished connecting: its leg is open again and anything the
     * profile left pending is due.
     */
    fun onTrackerConnected(context: Context, tracker: TasteRatingTracker) {
        closedGrants.remove(tracker)
        if (!enabled(tracker)) return
        drainSoon(context.applicationContext)
    }

    /**
     * A tracker was disconnected: drop ITS pending entries and nothing else.
     *
     * The grant is gone, so a queued rating must not fire into whatever grant a
     * later re-link produces; the other leg's entries are untouched either way.
     * Ratings already sent are not removed from the tracker - a disconnect does
     * not take a rating back (see the disconnect rules).
     */
    fun onTrackerDisconnected(context: Context, tracker: TasteRatingTracker) {
        val app = context.applicationContext
        closedGrants.remove(tracker)
        val dropped = TasteRatingQueue.clearTracker(app, tracker)
        if (dropped > 0) {
            Log.i(TAG, "${tracker.label}: dropped $dropped pending rating(s) on disconnect")
        }
    }

    /**
     * Sends everything due, oldest first, once.
     *
     * Re-entrant-safe (one drain at a time) and profile-safe: if the active
     * profile changes mid-drain the rest of this pass stops, because the entries
     * it is holding belong to the profile that queued them and may only drain
     * while that profile is active.
     */
    suspend fun drain(context: Context) {
        val app = context.applicationContext
        if (!anyEnabled()) return
        if (!drainMutex.tryLock()) return
        try {
            val profileAtStart = activeProfileId()
            for (entry in TasteRatingQueue.entries(app)) {
                if (activeProfileId() != profileAtStart) return
                val tracker = entry.trackerOrNull ?: continue
                if (!enabled(tracker)) continue
                if (isLegClosed(app, tracker)) continue

                val now = System.currentTimeMillis()
                if (TasteRatingQueueRules.isSpent(entry)) {
                    // The cap: dropped rather than tried a sixth time, with the
                    // reason left behind for diagnostics.
                    Log.w(
                        TAG,
                        "${tracker.label}: giving up on ${entry.title.ifBlank { "a title" }} " +
                            "after ${entry.attempts} attempts (${entry.lastError ?: "no detail"})"
                    )
                    TasteRatingQueue.dropEntry(
                        app,
                        entry,
                        now,
                        "gave up after ${entry.attempts} attempts"
                    )
                    continue
                }
                if (!TasteRatingQueueRules.isDue(entry, now)) continue

                val paused = when (tracker) {
                    TasteRatingTracker.TRAKT -> drainTrakt(app, entry, now)
                    TasteRatingTracker.SIMKL -> drainSimkl(app, entry, now)
                    TasteRatingTracker.MDBLIST -> drainMdbList(app, entry, now)
                }
                if (paused) {
                    val waitMs = remainingPauseMs(app, tracker)
                    if (waitMs in 1..FOLLOW_UP_MAX_WAIT_MS) scheduleFollowUp(app, waitMs)
                }
            }
        } finally {
            drainMutex.unlock()
        }
    }

    /** One Trakt attempt. True when the leg asked to be tried again shortly. */
    private suspend fun drainTrakt(
        app: Context,
        entry: TasteRatingQueueEntry,
        now: Long
    ): Boolean {
        val repo = TraktRepository.getInstance(app)
        val ids = trackerIdsOf(entry)
        val outcome = repo.submitTraktRating(
            parentId = ids.parentId.orEmpty(),
            tmdbId = ids.tmdbId,
            mediaType = entry.mediaType,
            rating = entry.rating,
            removal = entry.removal,
            ratedAtIso = TasteRatingMapping.ratedAtIso(entry.enqueuedAt)
        )
        when (outcome) {
            TraktRatingOutcome.LANDED -> TasteRatingQueue.removeEntry(app, entry)
            TraktRatingOutcome.NOT_FOUND -> {
                Log.w(TAG, "Trakt could not match ${entry.title.ifBlank { "a title" }}")
                TasteRatingQueue.dropEntry(
                    app,
                    entry,
                    now,
                    "trakt.tv could not match the title"
                )
            }
            TraktRatingOutcome.NO_IDS -> TasteRatingQueue.dropEntry(
                app,
                entry,
                now,
                "no id Trakt can be addressed with"
            )
            TraktRatingOutcome.AUTH -> {
                TasteRatingQueue.dropEntry(app, entry, now, "the Trakt session was refused")
                closeLeg(app, TasteRatingTracker.TRAKT)
            }
            TraktRatingOutcome.PAUSED -> Unit
            TraktRatingOutcome.NO_ANSWER -> TasteRatingQueue.markAttempt(
                app,
                entry,
                now,
                "trakt.tv did not answer"
            )
        }
        return outcome == TraktRatingOutcome.PAUSED
    }

    /** One Simkl attempt. True when the leg asked to be tried again shortly. */
    private suspend fun drainSimkl(
        app: Context,
        entry: TasteRatingQueueEntry,
        now: Long
    ): Boolean {
        val repo = SimklRepository.getInstance(app)
        val ids = trackerIdsOf(entry)
        val outcome = repo.submitSimklRating(
            parentId = ids.parentId.orEmpty(),
            tmdbId = ids.tmdbId,
            mediaType = entry.mediaType,
            rating = entry.rating,
            removal = entry.removal,
            ratedAtIso = TasteRatingMapping.ratedAtIso(entry.enqueuedAt)
        )
        when (outcome) {
            SimklRatingOutcome.LANDED -> TasteRatingQueue.removeEntry(app, entry)
            SimklRatingOutcome.NOT_FOUND -> {
                Log.w(TAG, "Simkl could not match ${entry.title.ifBlank { "a title" }}")
                TasteRatingQueue.dropEntry(
                    app,
                    entry,
                    now,
                    "simkl.com could not match the title"
                )
            }
            SimklRatingOutcome.NO_IDS -> TasteRatingQueue.dropEntry(
                app,
                entry,
                now,
                "no id Simkl can be addressed with"
            )
            SimklRatingOutcome.AUTH -> {
                TasteRatingQueue.dropEntry(app, entry, now, "the Simkl session was refused")
                closeLeg(app, TasteRatingTracker.SIMKL)
            }
            SimklRatingOutcome.PAUSED -> Unit
            SimklRatingOutcome.NO_ANSWER -> TasteRatingQueue.markAttempt(
                app,
                entry,
                now,
                "simkl.com did not answer"
            )
        }
        return outcome == SimklRatingOutcome.PAUSED
    }

    /**
     * One MDBList attempt.
     *
     * Always false: unlike the other two legs, this one has no spacing window of
     * its own to wait out - MDBList's per-key budget interceptor is the guard, it
     * refuses or parks at the HTTP layer, and it synthesizes its park with 429,
     * which the leg answers PAUSED. A follow-up wait would therefore never have
     * anything to wait for, and scheduling one would re-drain for nothing.
     */
    private suspend fun drainMdbList(
        app: Context,
        entry: TasteRatingQueueEntry,
        now: Long
    ): Boolean {
        val ids = trackerIdsOf(entry)
        val outcome = MdbListClient.submitMdbListRating(
            context = app,
            parentId = ids.parentId.orEmpty(),
            tmdbId = ids.tmdbId,
            mediaType = entry.mediaType,
            rating = entry.rating,
            removal = entry.removal,
            ratedAtIso = TasteRatingMapping.ratedAtIso(entry.enqueuedAt)
        )
        when (outcome) {
            MdbListRatingOutcome.LANDED -> TasteRatingQueue.removeEntry(app, entry)
            MdbListRatingOutcome.NOT_FOUND -> {
                Log.w(TAG, "MDBList could not match ${entry.title.ifBlank { "a title" }}")
                TasteRatingQueue.dropEntry(
                    app,
                    entry,
                    now,
                    "mdblist.com could not match the title"
                )
            }
            MdbListRatingOutcome.NO_IDS -> TasteRatingQueue.dropEntry(
                app,
                entry,
                now,
                "no id MDBList can be addressed with"
            )
            MdbListRatingOutcome.AUTH -> {
                TasteRatingQueue.dropEntry(app, entry, now, "the MDBList key was refused")
                closeLeg(app, TasteRatingTracker.MDBLIST)
            }
            MdbListRatingOutcome.PAUSED -> Unit
            MdbListRatingOutcome.NO_ANSWER -> TasteRatingQueue.markAttempt(
                app,
                entry,
                now,
                "mdblist.com did not answer"
            )
        }
        return false
    }

    /**
     * The tracker ids an entry carries, read back out of the store's own key
     * spellings (see [TasteRatingMapping.imdbIdOf] / [tmdbIdOf]).
     *
     * A title the store only knows by name resolves to nothing here and is
     * dropped by the leg as NO_IDS - refused before any request, which is the
     * honest answer for a title neither tracker can be addressed with.
     */
    private fun trackerIdsOf(entry: TasteRatingQueueEntry): TrackerIds {
        val imdb = TasteRatingMapping.imdbIdOf(entry.keys)
        val tmdb = TasteRatingMapping.tmdbIdOf(entry.keys)
        return TrackerIds(
            parentId = imdb ?: tmdb?.let { "tmdb:$it" },
            tmdbId = tmdb
        )
    }

    private data class TrackerIds(val parentId: String?, val tmdbId: Int?)

    /** True when this leg's recorded closure is for the grant it still holds. */
    private fun isLegClosed(app: Context, tracker: TasteRatingTracker): Boolean {
        val closedFor = closedGrants[tracker] ?: return false
        val current = currentGrant(app, tracker)
        if (current != null && current == closedFor) return true
        // A different grant (a reconnect) re-opens it, and so does no grant at
        // all - nothing can be sent either way, and the leg must not stay
        // closed for a session that no longer exists.
        closedGrants.remove(tracker)
        return false
    }

    /** Closes a leg for the grant that refused it, logging the closure once. */
    private fun closeLeg(app: Context, tracker: TasteRatingTracker) {
        val grant = currentGrant(app, tracker) ?: return
        closedGrants[tracker] = grant
        if (loggedClosures.add(tracker)) {
            Log.e(
                TAG,
                "${tracker.label} refused this profile's rating writes; the leg stays closed " +
                    "until it reconnects"
            )
            // MDBList has no connect flow to reconnect through: its grant is the
            // pasted per-profile key, so the remedy that actually works there is
            // replacing the key. Same closure, different instruction.
            val remedy = if (tracker == TasteRatingTracker.MDBLIST) {
                "paste a new key"
            } else {
                "reconnect"
            }
            TasteRatingQueue.noteFailure(
                app,
                tracker,
                System.currentTimeMillis(),
                "${tracker.label} refused the session ($remedy to resume)"
            )
        }
    }

    /** How long this leg is held back for; 0 when a call may go now. */
    private fun remainingPauseMs(app: Context, tracker: TasteRatingTracker): Long =
        when (tracker) {
            TasteRatingTracker.TRAKT -> TraktRepository.getInstance(app).guard.blockedForMs()
            TasteRatingTracker.SIMKL -> simklRatingPauseMs()
            // MDBList's pause lives in the budget interceptor, which parks the
            // key itself and answers 429 while parked: the leg has no window of
            // its own for this drain to wait out.
            TasteRatingTracker.MDBLIST -> 0L
        }

    /**
     * The grant a leg's closure is recorded against, or null.
     *
     * A session token for the two OAuth trackers; for MDBList, the pasted key's
     * non-reversible tag (see `mdbListKeyGrant`) - the same comparison, so a
     * replaced key re-opens that leg exactly as a reconnect re-opens the others.
     */
    private fun currentGrant(app: Context, tracker: TasteRatingTracker): String? =
        when (tracker) {
            TasteRatingTracker.TRAKT -> TraktRepository.getInstance(app).store.accessToken()
            TasteRatingTracker.SIMKL -> SimklRepository.getInstance(app).getSavedAccessToken()
            // Not a token: the pasted key's own hash tag. A changed key is a
            // different grant and re-opens the leg with no connect flow, and the
            // raw secret never reaches this map.
            TasteRatingTracker.MDBLIST -> mdbListKeyGrant(app)
        }

    /**
     * One deferred re-drain, after a leg's own spacing window.
     *
     * This is the current drain FINISHING, not a fourth trigger: a second thumb
     * inside the spacing window would otherwise leave its entry queued until the
     * viewer pressed something else. Bounded three ways - only for a pause under
     * [FOLLOW_UP_MAX_WAIT_MS], at most one follow-up in flight, and the follow-up
     * runs the same drain, which will only schedule another if it again leaves a
     * short pause behind.
     */
    private fun scheduleFollowUp(app: Context, waitMs: Long) {
        if (!followUpScheduled.compareAndSet(false, true)) return
        BackgroundWork.launch {
            try {
                delay(waitMs.coerceAtLeast(1L))
                drain(app)
            } finally {
                followUpScheduled.set(false)
            }
        }
    }

    private fun drainSoon(app: Context) {
        BackgroundWork.launch { drain(app) }
    }

    private fun activeProfileId(): String = ProfileManager.activeProfile.value?.id ?: ""

    /**
     * The COPY DIAGNOSTICS lines: what is queued per tracker and the last
     * submission failure each one hit.
     *
     * Empty while nothing has been queued and nothing has failed AND every leg
     * is open, so a clean report stays as short as it was; a closed or
     * gated-off leg is worth naming, because "my ratings never reach Simkl" is
     * exactly the report this has to answer.
     */
    fun statusLines(context: Context): List<String> {
        val app = context.applicationContext
        val pending = TasteRatingQueue.entries(app)
        val failures = TasteRatingQueue.failures(app)
        val anyClosed = TasteRatingTracker.entries.any(::enabled).not()
        if (pending.isEmpty() && failures.isEmpty() && !anyClosed) return emptyList()

        val parts = mutableListOf("pending=${pending.size}")
        TasteRatingTracker.entries.forEach { tracker ->
            val queued = pending.count { it.tracker == tracker.name }
            val failure = failures[tracker.name]
            parts += buildString {
                append(tracker.label)
                append(": ")
                append(if (enabled(tracker)) "on" else "off (probe gate)")
                append(" queued=").append(queued)
                if (failure != null && failure.message.isNotBlank()) {
                    append(" last error ").append(failure.message)
                    if (failure.at > 0L) {
                        append(" at ").append(
                            DateFormats.time(failure.at, DateFormats.DATE_TIME_SECONDS)
                        )
                    }
                }
            }
        }
        return listOf("taste ratings: " + parts.joinToString(" \u00b7 "))
    }
}
