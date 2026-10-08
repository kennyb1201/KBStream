package com.kennyb1201.kbstream.ui.player

/**
 * How many rebuilds one failure cause is allowed in a single player session.
 *
 * The field session this exists for spent nine rebuilds — the retry ladder
 * re-asking six times, the source ladder switching on top, and the Dolby Vision
 * strip rebuilding in between — on a file whose real problem was the decoder
 * pool the rebuilds were themselves wedging. Two is the number this borrows
 * from Nuvio's player (summarized from github.com/NuvioMedia/NuvioTV, GPL-3.0):
 * a genuinely transient failure is answered by the first or second rebuild, and
 * everything after that is re-asking the same question of the same component.
 *
 * "Same cause" is the failure's own identity ([PlaybackRecoveryRules.failureCauseKey]),
 * so two different problems in one session each keep their own budget while a
 * decoder error repeating six times does not.
 *
 * The budget is only consulted by the failure ladder ([NativePlayerActivity.scheduleRetry]).
 * A live channel keeps its reconnect loop — it has no backup engine to hand to
 * and its screen is most often left running overnight — and the one-shot
 * recoveries (the Dolby Vision strip, the P5 reroute) are not counted against
 * it, because each of those happens at most once per session by construction.
 */
internal const val MAX_REBUILDS_PER_CAUSE = 2

/**
 * Whether a cause that has already had [rebuildsForCause] rebuilds may have
 * another one.
 *
 * Reads as "the first [MAX_REBUILDS_PER_CAUSE] rebuilds are allowed, the next
 * is not", which is what makes "the same cause thrice" stop at two.
 */
internal fun rebuildAllowed(rebuildsForCause: Int): Boolean =
    rebuildsForCause < MAX_REBUILDS_PER_CAUSE

/**
 * One session's rebuild budget, keyed by failure cause.
 *
 * Deliberately tiny and free of side effects so the rule can be exercised
 * without a TV: the Activity owns the rebuild, this only answers whether the
 * cause has had its allowance. Not thread-safe — it is only ever touched from
 * the Activity's main thread.
 */
internal class RebuildBudget(private val cap: Int = MAX_REBUILDS_PER_CAUSE) {

    private var cause: String? = null
    private var spent: Int = 0

    /** True when [causeKey] may still have a rebuild. */
    fun allows(causeKey: String): Boolean = causeKey != cause || rebuildAllowed(spent)

    /** Counts one rebuild against [causeKey]. */
    fun record(causeKey: String) {
        if (causeKey == cause) {
            spent++
        } else {
            cause = causeKey
            spent = 1
        }
    }

    /** How many rebuilds the cause has had, for logs and the diagnostics line. */
    fun spentFor(causeKey: String): Int = if (causeKey == cause) spent else 0

    /** A fresh session (or a source that is now playing) starts clean. */
    fun reset() {
        cause = null
        spent = 0
    }
}
