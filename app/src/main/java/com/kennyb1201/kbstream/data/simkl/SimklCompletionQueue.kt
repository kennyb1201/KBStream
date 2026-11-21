package com.kennyb1201.kbstream.data.simkl

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * The durable completion queue: `simkl_completion_queue_v1`, per profile, on disk.
 *
 * Why it exists (spec 2026-11-20, F2): before this, a Simkl completion lived only
 * in the players' in-session latch. A playback park (Simkl's quota spent, 397
 * minutes in the field capture) made every completion fail, the latch retried on
 * the next tick, and when the viewer left the app the completions were gone -
 * Simkl never learned about three episodes KBStream's own history had every
 * second of. Local history stays authoritative; this only stops the tracker's
 * copy from being lost with the session.
 *
 * Storage is the ratings queue's shape - a JSON blob in a profile-namespaced
 * SharedPreferences file - with the same corrupt-blob discipline: a blob that
 * will not parse serves the last copy that did, and every read-modify-write
 * REFUSES rather than persisting that emptiness. A queue that read as "nothing
 * pending" would silently drop the viewer's completions.
 *
 * Profile-scoped by construction ([ProfileStorage.prefsName]): entries queued on
 * one profile wait there and drain only while that profile is active, so no
 * completion can reach an account it was not watched under.
 *
 * The entry's shape and the retry arithmetic live in [SimklCompletionQueueRules],
 * which has no Android in it and is pinned by a plain JVM test.
 */
object SimklCompletionQueue {

    private const val TAG = "SIMKL_COMPLETION_QUEUE"

    private const val PREFS_BASE = "kbstream_simkl_completion_queue"

    /** The one blob this build reads and writes. */
    private const val KEY_QUEUE = "simkl_completion_queue_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The last queue that parsed, per profile store. */
    private val lastGood = ConcurrentHashMap<String, List<SimklCompletionEntry>>()

    /**
     * The newest failure a drain recorded, for COPY DIAGNOSTICS.
     *
     * Kept outside the entries because the entry is deleted at exactly the moment
     * this is written: without it a paste could say the queue was empty and never
     * that the leg gave up on something.
     */
    @Serializable
    internal data class Failure(
        val at: Long = 0L,
        val message: String = "",
        val cause: String = ""
    )

    @Serializable
    internal data class Store(
        val pending: List<SimklCompletionEntry> = emptyList(),
        val failure: Failure? = null
    )

    /** Every pending entry, oldest first. */
    fun entries(context: Context): List<SimklCompletionEntry> = read(context).pending

    /** How many writes are waiting. Printed by the diagnostics line (F3). */
    fun depth(context: Context): Int = entries(context).size

    /** The newest drain failure, or null. */
    fun failure(context: Context): Failure? = read(context).failure

    /**
     * Queues [entry] for delivery, collapsing a duplicate of the same write.
     *
     * Returns false when the write was refused: no usable id, a kind this build
     * does not know, or a stored blob this build cannot parse (writing over an
     * unreadable one would lose it).
     */
    @Synchronized
    fun enqueue(context: Context, entry: SimklCompletionEntry): Boolean {
        val app = context.applicationContext
        if (entry.imdbId.isBlank()) return false
        if (entry.kindOrNull == null) return false
        val current = readOrNull(app) ?: return false
        val updated = SimklCompletionQueueRules.coalesce(current.pending, entry)
        write(app, current.copy(pending = updated))
        Log.i(
            TAG,
            "${entry.kind} queued for ${entry.title ?: entry.imdbId} " +
                "(s=${entry.season} eps=${entry.episodes.size}); ${updated.size} pending"
        )
        return true
    }

    /** Drops one entry. True when it was there to drop. */
    @Synchronized
    fun removeEntry(context: Context, entry: SimklCompletionEntry): Boolean {
        val app = context.applicationContext
        val current = readOrNull(app) ?: return false
        val kept = current.pending.filterNot { it.targetKey == entry.targetKey }
        if (kept.size == current.pending.size) return false
        write(app, current.copy(pending = kept))
        return true
    }

    /**
     * Records a failed attempt: one more attempt, a later due time, and the error
     * text. True when the entry may be retried; false when the cap has been
     * reached and the entry has been dropped instead.
     */
    @Synchronized
    fun markAttempt(
        context: Context,
        entry: SimklCompletionEntry,
        atMs: Long,
        message: String?
    ): Boolean {
        val app = context.applicationContext
        val current = readOrNull(app) ?: return false
        val updated = SimklCompletionQueueRules.afterFailure(entry, atMs, message)
        if (updated == null) {
            write(
                app,
                current.copy(
                    pending = current.pending.filterNot { it.targetKey == entry.targetKey },
                    failure = Failure(atMs, message.orEmpty(), message.orEmpty())
                )
            )
            return false
        }
        write(
            app,
            current.copy(
                pending = current.pending.map {
                    if (it.targetKey == entry.targetKey) updated else it
                }
            )
        )
        return true
    }

    /**
     * Drops an entry Simkl will never accept, leaving the reason behind for the
     * diagnostics line.
     */
    @Synchronized
    fun dropEntry(context: Context, entry: SimklCompletionEntry, atMs: Long, message: String) {
        val app = context.applicationContext
        val current = readOrNull(app) ?: return
        write(
            app,
            current.copy(
                pending = current.pending.filterNot { it.targetKey == entry.targetKey },
                failure = Failure(atMs, message, message)
            )
        )
    }

    /**
     * An UNMARK cancels the queued push it would undo.
     *
     * The removal legs are the viewer's intent and are not queued themselves, but
     * they must not be overtaken: an episode queued during a park and then
     * unmarked before the park lifts would otherwise land after the unmark and
     * resurrect the episode on Simkl.
     */
    @Synchronized
    fun cancelQueuedPushes(
        context: Context,
        imdbId: String,
        kind: SimklCompletionKind,
        season: Int = 0,
        episodes: List<Int> = emptyList()
    ): Int {
        val app = context.applicationContext
        val current = readOrNull(app) ?: return 0
        val kept = current.pending.filterNot { entry ->
            entry.imdbId == imdbId && covers(entry, kind, season, episodes)
        }
        val dropped = current.pending.size - kept.size
        if (dropped > 0) {
            write(app, current.copy(pending = kept))
            Log.i(TAG, "cancelled $dropped queued push(es) for $imdbId on an unmark")
        }
        return dropped
    }

    /**
     * True when [entry] is a write the unmark of [kind]/[season]/[episodes] takes
     * back.
     *
     * Deliberately generous where that is the safe direction: removing a WHOLE
     * show cancels every queued write for it, and removing a season cancels that
     * season's episode pushes. Nothing here can cancel a write for a DIFFERENT
     * title or a different season.
     */
    private fun covers(
        entry: SimklCompletionEntry,
        kind: SimklCompletionKind,
        season: Int,
        episodes: List<Int>
    ): Boolean {
        val entryKind = entry.kindOrNull ?: return false
        return when (kind) {
            SimklCompletionKind.PUSH_MOVIE -> entryKind == SimklCompletionKind.PUSH_MOVIE
            SimklCompletionKind.PUSH_SHOW -> true
            SimklCompletionKind.PUSH_SEASON ->
                entry.season == season &&
                    (entryKind == SimklCompletionKind.PUSH_SEASON ||
                        entryKind == SimklCompletionKind.PUSH_EPISODE)
            SimklCompletionKind.PUSH_EPISODE ->
                entry.season == season &&
                    entryKind == SimklCompletionKind.PUSH_EPISODE &&
                    (episodes.isEmpty() || entry.episodes.any { it in episodes })
        }
    }

    /** The blob for a READ, falling back to the last copy that parsed. */
    private fun read(context: Context): Store =
        readOrNull(context) ?: Store(pending = lastGood[cacheKey(context)].orEmpty())

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
        lastGood[cacheKey(context)] = parsed.pending
        return parsed
    }

    private fun write(context: Context, store: Store) {
        prefs(context).edit()
            .putString(KEY_QUEUE, json.encodeToString(Store.serializer(), store))
            .apply()
        lastGood[cacheKey(context)] = store.pending
    }

    private fun cacheKey(context: Context): String =
        ProfileStorage.prefsName(context, PREFS_BASE)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileStorage.prefsName(context.applicationContext, PREFS_BASE),
            Context.MODE_PRIVATE
        )
}
