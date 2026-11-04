package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream

/**
 * What the line has already proven it can hold, and the pick rule that uses it.
 *
 * Reported (field, 2026-11-03): a Penguplay title whose top link is a ~40 Mbps
 * remux stalls on BOTH engines, while a different (non-top) Penguplay source of
 * the same title plays fine. Nothing about the add-on or the host was wrong -
 * the head of the ranked list was simply heavier than the connection, and auto-
 * play takes the head without ever asking. Both engines already react to a
 * source that cannot keep up (Exo downshifts, MPV's watchdog reloads and then
 * gives up), so the viewer pays the stall first and the fix second, if at all.
 *
 * This object moves that knowledge one step earlier. Two halves, one model:
 *
 *  - **the estimate** ([estimateMbps]): a row's average bitrate, read from the
 *    size and the title's runtime - the same GB-to-Mbit-over-runtime reading
 *    [StreamRanker] already makes of a size, never a second size parser;
 *  - **the ceiling** ([afterStall], [afterCleanPlay]): one device-local number,
 *    learned from outcomes both engines already produce. A qualified stall at a
 *    row worth B lowers it to B × [STALL_HEADROOM]; a clean play of a row worth
 *    B raises an existing one to B. It is never created by a clean play, and
 *    never written by a debrid session, whose CDN throughput says nothing about
 *    what a file host will sustain.
 *
 * [admits] is then the whole gate: with no ceiling, or no estimate, or an
 * estimate at or under the ceiling, the row passes. [gatedPick] applies it as a
 * *pick* rule - it may move auto-play down the list, and never a debrid pick
 * away, never a human's press, and never the picker's order or badges.
 *
 * Pure, so the rule is unit tested rather than discovered on the TV: the store
 * and the two engines' sessions are the only Android-shaped parts, and they
 * call in here.
 */
internal object AutoplayBitrate {

    /**
     * Headroom applied to a row that stalled: a row that could not hold B does
     * not prove B × 0.95 would, so the ceiling lands well under the evidence
     * rather than at it.
     */
    const val STALL_HEADROOM = 0.8

    /**
     * How much of a session counts as a clean play: a minute past the first
     * frame. Shorter sessions prove nothing about throughput - a startup burst,
     * a glance at a stream, or a row abandoned in its first seconds all clear
     * in a way no sustained read does.
     */
    const val CLEAN_PLAY_MS = 60_000L

    /**
     * A row's average bitrate in Mbps, from its size in GB and the title's
     * runtime - or null when either is unknown.
     *
     * Unknown is not "small": an unlabeled file is no evidence of a heavy one,
     * so every caller here treats null as \"cannot judge\" and lets the row
     * through. Punishing unknown rows would gate exactly the rows this cannot
     * reason about, which is a bigger hole than the one it closes.
     */
    fun estimateMbps(sizeGb: Double?, runtimeMinutes: Int?): Double? {
        val runtime = runtimeMinutes?.takeIf { it > 0 } ?: return null
        val size = sizeGb?.takeIf { it > 0.0 } ?: return null
        // GB to Mbit, over the title's own seconds - the same reading
        // EpisodeMatch.isSeasonPack and StreamRanker.fakeQualityPenalty make.
        return size * 8_000.0 / (runtime * 60.0)
    }

    /** [estimateMbps] for a row, through the ranker's one size reading. */
    fun estimateFor(stream: Stream, runtimeMinutes: Int?): Double? =
        estimateMbps(StreamRanker.sizeGb(stream), runtimeMinutes)

    /**
     * Whether a row may be picked with [ceilingMbps] learned: no ceiling, no
     * estimate, or an estimate that fits.
     */
    fun admits(ceilingMbps: Float?, estimateMbps: Double?): Boolean =
        ceilingMbps == null || estimateMbps == null || estimateMbps <= ceilingMbps

    /**
     * The ceiling after a QUALIFIED stall on a row worth [estimateMbps], or null
     * when nothing should be written.
     *
     * Creates the ceiling when there is none and lowers an existing one; never
     * raises. One stall on the same class of row is what this exists to make
     * cost once, so each later stall tightens it again by [STALL_HEADROOM].
     */
    fun afterStall(ceilingMbps: Float?, estimateMbps: Double?): Float? {
        val estimate = estimateMbps ?: return null
        val next = (estimate * STALL_HEADROOM).toFloat()
        val current = ceilingMbps ?: return next
        return next.takeIf { it < current }
    }

    /**
     * The ceiling after a clean play of a row worth [estimateMbps], or null when
     * nothing should be written.
     *
     * Raises an existing ceiling to the played bitrate and NEVER creates one: a
     * clean 25 Mbps play is not evidence that a 40 Mbps row nobody stalled on is
     * safe. This is also the recovery path for a ceiling stuck low after a bad
     * night - the gate never blocks a manual press, so playing the heavy row
     * clean re-opens it for auto-play too.
     */
    fun afterCleanPlay(ceilingMbps: Float?, estimateMbps: Double?): Float? {
        val current = ceilingMbps ?: return null
        val estimate = estimateMbps ?: return null
        return estimate.toFloat().takeIf { it > current }
    }

    /**
     * True when the row's bytes are served by the viewer's debrid account: the
     * link's host or its completion tag says so, or the add-on it came from is
     * debrid-first (see [StreamRanker.isDebridServed] and
     * [StreamRanker.isDebridBackedByAddon]).
     */
    fun isDebrid(
        stream: Stream,
        addonOf: (Stream) -> String?,
        debridAddons: Set<String>
    ): Boolean = StreamRanker.isDebridServed(stream) ||
        StreamRanker.isDebridBackedByAddon(addonOf(stream), debridAddons)

    /**
     * The estimate a PLAYED session may teach the ceiling, or null when this
     * session must teach nothing.
     *
     * [playedUrl] is the link the session actually played, matched against
     * [sources] by identity: a link that matches no row is a resolved hash row
     * or a trailer, and both are somebody else's throughput - a TorBox CDN
     * resolve says nothing about what a file host will sustain, so it is
     * exempt exactly like a debrid row (`debridAddons` is the caller's own set,
     * the same one it hands `rank()` and [AutoplayPick.pick]).
     */
    fun sessionEstimateMbps(
        playedUrl: String,
        sources: List<Stream>,
        sourceAddons: List<String?>,
        runtimeMinutes: Int?,
        debridAddons: Set<String>
    ): Double? {
        if (playedUrl.isBlank()) return null
        val index = sources.indexOfFirst { !it.url.isNullOrBlank() && it.url == playedUrl }
        val row = sources.getOrNull(index) ?: return null
        if (isDebrid(row, { sourceAddons.getOrNull(index) }, debridAddons)) return null
        return estimateFor(row, runtimeMinutes)
    }

    /**
     * The auto-play pick, with the ceiling applied on top of [fullPick] (the
     * episode rules plus the resolution ceiling, exactly as today).
     *
     * An iterate-and-exclude loop, bounded by the candidate list: each pass asks
     * the caller's own pick over what is left, and a pick that is exempt or
     * admits is the answer. Exempt are debrid rows (by either signal), hash rows
     * (the walk resolves them, and a resolved link IS debrid), rows with no
     * estimate, and every row when no ceiling has been learned.
     *
     * The fallback is the point: when every re-pick is gated the ORIGINAL pick
     * is returned. The gate diverts when a lighter row exists; it never vetoes
     * playback and never strands the viewer on the picker for a title their own
     * ceiling cannot place - that session starts today's head and pays today's
     * stall, which is exactly what happens now. A null comes back only when
     * [fullPick] itself answers null on the FIRST pass (the resolution ceiling
     * emptied the set, which is the picker's existing case). A later pass that
     * answers null is this loop's own doing - the same rules found the original
     * pick in the full list a moment ago - so that answers the original too.
     *
     * [fullPick] is called with the same list on the first pass, so a session
     * with nothing to gate does exactly the work it did before - and the list is
     * copied before any exclusion, so no caller's list is ever mutated.
     */
    fun gatedPick(
        candidates: List<Stream>,
        ceilingMbps: Float?,
        runtimeMinutes: Int?,
        debridAddons: Set<String>,
        addonOf: (Stream) -> String?,
        fullPick: (List<Stream>) -> Stream?
    ): Stream? {
        val original = fullPick(candidates) ?: return null
        if (ceilingMbps == null) return original
        val remaining = candidates.toMutableList()
        while (remaining.isNotEmpty()) {
            // Null here is the gate's doing, not the ceiling's: these same rules
            // found the original pick in the full list one pass earlier. The
            // answer is that original - the gate may divert, never veto.
            val pick = fullPick(remaining) ?: return original
            val exempt = isDebrid(pick, addonOf, debridAddons) || !pick.infoHash.isNullOrBlank()
            if (exempt || admits(ceilingMbps, estimateFor(pick, runtimeMinutes))) return pick
            // Over the ceiling: that row is out of the running and the same
            // rules are asked again over what is left. Identity, not equality -
            // two add-ons can serve byte-identical rows, and they are still two
            // candidates.
            val at = remaining.indexOfFirst { it === pick }
            if (at < 0) return original
            remaining.removeAt(at)
        }
        return original
    }
}
