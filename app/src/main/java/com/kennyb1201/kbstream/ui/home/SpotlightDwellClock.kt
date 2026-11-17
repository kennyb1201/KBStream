package com.kennyb1201.kbstream.ui.home

/**
 * The Spotlight dwell's clock, kept PER CARD (spec 2026-11-16, A1/A2).
 *
 * The dwell asks one question - "has this card held focus for the viewer's
 * delay?" - and the field answer was "sometimes", because the clock used to be a
 * single slot wiped on every card change. Moving across a rail to read the cards
 * therefore threw away the second a card had already earned, and a viewer had to
 * come back and hold it again from zero (Kenny: "scroll past them and back a
 * couple of times before they finally expand").
 *
 * So time accumulates per CARD identity: [start] banks the run it replaces and
 * returns what that card has already earned, and only [spend] - the card's panel
 * opening, or the viewer closing it - forgets it. The clock is a plain Kotlin
 * class with no Android or Compose types, so the accounting is unit-tested.
 *
 * The map is bounded ([capacity]): the last few cards are all a viewer can
 * plausibly return to by scrolling, and a rail of thousands must not grow it.
 */
internal class SpotlightDwellClock(private val capacity: Int = CAPACITY) {

    /** Time already earned, per card identity, least recently held first. */
    private val earnedMs = LinkedHashMap<String, Long>()

    private var runCard: String? = null

    /** When the run in flight started; null when nothing is timing (0L is a time). */
    private var runStartMs: Long? = null

    /** True while a hold is timing; the caller's delay is in flight. */
    val running: Boolean get() = runCard != null

    /**
     * Stops the run in flight and banks its time against the card it held.
     *
     * A pause, not a reset (A2): the same card returning a moment later - or a
     * re-created node for it - resumes from here instead of from zero. Safe to
     * call with no run in flight.
     */
    fun pause(nowMs: Long) {
        val card = runCard ?: return
        runStartMs?.let { started ->
            val held = (nowMs - started).coerceAtLeast(0L)
            if (held > 0L) earnedMs[card] = (earnedMs[card] ?: 0L) + held
        }
        runCard = null
        runStartMs = null
        trim()
    }

    /**
     * Starts (or resumes) [card]'s hold at [nowMs] and returns how long it has
     * already been held.
     *
     * Any run still in flight is banked first, so a card node rebuilt under the
     * viewer - a rail re-deal, a bring-into-view - keeps its time instead of
     * restarting the wait (the older F1 bug, kept fixed here).
     */
    fun start(card: String, nowMs: Long): Long {
        pause(nowMs)
        val alreadyHeld = earnedMs[card] ?: 0L
        // Re-insert so the map's order is "least recently held first": the trim
        // drops the card this viewer is least likely to come back to.
        earnedMs.remove(card)
        earnedMs[card] = alreadyHeld
        runCard = card
        runStartMs = nowMs
        return alreadyHeld
    }

    /**
     * The wait is spent: [card]'s panel opened, or the viewer closed it.
     *
     * A null [card] means "whatever the clock knows" - the deliberate closes that
     * end the dwell outright (the expansion switched off, a menu opened, focus
     * leaving the cards) clear every card, as they always have.
     */
    fun spend(card: String?) {
        if (card == null) earnedMs.clear() else earnedMs.remove(card)
        runCard = null
        runStartMs = null
    }

    /** What [card] has already earned, for the trace and the tests. */
    fun earnedMsFor(card: String): Long = earnedMs[card] ?: 0L

    private fun trim() {
        while (earnedMs.size > capacity) {
            val eldest = earnedMs.keys.firstOrNull() ?: return
            earnedMs.remove(eldest)
        }
    }

    companion object {
        /**
         * How many cards' holds are remembered.
         *
         * Eight is a rail's worth either side of the focused card: enough for the
         * "read the row and come back" habit the field report describes, small
         * enough that the map can never be a leak.
         */
        const val CAPACITY = 8
    }
}
