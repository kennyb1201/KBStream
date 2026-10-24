package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream

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
 * working source, not to defend one. Attempts are capped: a cached torrent's
 * check is one round trip, but a create on an uncached one is not, and the path
 * to the first frame has to stay bounded.
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
     * What to start.
     *
     * [sources] is [candidates] with the prepared stream substituted in place of
     * the hash row it came from - the same substitution the picker's manual press
     * makes, so the player's list holds the link it is actually playing (and the
     * "next source" walk continues from the right place). The substitution is
     * positional: a failed candidate stays in the list where it was, so an
     * add-on-name array built against [candidates] still lines up.
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
        maxAttempts: Int = MAX_RESOLVE_ATTEMPTS
    ): Start? {
        var remaining = candidates
        var attempts = 0
        while (true) {
            val candidate = pick(remaining) ?: return null
            if (!candidate.url.isNullOrBlank()) {
                return Start(candidate, candidates, resolved = false)
            }
            if (attempts >= maxAttempts) return null
            attempts++
            val prepared = resolve(candidate)
            if (prepared != null) {
                return Start(
                    stream = prepared,
                    sources = candidates.map { if (it === candidate) prepared else it },
                    resolved = true
                )
            }
            // Identity, not equality: two add-ons can serve the same torrent,
            // and the rows the picker ranks are the objects in this list.
            remaining = remaining.filterNot { it === candidate }
        }
    }
}
