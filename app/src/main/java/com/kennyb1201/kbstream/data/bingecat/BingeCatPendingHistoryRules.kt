package com.kennyb1201.kbstream.data.bingecat

/**
 * One completion BingeCat never confirmed.
 *
 * [body] is the request body itself, as the JSON that went on the wire, so a
 * replay is the SAME request rather than a rebuilt one: `watched_at` rides
 * inside it, which is what keeps a replayed play at the time it happened
 * instead of at the time the device next had signal.
 *
 * [key] is what makes two completions of the same play one entry (see
 * [BingeCatPendingHistoryRules.key]); [mappedProfileId] is the BingeCat profile
 * it was written FOR, and no replay may ever deliver it to a different one.
 */
internal data class BingeCatPendingPlay(
    val mappedProfileId: Long,
    val key: String,
    val body: String,
    val label: String,
    val queuedAtMs: Long,
    val attempts: Int = 0
)

/**
 * The queue's decisions, with no Android and no network in them.
 *
 * The mechanism is spec audit 2026-11-18 Data M2: a completion that failed
 * (offline, a 429 park, a token that would not refresh) used to die with the
 * player session - the completion latch reset on the next tick of that one load
 * and nothing outlived the process - so BingeCat silently lost plays that Simkl
 * and Trakt eventually received (Trakt keeps a failed STOP in
 * `TraktPendingScrobbles` and flushes it in its next window).
 *
 * Four rules, each of which is wrong silently if it drifts:
 *
 *  - **One entry per play.** A re-completion of the same movie / episode
 *    replaces the pending entry instead of appending a second one, so a queue
 *    can never turn one click into two plays.
 *  - **Bounded.** [MAX_ENTRIES] newest, [MAX_AGE_MS] old, [MAX_ATTEMPTS]
 *    attempts: a permanently failing entry is dropped rather than retried
 *    forever, and the queue cannot grow without limit on a device that has been
 *    offline for weeks.
 *  - **Profile-scoped.** An entry is only ever replayed into the BingeCat
 *    profile it was written for. A mapping change (or a re-link to a different
 *    profile) drops it: a play belongs to the profile that watched it, and the
 *    one thing worse than losing it is filing it against the wrong person.
 *  - **Completions only.** Adds are queued; removes are not (see
 *    [BingeCatRepository.submitHistory]). A replayed remove could delete a play
 *    the viewer made since, on another device - a lost remove only leaves a
 *    badge, a late remove destroys data.
 */
internal object BingeCatPendingHistoryRules {

    /** The most plays one device will hold. */
    const val MAX_ENTRIES = 40

    /** Attempts before an entry is given up on. */
    const val MAX_ATTEMPTS = 3

    /** How long a play survives in the queue. */
    const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L

    /** The kinds a batch can carry, in the wire's own terms. */
    enum class Kind { MOVIE, SHOW, SEASON, EPISODES }

    /**
     * The identity of one play.
     *
     * Episode lists are SORTED into the key: a batch of `[3, 2]` and one of
     * `[2, 3]` are the same two plays, and keying them differently would queue
     * both. `-` stands in for an absent id so `imdb = null, tmdb = 5` cannot
     * collide with `imdb = "5", tmdb = null`.
     */
    fun key(
        kind: Kind,
        imdbId: String?,
        tmdbId: Int?,
        season: Int?,
        episodes: List<Int>
    ): String = listOf(
        kind.name,
        imdbId?.trim()?.takeIf { it.isNotBlank() } ?: "-",
        tmdbId?.takeIf { it > 0 }?.toString() ?: "-",
        season?.toString() ?: "-",
        if (episodes.isEmpty()) "-" else episodes.sorted().joinToString(",")
    ).joinToString("|")

    /**
     * [entries] with [entry] in it: same-key replacement, then the cap.
     *
     * Order is oldest first, so the cap keeps the NEWEST plays - the ones the
     * viewer is most likely to still care about, and the ones least likely to
     * have been superseded by a later play on another device.
     */
    fun enqueue(
        entries: List<BingeCatPendingPlay>,
        entry: BingeCatPendingPlay,
        nowMs: Long
    ): List<BingeCatPendingPlay> = prune(
        entries.filterNot { it.key == entry.key && it.mappedProfileId == entry.mappedProfileId } +
            entry,
        nowMs
    )

    /**
     * [entries] after one replay attempt on [key] answered [success].
     *
     * A success drops the entry (it is now on BingeCat); a failure counts an
     * attempt and drops the entry once it has used them all. The original
     * `queuedAtMs` is never refreshed: age is how long the play has been
     * waiting, not how recently it was tried.
     */
    fun settle(
        entries: List<BingeCatPendingPlay>,
        key: String,
        success: Boolean
    ): List<BingeCatPendingPlay> = entries.mapNotNull { entry ->
        if (entry.key != key) return@mapNotNull entry
        if (success) return@mapNotNull null
        val attempts = entry.attempts + 1
        if (attempts >= MAX_ATTEMPTS) null else entry.copy(attempts = attempts)
    }

    /**
     * What may be replayed now, oldest first.
     *
     * Only [mappedProfileId]'s entries survive - the rest are another profile's
     * plays and are dropped, not deferred - and only while they are young and
     * untried enough. Safe to call on every door: it is pure and idempotent.
     */
    fun usable(
        entries: List<BingeCatPendingPlay>,
        mappedProfileId: Long,
        nowMs: Long
    ): List<BingeCatPendingPlay> = prune(
        entries.filter { it.mappedProfileId == mappedProfileId },
        nowMs
    ).sortedBy { it.queuedAtMs }

    /** Age/attempt pruning plus the cap; oldest first. */
    fun prune(
        entries: List<BingeCatPendingPlay>,
        nowMs: Long
    ): List<BingeCatPendingPlay> {
        val fresh = entries.filter { entry ->
            entry.attempts < MAX_ATTEMPTS && nowMs - entry.queuedAtMs <= MAX_AGE_MS
        }
        return if (fresh.size <= MAX_ENTRIES) fresh else fresh.takeLast(MAX_ENTRIES)
    }
}
