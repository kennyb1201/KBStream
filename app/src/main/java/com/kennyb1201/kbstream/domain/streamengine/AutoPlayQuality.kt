package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream

/**
 * A ceiling on what auto-play is allowed to start.
 *
 * The ranker already knows what this *device* can decode (see
 * [com.kennyb1201.kbstream.data.device.DeviceCapability]) and downshifts 4K on
 * a low-RAM box, but it cannot know that the network is congested tonight, that
 * the viewer is on a metered connection, or that they simply do not want a
 * 40 Mbps remux to be the thing that starts on its own. This is that missing
 * input: a viewer-set resolution ceiling applied to the auto-play pick.
 *
 * Deliberately a *pick* rule, not a sort rule: the picker still lists every
 * source and still shows its badges, and a viewer who wants the 4K can always
 * press it. Only the head that auto-play reaches for is constrained — which is
 * the source of the "it started a remux I did not choose" report.
 *
 * Pure, so the choice is unit tested rather than discovered on the TV.
 */
internal object AutoPlayQuality {

    /** No ceiling: whatever the ranker put first is what auto-play takes. */
    const val CAP_AUTO = 0

    /** 2160p / 4K and below. */
    const val CAP_2160 = 1

    /** 1080p and below. */
    const val CAP_1080 = 2

    /** 720p and below. */
    const val CAP_720 = 3

    /**
     * The resolution tier a cap admits. Mirrors [StreamRanker.resolutionRank],
     * so "1080p" admits a 1080p stream and nothing taller; [CAP_AUTO] admits
     * everything.
     */
    fun capRank(cap: Int): Int = when (cap) {
        CAP_2160 -> 5
        CAP_1080 -> 3
        CAP_720 -> 2
        else -> Int.MAX_VALUE
    }

    /**
     * The source auto-play should actually start, given the pick the episode
     * rules already made and the viewer's ceiling.
     *
     * [pick] is what [EpisodeMatch.autoplayPick] chose with no ceiling in mind;
     * [candidates] is the same ranked list it read. When no ceiling is set, or
     * the pick already DECLARES a resolution at or under it, the pick is
     * returned untouched — a cap may only ever move auto-play *down* the list,
     * never up.
     *
     * Otherwise a source that declares its own resolution at or under the
     * ceiling wins, keeping the ranker's own order among the qualifiers. The
     * episode rules are re-applied here rather than trusted from [pick]: a
     * lower-ranked source at the cap is only a candidate when it is still
     * *this* episode — never one that declares another episode of the season,
     * and never a whole-season pack.
     *
     * An unlabeled copy is only the fallback behind those declared ones. Both
     * are allowed — an unlabeled file is not evidence of a big one — but only
     * the declared copy is *evidence of a small one*, and the unlabeled head is
     * exactly where a 4K remux hides behind a fill-only size hint. Preferring
     * the unlabeled head is what made a declared 1080p ceiling start 4K: the
     * head said nothing, so the ceiling saw nothing to apply. A title whose
     * sources carry no resolution labels at all still behaves as it always did.
     *
     * When every copy plainly claims MORE than the ceiling, this returns null
     * and auto-play starts nothing: the picker opens with the whole list, one
     * tap from playing. That is what the setting says it does ("never starts
     * above 1080p", "never starts above 720p"), and a ceiling that quietly
     * started the 4K anyway is the report this rule exists to answer. It is
     * still not a veto on playing the title — only on picking for the viewer.
     */
    fun cappedPick(
        pick: Stream?,
        candidates: List<Stream>,
        season: Int?,
        episode: Int?,
        runtimeMinutes: Int?,
        cap: Int
    ): Stream? {
        if (pick == null || cap == CAP_AUTO) return pick
        val limit = capRank(cap)
        // A cap moves the pick down, never up: a pick that already declares a
        // resolution at or under the ceiling is the answer, even if a taller
        // source exists higher in some other sense.
        val pickRank = StreamRanker.resolutionRank(pick)
        if (pickRank in 1..limit) return pick
        val eligible = candidates.filter { stream ->
            !stream.url.isNullOrBlank() &&
                qualifiesForEpisode(stream, season, episode, runtimeMinutes)
        }
        // 1. a copy that SAYS it fits ...
        eligible.firstOrNull { StreamRanker.resolutionRank(it) in 1..limit }
            ?.let { return it }
        // 2. ... then one that says nothing, the pick itself first because that
        //    is where the ranker and the episode rules already landed ...
        if (pickRank == 0) return pick
        eligible.firstOrNull { StreamRanker.resolutionRank(it) == 0 }?.let { return it }
        // 3. ... and otherwise nothing, so the picker opens instead of a copy
        //    the viewer's own ceiling excludes.
        return null
    }

    /**
     * The name the Settings pickers use for a cap: "Auto", "4K", "1080p",
     * "720p".
     *
     * Here rather than in either picker so a notice on the phone and the same
     * notice on the TV cannot call the same ceiling two things.
     */
    fun name(cap: Int): String = when (cap) {
        CAP_2160 -> "4K"
        CAP_1080 -> "1080p"
        CAP_720 -> "720p"
        else -> "Auto"
    }

    /**
     * The sentence the picker shows when the ceiling, and nothing else, is why
     * auto-play started nothing — or null when it is not the reason.
     *
     * [cappedPick] answers null in that case and both pickers then simply stay
     * open (see its call sites), which without a word on screen reads as its
     * own bug: a viewer who pressed Play lands on a list of sources with no
     * explanation. The same shape as the picker's wrong-episode notice, and for
     * the same reason.
     *
     * Only when EVERY candidate for this episode plainly declares more than the
     * ceiling: an unlabeled source would have been taken by [cappedPick], and a
     * list with no candidates at all is the wrong-episode case's business, not
     * this one's.
     */
    fun ceilingNotice(
        streams: List<Stream>,
        season: Int?,
        episode: Int?,
        runtimeMinutes: Int?,
        cap: Int
    ): String? {
        if (cap == CAP_AUTO || streams.isEmpty()) return null
        val limit = capRank(cap)
        val candidates = streams.filter { stream ->
            !stream.url.isNullOrBlank() &&
                qualifiesForEpisode(stream, season, episode, runtimeMinutes)
        }
        if (candidates.isEmpty()) return null
        val everyCandidateTaller = candidates.all { stream ->
            StreamRanker.resolutionRank(stream).let { rank -> rank != 0 && rank > limit }
        }
        if (!everyCandidateTaller) return null
        val subject = if (season == null || episode == null) "title" else "episode"
        return "Every source for this $subject is above your ${name(cap)} auto-play " +
            "ceiling, so none was started. Pick one below, or raise Maximum " +
            "auto-play quality in Settings."
    }

    /**
     * True when [stream] may stand in for the requested episode: it is playable,
     * does not declare another episode of the same season, and is not a whole
     * season. With no episode in the request (a movie, a live channel) every
     * playable source qualifies.
     */
    private fun qualifiesForEpisode(
        stream: Stream,
        season: Int?,
        episode: Int?,
        runtimeMinutes: Int?
    ): Boolean {
        if (season == null || episode == null) return true
        return !EpisodeMatch.isOtherEpisode(stream, season, episode) &&
            !EpisodeMatch.isSeasonPack(stream, runtimeMinutes)
    }
}
