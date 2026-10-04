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
     * the pick already sits at or under it, the pick is returned untouched — a
     * cap may only ever move auto-play *down* the list, never up.
     *
     * Otherwise the first source at or under the ceiling wins, keeping the
     * ranker's own order among the qualifiers. The episode rules are re-applied
     * here rather than trusted from [pick]: a lower-ranked source at the cap is
     * only a candidate when it is still *this* episode — never one that
     * declares another episode of the season, and never a whole-season pack.
     * A source that declares no resolution at all counts as under the ceiling
     * (an unlabeled file is not evidence of a big one); only a source that
     * plainly claims more than the cap is skipped.
     *
     * When nothing qualifies the pick is returned anyway: a congestion ceiling
     * is a preference, not a veto, and refusing to play anything because every
     * copy of the episode is 4K would be a worse answer than starting one.
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
        // A cap moves the pick down, never up: when the episode rules already
        // landed at or under the ceiling, that is the answer, even if a taller
        // source exists higher in some other sense.
        if (StreamRanker.resolutionRank(pick).let { rank -> rank == 0 || rank <= limit }) return pick
        val withinCap = candidates.firstOrNull { stream ->
            !stream.url.isNullOrBlank() &&
                qualifiesForEpisode(stream, season, episode, runtimeMinutes) &&
                StreamRanker.resolutionRank(stream).let { rank -> rank == 0 || rank <= limit }
        }
        return withinCap ?: pick
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
