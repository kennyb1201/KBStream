package com.kennyb1201.kbstream.data.reporting

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Which composables actually ran, counted per metered gesture.
 *
 * The 2026-11-26 walk trace put the TCL's browsing jank in the main thread's
 * ANIMATION phase and could not name a sub-item inside it: Compose emits no
 * atrace child for a recomposition. FrameMetrics already splits that phase
 * (`animP50=` in the `frame:` line), and this answers the next question - which
 * scopes were running while it was spent - in the same COPY DIAGNOSTICS paste,
 * with no profiler attached.
 *
 * A COUNT, not a duration. One recomposition of a scope can still be the
 * expensive one; what a count rules out is CHURN, and that is the hypothesis a
 * walk carries: forty-eight card bodies in one gesture is a different bug from
 * one panel measuring for 30 ms, and they have different fixes. Read beside
 * `frames=` in the same line: a count near the frame count is per-frame work,
 * and a count that dwarfs it is churn. Either way it is the number a fix has to
 * bring down, and the next walk says whether it did.
 *
 * [note] is called in the body of the composable it counts, so it is reached
 * only when Compose actually executes that body. A scope whose inputs did not
 * change is skipped and contributes nothing - which is the signal, not a gap.
 * It writes no Compose state (a state write here would invalidate the very
 * card it counted) and takes no lock on the hot path: one ConcurrentHashMap
 * lookup and one LongAdder bump, against a body already measuring text and
 * drawing art.
 *
 * Scopes are named at the call site, never derived from a class name: R8
 * renames the UI package in a release build, so a reflected name would print as
 * `a.b`, and the whole point of the line is that it can be read in a paste.
 */
object ComposeChurn {

    /** Scopes one line can name before it stops listing them. */
    private const val MAX_SCOPES = 8

    private val counts = ConcurrentHashMap<String, LongAdder>()

    /**
     * Records one execution of [scope]'s body.
     *
     * Deliberately not `inline`: an inline call would disappear into the
     * composable it instruments, and the count is meant to survive R8 exactly
     * as the call that measures it does.
     */
    fun note(scope: String) {
        counts.computeIfAbsent(scope) { LongAdder() }.increment()
    }

    /** Every scope's lifetime count - the base a gesture's delta is taken from. */
    fun snapshot(): Map<String, Long> =
        counts.entries.associate { (scope, count) -> scope to count.sum() }

    /**
     * The scopes that ran since [before], counting only the increase.
     *
     * A scope that did not run is left out: a list of zeroes would grow the line
     * with every scope the app counts and say nothing. A scope absent from
     * [before] is work that did not exist when the snapshot was taken, and is
     * counted in full.
     */
    fun since(before: Map<String, Long>): Map<String, Long> =
        snapshot().mapNotNull { (scope, count) ->
            val delta = count - (before[scope] ?: 0L)
            if (delta > 0L) scope to delta else null
        }.toMap()

    /**
     * The ` churn ...` suffix a metered gesture's `frame:` line carries, or
     * empty when no counted scope ran (a surface with none of them composed).
     *
     * Busiest scopes first, so the line names the churn rather than the
     * alphabet, with ties by name so the same session always prints the same
     * line. Past [MAX_SCOPES] the tail is a count, because a line that grew with
     * every scope the app counts would stop being readable in a paste.
     */
    fun suffix(delta: Map<String, Long>): String {
        if (delta.isEmpty()) return ""
        val ranked = delta.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
            )
        val named = ranked
            .take(MAX_SCOPES)
            .joinToString(" ") { (scope, count) -> "$scope=$count" }
        val rest = ranked.size - MAX_SCOPES
        return " churn $named" + if (rest > 0) " +$rest" else ""
    }

    /** Drops every count - the diagnostics "clear" action. */
    fun reset() {
        counts.clear()
    }
}
