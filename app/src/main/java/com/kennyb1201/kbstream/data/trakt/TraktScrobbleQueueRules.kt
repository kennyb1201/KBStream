package com.kennyb1201.kbstream.data.trakt

/**
 * One STOP scrobble that did not reach Trakt (F2, 2026-11-11).
 *
 * Why a queue rather than Simkl's retry-inside-the-write: Simkl's scrobble leg
 * answers "false, the next event tries again", and for a start/pause that is
 * true - playback continues, so another event follows. A STOP is the last event
 * of a play. When it fails there is no next one, so the same shape would lose
 * the play outright: the local history and the other trackers record it, Trakt
 * never does, and the viewer has to re-mark the title by hand.
 *
 * [attemptedAtMs] is when the scrobble was first refused, and it is deliberately
 * NOT refreshed on a replay attempt: it is the age of the PLAY, so the cap in
 * [TraktScrobbleQueueRules.MAX_AGE_MS] eventually retires a record that can
 * never be delivered, instead of letting an undeliverable entry live forever.
 */
internal data class PendingScrobble(
    val action: String,
    val parentId: String,
    val parentType: String,
    val season: Int? = null,
    val episode: Int? = null,
    /** The whole percent the player reported, already through the rules. */
    val progress: Int,
    val tmdbId: Int? = null,
    val attemptedAtMs: Long
) {

    /**
     * Identity of the PLAY this entry records: the title, and for a series the
     * episode. One pending stop per play, so a second failed stop for the same
     * episode supersedes the first rather than replaying it twice - a replay
     * must never double-record a play.
     *
     * The episode is part of the key on purpose. Trakt's stop records the
     * EPISODE, so collapsing a show's E1 and E2 stops onto the show would drop
     * one of two different plays; only the same episode's stop is a duplicate.
     */
    val playKey: String
        get() = listOf(action, parentId, season ?: "", episode ?: "").joinToString("|")
}

/**
 * The queue's pure rules, so they are tested without Android or a network.
 */
internal object TraktScrobbleQueueRules {

    /**
     * How long a queued stop may wait for a window before it is dropped.
     *
     * Seven days: long enough to survive a holiday's worth of offline viewing
     * (which is exactly when scrobbles fail), short enough that a record from a
     * different viewing season is not posted months later and read as a
     * just-watched title. Past this the entry is dropped, never retried - a
     * tracker that has been unreachable for a week is not owed a play.
     */
    const val MAX_AGE_MS: Long = 7L * 24L * 60L * 60L * 1000L

    /** Bounded store: the newest [MAX_ENTRIES] plays are kept, oldest dropped. */
    const val MAX_ENTRIES: Int = 50

    /** The one action that is durable; start/pause stay fire-and-forget. */
    const val STOP: String = "stop"

    fun isStop(action: String): Boolean = action.trim().equals(STOP, ignoreCase = true)

    fun isExpired(entry: PendingScrobble, nowMs: Long): Boolean =
        nowMs - entry.attemptedAtMs >= MAX_AGE_MS

    /**
     * Whether a failed call is worth keeping for later.
     *
     * A transport failure (no response) is the airplane-mode case; 429 is the
     * rate limit; 5xx is Trakt being down. Everything else - a 4xx that is not
     * 429 - is Trakt answering "no": a malformed body, an id it will not
     * accept, a scrobble it considers invalid. Re-posting those on every window
     * would never succeed, so they are dropped at the call site.
     */
    fun isRetryable(code: Int?): Boolean = code == null || code == 429 || code >= 500
}
