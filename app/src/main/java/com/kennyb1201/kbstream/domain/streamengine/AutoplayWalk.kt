package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Auto-play's walk down the ranked list, through the debrid resolver when it has
 * to.
 *
 * The field case: with a P2P add-on installed beside the URL-serving ones, the
 * fastest answer was a list of torrent HASHES. Auto-play's eligible set was
 * "rows with a URL", so it saw nothing to start and handed the viewer a picker;
 * the picker showed that one add-on, because the press-of-Play collection had
 * already stopped (see StreamsViewModel). The rows were not unplayable - the
 * viewer's own TorBox account could turn a cached one into a CDN link in a round
 * trip - the app simply never asked.
 *
 * So autoplay walks: a row with its own link starts immediately (the common case
 * and the untouched one), and a hash row goes through [resolve]. A failure moves
 * to the NEXT candidate rather than stopping, because autoplay's job is to find A
 * working source, not to defend one. RESOLVES are capped: a cached torrent's
 * check is one round trip, but a create on an uncached one is not, and the path
 * to the first frame has to stay bounded - but the cap bounds the round trips,
 * not the walk. Once it is spent the walk keeps going over the remaining
 * candidates WITHOUT resolving them, so a url-bearing row behind a pile of
 * hashes still starts instead of handing the viewer a picker.
 *
 * [pick] is the caller's own choice of the next candidate (episode rules, season
 * packs, the quality ceiling - see [EpisodeMatch.autoplayPick] and
 * [AutoPlayQuality]) evaluated against what is LEFT, so removing a candidate that
 * would not resolve cannot change which rules apply to the rest. MANUAL presses
 * do not come through here: one attempt and a message is the right answer to a
 * press on a row the viewer chose.
 */
object AutoplayWalk {

    /** How many hash rows one autoplay may try to prepare. */
    const val MAX_RESOLVE_ATTEMPTS = 3

    /**
     * One TOTAL time budget for a walk (spec C20).
     *
     * Each attempt keeps the per-call timeouts it always had; this bounds their
     * SUM, which is what the viewer actually waits through. Three resolves are
     * three round trips at best, and an evening of a slow debrid host made
     * "Finding sources…" run for minutes with Back as the only way out. Twenty
     * seconds is generous for three calls and still leaves the picker as the
     * worst case.
     */
    const val DEFAULT_BUDGET_MS = 20_000L

    /**
     * What to start.
     *
     * [sources] is [candidates] with the prepared stream substituted in place of
     * the hash row it came from - the same substitution the picker's manual press
     * makes, so the player's list holds the link it is actually playing (and the
     * "next source" walk continues from the right place).
     *
     * A row the walk was answered NO by is NOT in it: a hash that would not
     * resolve, or one the budget never got to, is a row the player cannot start,
     * and leaving it in made "next source" walk onto a dead link the walk had
     * already refused. Every other row keeps its order, so the player's list is
     * still a subsequence of [candidates]; names are resolved by stream identity
     * rather than by index (see `addonsFor`), which is what makes the removal
     * safe for the parallel add-on array.
     */
    data class Start(
        val stream: Stream,
        val sources: List<Stream>,
        val resolved: Boolean
    )

    /**
     * Walks [candidates] in order and answers what to start, or null when
     * nothing in the list can be started.
     */
    suspend fun start(
        candidates: List<Stream>,
        pick: (remaining: List<Stream>) -> Stream?,
        resolve: suspend (Stream) -> Stream?,
        maxAttempts: Int = MAX_RESOLVE_ATTEMPTS,
        budgetMs: Long = DEFAULT_BUDGET_MS
    ): Start? {
        var remaining = candidates
        var attempts = 0
        // Wall clock rather than a timer coroutine: the budget has to describe
        // the wait the VIEWER is paying, and each attempt can spend an arbitrary
        // slice of it.
        val deadline = System.currentTimeMillis() + budgetMs
        // The rows the walk abandoned, by identity - a failed hash and a hash the
        // spent budget skipped both count. [sourcesFor] leaves them out of the
        // list the player is handed.
        val abandoned = ArrayList<Stream>()
        val sourcesFor: (prepared: Stream?, from: Stream) -> List<Stream> = { prepared, from ->
            candidates
                .filterNot { row -> abandoned.any { it === row } }
                .map { row -> if (prepared != null && row === from) prepared else row }
        }
        while (true) {
            val candidate = pick(remaining) ?: return null
            if (!candidate.url.isNullOrBlank()) {
                return Start(candidate, sourcesFor(null, candidate), resolved = false)
            }
            // The resolve budget (attempts OR time) is spent, but the LIST is
            // not: skip this hash and ask again, so a url-bearing row anywhere
            // behind it still starts. Returning null here was how a hash-heavy
            // list whose first three hashes had failed abandoned the free rows
            // after them.
            val leftMs = deadline - System.currentTimeMillis()
            if (attempts >= maxAttempts || leftMs <= 0L) {
                abandoned += candidate
                remaining = remaining.without(candidate) ?: return null
                continue
            }
            attempts++
            // The attempt is bounded by what is LEFT of the budget, and a timeout
            // cancels the resolve (which cancels its HTTP call - see
            // Call.awaitResponse), so a hung host cannot outlive the budget.
            val prepared = withTimeoutOrNull(leftMs) { resolve(candidate) }
            if (prepared != null) {
                return Start(
                    stream = prepared,
                    sources = sourcesFor(prepared, candidate),
                    resolved = true
                )
            }
            // Identity, not equality: two add-ons can serve the same torrent,
            // and the rows the picker ranks are the objects in this list.
            abandoned += candidate
            remaining = remaining.without(candidate) ?: return null
        }
    }

    /**
     * [this] without [candidate], or null when it was not in the list at all.
     *
     * The null is the loop's termination guard: [pick] is the caller's function
     * and a pick that names a row it was not offered would otherwise leave
     * `remaining` unchanged and spin forever.
     */
    private fun List<Stream>.without(candidate: Stream): List<Stream>? {
        val next = filterNot { it === candidate }
        return next.takeIf { it.size < size }
    }
}
