package com.kennyb1201.kbstream.data.library

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * The two trackers a taste signal can be written back to.
 *
 * Separate legs in every sense: separate queue entries, separate attempt
 * counts, separate guards and separate disable states, so a Simkl failure can
 * never delay, block or drop a Trakt submission and vice versa. They share only
 * the mapping ([TasteRatingMapping]) and this storage shape.
 */
enum class TasteRatingTracker(val label: String) {
    SIMKL("Simkl"),
    TRAKT("Trakt");

    companion object {
        /** The tracker a stored entry names, or null for a value this build does not know. */
        fun from(value: String?): TasteRatingTracker? =
            entries.firstOrNull { it.name == value }
    }
}

/**
 * One pending submission: the title, the tracker, and what to write there.
 *
 * [rating] is always a concrete 1-10 value - on a [removal] it is the value the
 * title carried before the clear, kept because the remove request shares its
 * shape with an add and needs a number in it. [removal] is what makes the call a
 * take-back; the number is presence only and is never read as an opinion.
 *
 * [signal] is the signal as written (LIKE / DISLIKE / NONE), [enqueuedAt] the
 * wall-clock of the newest signal, [attempts] / [lastAttemptAt] / [lastError]
 * the retry bookkeeping ([TasteRatingQueueRules] owns its arithmetic).
 */
@Serializable
data class TasteRatingQueueEntry(
    val tracker: String = "",
    val keys: List<String> = emptyList(),
    val mediaType: String = "",
    val title: String = "",
    val rating: Int = TasteRatingMapping.LIKE_RATING,
    val removal: Boolean = false,
    val signal: String = TasteSignals.NONE,
    val enqueuedAt: Long = 0L,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0L,
    val lastError: String? = null
) {
    /** The tracker this entry names, or null when the blob outlived a rename. */
    val trackerOrNull: TasteRatingTracker get() = TasteRatingTracker.from(tracker)

    /** True when this entry names the same title as [other] on the same tracker. */
    fun sameTargetAs(other: TasteRatingQueueEntry): Boolean =
        tracker == other.tracker && TasteRatingQueueRules.sharesAKey(keys, other.keys)
}

/**
 * The queue's arithmetic, without Android: coalescing, the backoff schedule and
 * the attempt cap.
 *
 * The schedule is 1 min, 5 min, 30 min, 2 h, 12 h, indexed by the number of
 * attempts already made, and the cap is five attempts: an entry that has failed
 * five times is DROPPED when it next comes due, rather than making a sixth
 * attempt. Checked at drain time against the wall clock - no timer is ever
 * scheduled for a retry, so a process that dies between attempts loses nothing
 * but the wait.
 *
 * A server-requested pause (a 429, a spent budget, a revoked grant) is
 * deliberately NOT in here: none of those is a failed attempt, so none of them
 * moves an entry along this schedule.
 */
internal object TasteRatingQueueRules {

    /** How many failed attempts an entry may make before it is dropped. */
    const val MAX_ATTEMPTS = 5

    /** The waits between attempts, indexed by attempts ALREADY made. */
    val BACKOFF_MS: List<Long> = listOf(1L, 5L, 30L, 120L, 720L).map { it * 60_000L }

    /** The wait after [attempts] failed attempts. */
    fun backoffFor(attempts: Int): Long =
        BACKOFF_MS[(attempts - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    /** When [entry] may be tried again: immediately while it has never been tried. */
    fun dueAt(entry: TasteRatingQueueEntry): Long =
        if (entry.attempts <= 0) 0L else entry.lastAttemptAt + backoffFor(entry.attempts)

    /** True when [entry] is due at [nowMs]. */
    fun isDue(entry: TasteRatingQueueEntry, nowMs: Long): Boolean = nowMs >= dueAt(entry)

    /** True when [entry] has spent its attempts and must be dropped instead. */
    fun isSpent(entry: TasteRatingQueueEntry): Boolean = entry.attempts >= MAX_ATTEMPTS

    /**
     * [incoming] placed into [pending], coalescing with the entry it supersedes.
     *
     * Coalescing is the whole point of the queue: a newer signal for the same
     * title on the same tracker REPLACES the pending one IN PLACE, so a
     * like -> dislike flip while the like is still queued sends only the dislike,
     * a clear supersedes a pending submit, and no stale flip is ever sent after a
     * newer one. The replacement is a fresh entry, so a superseded submit's
     * attempt history does not carry over to the signal that replaced it.
     *
     * Titles are matched by a SHARED key rather than an equal key set: the same
     * title signalled from a TMDB rail and from an add-on rail carries different
     * spellings, and one of them overlapping is what makes it one title.
     */
    fun coalesce(
        pending: List<TasteRatingQueueEntry>,
        incoming: TasteRatingQueueEntry
    ): List<TasteRatingQueueEntry> {
        val index = pending.indexOfFirst { it.sameTargetAs(incoming) }
        if (index < 0) return pending + incoming
        return pending.toMutableList().apply { this[index] = incoming }
    }

    /** True when the two key sets name the same title (any shared spelling). */
    fun sharesAKey(a: List<String>, b: List<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val set = b.toHashSet()
        return a.any { it in set }
    }

    /** [entry] after a failed attempt, or null when the cap drops it instead. */
    fun afterFailure(
        entry: TasteRatingQueueEntry,
        nowMs: Long,
        message: String?
    ): TasteRatingQueueEntry? =
        if (isSpent(entry)) null
        else entry.copy(
            attempts = entry.attempts + 1,
            lastAttemptAt = nowMs,
            lastError = message
        )
}

/**
 * The durable submission queue: `taste_rating_queue_v1`, per profile, on disk.
 *
 * Storage is the taste store's own shape (a JSON blob in a profile-namespaced
 * SharedPreferences file) with the same corrupt-blob discipline: a blob that
 * will not parse is served from the last copy that did, and every
 * read-modify-write REFUSES rather than persisting that emptiness. It matters
 * more here than anywhere: an unreadable queue blob that read as "nothing
 * pending" would silently drop the viewer's ratings, and one that read as
 * writable would let the next press overwrite the record of the last few.
 *
 * Profile-scoped by construction ([ProfileStorage.prefsName]): entries queued on
 * one profile wait there and drain only while that profile is active, so no
 * submission can reach an account it was not signalled under.
 */
object TasteRatingQueue {

    private const val TAG = "TASTE_RATING_QUEUE"

    private const val PREFS_BASE = "kbstream_taste_rating_queue"

    /** The one blob this build reads and writes. */
    private const val KEY_QUEUE = "taste_rating_queue_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The last QUEUE that parsed, per profile store.
     *
     * A blob this build cannot read is served from here instead of reading as an
     * empty queue, and [readOrNull] returning null is what makes a write refuse.
     */
    private val lastGood = ConcurrentHashMap<String, Store>()

    /** The newest failure per tracker, for COPY DIAGNOSTICS. */
    @Serializable
    data class LegFailure(val at: Long = 0L, val message: String = "")

    @Serializable
    internal data class Store(
        val pending: List<TasteRatingQueueEntry> = emptyList(),
        val failures: Map<String, LegFailure> = emptyMap()
    )

    /** Every pending entry, oldest first (the blob's own order). */
    fun entries(context: Context): List<TasteRatingQueueEntry> {
        val app = context.applicationContext
        return read(app).pending
    }

    /** The newest failure recorded per tracker, or an empty map. */
    fun failures(context: Context): Map<String, LegFailure> {
        val app = context.applicationContext
        return read(app).failures
    }

    /**
     * Queues [tracker]'s write for one title, coalescing with anything already
     * pending for it.
     *
     * [rating] is what to write, or null for a REMOVAL - the caller's own signal
     * shape, so a clear is a clear. [lastRating] is the number the cleared entry
     * carried, used only for the remove body's presence value.
     *
     * Returns false when the write was refused: no usable keys, or a stored blob
     * this build cannot parse.
     */
    @Synchronized
    fun enqueue(
        context: Context,
        tracker: TasteRatingTracker,
        keys: List<String>,
        mediaType: String,
        title: String,
        rating: Int?,
        lastRating: Int?,
        signal: String,
        atMs: Long
    ): Boolean {
        val app = context.applicationContext
        if (keys.isEmpty()) return false
        val current = readOrNull(app) ?: return false

        val entry = TasteRatingQueueEntry(
            tracker = tracker.name,
            keys = keys,
            mediaType = mediaType,
            title = title,
            rating = rating?.let(TasteRatingMapping::clamp)
                ?: TasteRatingMapping.removalWireRating(lastRating, signal),
            removal = rating == null,
            signal = signal,
            enqueuedAt = atMs
        )
        write(app, current.copy(pending = TasteRatingQueueRules.coalesce(current.pending, entry)))
        Log.i(
            TAG,
            "${tracker.label}: queued ${if (entry.removal) "removal" else "rating ${entry.rating}"} " +
                "for ${entry.title.ifBlank { "a title" }} (${current.pending.size} pending before)"
        )
        return true
    }

    /** Drops one entry, whatever its state. True when it was there to drop. */
    @Synchronized
    fun removeEntry(context: Context, entry: TasteRatingQueueEntry): Boolean {
        val app = context.applicationContext
        val current = readOrNull(app) ?: return false
        val kept = current.pending.filterNot { it.sameTargetAs(entry) }
        if (kept.size == current.pending.size) return false
        write(app, current.copy(pending = kept))
        return true
    }

    /**
     * Records a failed attempt: one more attempt, a later due time, and the
     * error text. True when the entry may be retried; false when the cap has
     * been reached and the entry has been dropped instead.
     */
    @Synchronized
    fun markAttempt(
        context: Context,
        entry: TasteRatingQueueEntry,
        atMs: Long,
        message: String?
    ): Boolean {
        val app = context.applicationContext
        val current = readOrNull(app) ?: return false
        val updated = TasteRatingQueueRules.afterFailure(entry, atMs, message)
        if (updated == null) {
            // The cap: the entry goes, and the reason stays behind in the
            // failure record so COPY DIAGNOSTICS can still name it.
            write(
                app,
                current.copy(
                    pending = current.pending.filterNot { it.sameTargetAs(entry) },
                    failures = current.failures +
                        (entry.tracker to LegFailure(atMs, message.orEmpty()))
                )
            )
            return false
        }
        write(
            app,
            current.copy(pending = current.pending.map { if (it.sameTargetAs(entry)) updated else it })
        )
        return true
    }

    /**
     * Drops an entry the tracker will never accept (unresolvable id, a revoked
     * grant) and records why, so the reason survives in COPY DIAGNOSTICS after
     * the entry itself is gone.
     */
    @Synchronized
    fun dropEntry(
        context: Context,
        entry: TasteRatingQueueEntry,
        atMs: Long,
        message: String
    ) {
        val app = context.applicationContext
        val current = readOrNull(app) ?: return
        write(
            app,
            current.copy(
                pending = current.pending.filterNot { it.sameTargetAs(entry) },
                failures = current.failures + (entry.tracker to LegFailure(atMs, message))
            )
        )
    }

    /** Records a leg-level failure with no entry to attribute it to. */
    @Synchronized
    fun noteFailure(context: Context, tracker: TasteRatingTracker, atMs: Long, message: String) {
        val app = context.applicationContext
        val current = readOrNull(app) ?: return
        noteFailureLocked(app, current, tracker.name, atMs, message)
    }

    private fun noteFailureLocked(
        app: Context,
        current: Store,
        tracker: String,
        atMs: Long,
        message: String?
    ) {
        write(
            app,
            current.copy(failures = current.failures + (tracker to LegFailure(atMs, message.orEmpty())))
        )
    }

    /**
     * Drops every pending entry for ONE tracker, and only that tracker: a
     * disconnect ends that grant, so ratings queued against it must not fire into
     * a later re-link, while the other leg's entries are untouched. Returns how
     * many went.
     */
    @Synchronized
    fun clearTracker(context: Context, tracker: TasteRatingTracker): Int {
        val app = context.applicationContext
        val current = readOrNull(app) ?: return 0
        val kept = current.pending.filterNot { it.tracker == tracker.name }
        val dropped = current.pending.size - kept.size
        if (dropped > 0) write(app, current.copy(pending = kept))
        return dropped
    }

    /**
     * The blob for a READ, falling back to the last copy that parsed so an
     * unreadable blob can never read as "nothing is pending".
     */
    private fun read(context: Context): Store =
        readOrNull(context) ?: lastGood[cacheKey(context)] ?: Store()

    /** The blob for a READ-MODIFY-WRITE: null when it exists and will not parse. */
    private fun readOrNull(context: Context): Store? {
        val raw = prefs(context).getString(KEY_QUEUE, null)
        if (raw == null) return Store()
        val parsed = runCatching {
            json.decodeFromString(Store.serializer(), raw)
        }.getOrNull()
        if (parsed == null) {
            Log.w(
                TAG,
                "queue blob will not parse; serving the last good copy and refusing to write " +
                    "over it"
            )
            return null
        }
        lastGood[cacheKey(context)] = parsed
        return parsed
    }

    private fun write(context: Context, store: Store) {
        prefs(context).edit()
            .putString(KEY_QUEUE, json.encodeToString(Store.serializer(), store))
            .apply()
        lastGood[cacheKey(context)] = store
    }

    private fun cacheKey(context: Context): String =
        ProfileStorage.prefsName(context, PREFS_BASE)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileStorage.prefsName(context.applicationContext, PREFS_BASE),
            Context.MODE_PRIVATE
        )
}
