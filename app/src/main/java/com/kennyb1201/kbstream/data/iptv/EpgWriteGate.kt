package com.kennyb1201.kbstream.data.iptv

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps bulk guide (EPG) writes out of the player's way.
 *
 * A guide import inserts thousands of rows in 500/1000-row batches and then
 * re-keys the whole table in one staging→live swap; the live-lineup query
 * additionally writes a match row per newly-matched channel. All of it is
 * ordinary Room IO, but it runs on the same SQLite pool and the same process
 * heap the video decoder is filling. The field log showed the guide rebuild
 * finishing underneath a starting player, 750 ms GC pauses and a 6.9 s
 * rebuffer stall on a stream that otherwise played cleanly.
 *
 * So writes wait while something is playing. The wait is budgeted per viewing
 * session rather than unbounded — a two-hour movie must not starve the
 * 12-hour EPG refresh to the point where WorkManager kills and retries it —
 * and the budget only counts time actually spent holding. Once it is spent the
 * rest of the import goes through at full speed, and the next time playback
 * starts a fresh budget is granted.
 *
 * Two playback sources feed the gate, and they do NOT share a budget size:
 *
 *  - the fullscreen player ([setPlayerActive]), which sits on top of
 *    everything for the length of a title, and
 *  - the home hero's inline trailer ([setInlinePlaybackActive]), which the
 *    hero rotates constantly while the user browses. Each rotation is a
 *    session, so a full minute each would keep the EPG refresh waiting for as
 *    long as the home screen stays open; inline playback therefore gets a
 *    short leash ([INLINE_MAX_HOLD_MS]).
 *
 * Callers hold *before* a write, never around a read: a guide write must not
 * block the guide UI, only avoid landing in the middle of playback.
 */
object EpgWriteGate {

    /**
     * How long one fullscreen viewing session may hold guide writes back
     * before letting them run at full speed again.
     */
    const val MAX_HOLD_MS = 60_000L

    /**
     * The shorter leash for inline hero-trailer playback. Long enough to cover
     * a decoder configuring, short enough that browsing the hero cannot hold
     * the guide import off indefinitely.
     */
    const val INLINE_MAX_HOLD_MS = 5_000L

    private val lock = Any()

    /** Fullscreen player: set while the activity is on screen. */
    private val fullscreenActive = AtomicBoolean(false)

    /** Home hero: set while the pooled trailer player is actually playing. */
    private val inlineActive = AtomicBoolean(false)

    /** True while nothing is playing, i.e. writes may proceed. */
    private val idle = MutableStateFlow(true)

    /** Time already spent holding writes back in the current session. */
    private val heldMs = AtomicLong(0L)

    val isPlayerActive: Boolean get() = !idle.value

    /**
     * Called by the fullscreen player as it enters ([active] = true) and leaves
     * (false) the screen.
     */
    fun setPlayerActive(active: Boolean) {
        fullscreenActive.set(active)
        refresh()
    }

    /**
     * Called by the home hero's pooled trailer player as inline playback
     * starts and stops.
     */
    fun setInlinePlaybackActive(active: Boolean) {
        inlineActive.set(active)
        refresh()
    }

    /**
     * Recomputes the gate from both sources. Only the idle→active edge starts
     * a new session, so the budget is granted once per viewing rather than
     * once per callback — and so one source stopping cannot reset the budget
     * while the other is still playing.
     */
    private fun refresh() {
        synchronized(lock) {
            val active = fullscreenActive.get() || inlineActive.get()
            if (active == !idle.value) return
            if (active) heldMs.set(0L)
            idle.value = !active
        }
    }

    /**
     * How long a hold started right now may last: the session budget, clamped
     * for inline-only playback. Exposed for tests; [holdWhilePlaying] is the
     * only production caller.
     */
    internal fun holdCapMs(requested: Long = MAX_HOLD_MS): Long = when {
        fullscreenActive.get() -> requested
        inlineActive.get() -> minOf(requested, INLINE_MAX_HOLD_MS)
        else -> 0L
    }

    /**
     * Suspends while something is playing, returning as soon as playback stops
     * or the session budget is used up. Returns immediately when nothing is
     * playing, which is the case for every guide write outside playback.
     *
     * [maxHoldMs] exists for tests; production callers pass nothing.
     */
    suspend fun holdWhilePlaying(maxHoldMs: Long = MAX_HOLD_MS) {
        if (idle.value) return

        val budgetLeft = holdCapMs(maxHoldMs) - heldMs.get()
        if (budgetLeft <= 0L) return

        val startedAt = System.currentTimeMillis()
        withTimeoutOrNull(budgetLeft) {
            idle.first { it }
        }
        heldMs.addAndGet(System.currentTimeMillis() - startedAt)
    }
}
