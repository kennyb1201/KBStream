package com.kennyb1201.kbstream.ui.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The IMDb id of the title being played, when the player only knows a TMDB id.
 *
 * IntroDB is keyed by IMDb id, and turning a TMDB id into one needs a Context
 * plus a network round trip — neither of which the segment fetcher has (it is
 * handed an id and nothing else). So the activity starts the lookup at playback
 * start and drops the answer here; the fetcher waits briefly for it and falls
 * back to the id it was given when nothing arrives in time.
 *
 * Session-scoped by construction: [begin] replaces any previous hint, so a slow
 * lookup from a finished episode can never be applied to the next one.
 */
internal object IntroDbHints {

    /** How long the fetcher waits for the lookup before giving up on it. */
    private const val WAIT_MS = 2_500L

    @Volatile
    private var deferred: CompletableDeferred<String?>? = null

    /** Starts a fresh lookup for a session; any earlier hint is discarded. */
    fun begin() {
        deferred = CompletableDeferred()
    }

    /** Records the resolved id, or null when there is none to record. */
    fun publish(imdbId: String?) {
        deferred?.complete(imdbId)
    }

    /** The resolved IMDb id, or null when it did not arrive in time. */
    suspend fun await(): String? {
        val pending = deferred ?: return null
        return withTimeoutOrNull(WAIT_MS) { pending.await() }
    }
}
