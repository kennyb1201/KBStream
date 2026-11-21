package com.kennyb1201.kbstream.data.simkl

import kotlinx.serialization.Serializable

/**
 * The completion queue's shape and arithmetic, with no Android in the file.
 *
 * Split from [SimklCompletionQueue] for the reason `TasteRatingQueueRules` is
 * split from its store: dedup, the backoff schedule and the attempt cap are time
 * and identity behaviour, and the only way to pin them is a plain JVM test that
 * does not need a Context. The store beside it is the part that needs one.
 */

/**
 * Which history write a queued completion replays.
 *
 * Only PUSHES are queued: a removal is fire-and-report (see
 * [SimklCompletionQueue.cancelQueuedPushes]), so there is no REMOVE_* value here
 * on purpose.
 */
enum class SimklCompletionKind {
    PUSH_MOVIE,
    PUSH_SHOW,
    PUSH_EPISODE,
    PUSH_SEASON;

    companion object {
        /** The kind a stored entry names, or null for a value this build does not know. */
        fun from(value: String?): SimklCompletionKind? = entries.firstOrNull { it.name == value }
    }
}

/**
 * One watched-history write waiting for Simkl to accept it.
 *
 * Everything the impl needs to re-fire the write verbatim: the id spellings, the
 * title (Simkl matches on ids, but its own record reads better with one), and
 * for an episode or season write the season number and the episode numbers.
 *
 * [profileId] is the profile the write was made under. Simkl auth is per profile,
 * and the store itself is profile-scoped (see [SimklCompletionQueue]), so this is
 * a second wall rather than the only one: an entry written while the profile was
 * still resolving still drains under the profile that queued it, or not at all.
 *
 * [attempts] / [lastAttemptAt] / [lastError] are the retry bookkeeping owned by
 * [SimklCompletionQueueRules]; a park is NOT an attempt.
 */
@Serializable
data class SimklCompletionEntry(
    val kind: String = "",
    val imdbId: String = "",
    val tmdbId: Int? = null,
    val title: String? = null,
    val season: Int = 0,
    val episodes: List<Int> = emptyList(),
    val profileId: String = "",
    val enqueuedAt: Long = 0L,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0L,
    val lastError: String? = null
) {

    /** The kind this entry names, or null when the blob outlived a rename. */
    val kindOrNull: SimklCompletionKind? get() = SimklCompletionKind.from(kind)

    /**
     * The write this entry replays, as one string.
     *
     * Queueing the same (parent, season, episode) twice collapses to one entry,
     * and this is what decides "the same": the kind, the id, and the episode set.
     * A movie has no season or episodes, so its key is just its id.
     */
    val targetKey: String
        get() = "$kind|$imdbId|$season|${episodes.sorted().joinToString(",")}"
}

/**
 * The queue's arithmetic, without Android.
 *
 * The same schedule the ratings queue uses - 1 min, 5 min, 30 min, 2 h, 12 h,
 * then dropped at five attempts - for the same reason: a completion that cannot
 * be delivered because the network is gone must not retry in a tight loop, and
 * must not live in the queue forever either.
 *
 * A 429 is deliberately NOT a failed attempt here. It is the server telling this
 * leg when it may ask again, which is a park ([simklPlaybackParkMs]); walking an
 * entry down this schedule for a park would drop a legitimate completion after
 * five 429s that said nothing about the completion itself.
 */
internal object SimklCompletionQueueRules {

    /** How many failed attempts an entry may make before it is dropped. */
    const val MAX_ATTEMPTS = 5

    /** The waits between attempts, indexed by attempts ALREADY made. */
    val BACKOFF_MS: List<Long> = listOf(1L, 5L, 30L, 120L, 720L).map { it * 60_000L }

    /** The wait after [attempts] failed attempts. */
    fun backoffFor(attempts: Int): Long =
        BACKOFF_MS[(attempts - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    /** When [entry] may be tried again: immediately while it has never been tried. */
    fun dueAt(entry: SimklCompletionEntry): Long =
        if (entry.attempts <= 0) 0L else entry.lastAttemptAt + backoffFor(entry.attempts)

    /** True when [entry] is due at [nowMs]. */
    fun isDue(entry: SimklCompletionEntry, nowMs: Long): Boolean = nowMs >= dueAt(entry)

    /** True when [entry] has spent its attempts and must be dropped instead. */
    fun isSpent(entry: SimklCompletionEntry): Boolean = entry.attempts >= MAX_ATTEMPTS

    /**
     * [incoming] placed into [pending], replacing anything queued for the same
     * write IN PLACE.
     *
     * In place rather than appended, so the FIFO order of the queue is the order
     * the viewer watched things in - a re-queue of the same episode must not move
     * it behind the episode watched after it.
     *
     * The attempt count carries over, like the ratings queue's coalesce: the
     * count is about whether Simkl accepts this write, and re-queueing it proves
     * nothing about that.
     */
    fun coalesce(
        pending: List<SimklCompletionEntry>,
        incoming: SimklCompletionEntry
    ): List<SimklCompletionEntry> {
        val index = pending.indexOfFirst { it.targetKey == incoming.targetKey }
        if (index < 0) return pending + incoming
        val previous = pending[index]
        return pending.toMutableList().apply {
            this[index] = incoming.copy(attempts = previous.attempts)
        }
    }

    /** [entry] after a failed attempt, or null when the cap drops it instead. */
    fun afterFailure(
        entry: SimklCompletionEntry,
        nowMs: Long,
        message: String?
    ): SimklCompletionEntry? =
        if (isSpent(entry)) null
        else entry.copy(
            attempts = entry.attempts + 1,
            lastAttemptAt = nowMs,
            lastError = message
        )
}
