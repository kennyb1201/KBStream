package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.player.BingeGroupMemory
import com.kennyb1201.kbstream.data.player.SourcePickMemory

/**
 * THE auto-play pick, in one place, for every surface that starts an episode by
 * itself.
 *
 * There used to be three near-copies of "what would auto-play start": the
 * background resolve in MainActivity (episode rules + quality ceiling +
 * the binge tie-break), the TV picker's own auto-select (the same, minus the
 * tie-break), and the phone picker's via [com.kennyb1201.kbstream.ui.mobile.autoplayPickFor].
 * They agreed on the episode and the ceiling, and disagreed on the binge
 * identity - so a binge kept one provider on the Continue Watching path and
 * jumped on the first Play pressed from a picker (spec C19). One constructor
 * makes that divergence impossible: every caller passes the identity it has and
 * gets the same tie-break out.
 *
 * The identity itself is resolved the same way everywhere too ([binge]): what
 * was handed over wins, and a show with nothing handed over is recalled from
 * [BingeGroupMemory] - the cold-start case that made a binge change provider
 * after an app restart.
 *
 * A declared-episode match still beats the identity outright: the tie-break
 * only orders rows inside the same episode tier, which is [EpisodeMatch]'s rule
 * and not this object's business.
 */
internal object AutoplayPick {

    /** The identity a launch continues from: a tagging group and/or its add-on. */
    data class Binge(
        val group: String? = null,
        val addonName: String? = null
    ) {
        val isBlank: Boolean
            get() = group.isNullOrBlank() && addonName.isNullOrBlank()
    }

    /**
     * The identity to continue with.
     *
     * [handedGroup]/[handedAddon] are the player's handoff; when BOTH are blank
     * the show's remembered identity is used, which is the cold-start case (see
     * [BingeGroupMemory]). Either half is enough: the group is what a tagging
     * provider continues with, the add-on is the fallback for one that stopped
     * tagging consecutive files.
     */
    fun binge(
        memory: BingeGroupMemory.Identity?,
        handedGroup: String?,
        handedAddon: String?
    ): Binge {
        val group = handedGroup?.takeIf { it.isNotBlank() }
        val addon = handedAddon?.takeIf { it.isNotBlank() }
        if (group != null || addon != null) return Binge(group, addon)
        return Binge(
            memory?.bingeGroup?.takeIf { it.isNotBlank() },
            memory?.addonName?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * The within-tier preference: this row is served by the provider the binge
     * has been continuing with.
     *
     * [addonOf] is the caller's own lookup, because the three surfaces keep that
     * association differently (a parallel array in the picker, a stream-keyed
     * map after the background resolve) and a row served by two add-ons must
     * read as the one each surface would show.
     */
    fun preferBinge(
        binge: Binge,
        addonOf: (Stream) -> String?
    ): (Stream) -> Boolean = { stream ->
        (binge.group != null && stream.bingeGroup == binge.group) ||
            (binge.addonName != null && addonOf(stream) == binge.addonName)
    }

    /**
     * The row this surface would start by itself, or null when nothing in
     * [remaining] may be started automatically.
     *
     * The order is: the episode rules ([EpisodeMatch] - never a file that
     * declares another episode, never a whole season), the binge tie-break
     * inside those tiers, the viewer's own ceiling ([AutoPlayQuality]), and
     * last the learned bitrate gate ([AutoplayBitrate]).
     *
     * The gate is a PICK rule like the ceiling, and it is the only new step: it
     * may move auto-play to a lighter row the line has already proven it can
     * hold, and it never touches a debrid pick (by host, tag, or the debrid-
     * backed add-on set in [debridAddons]), never touches a hash row (the walk
     * resolves it through TorBox, so the resolved link IS debrid), and never
     * vetoes playback: when every candidate is too heavy the original pick is
     * what starts, exactly as today.
     *
     * [debridAddons] is the same set the caller hands [StreamRanker.rank].
     * Every caller must pass it: a surface that passes nothing silently
     * disables the exemption, and an AIOStreams row would be gated like a
     * hoster link.
     */
    fun pick(
        context: android.content.Context,
        remaining: List<Stream>,
        season: Int?,
        episode: Int?,
        runtimeMinutes: Int?,
        binge: Binge,
        addonOf: (Stream) -> String?,
        debridAddons: Set<String> = emptySet(),
        /**
         * The show this episode belongs to, for the viewer's remembered pick
         * (spec 2026-11-15). Null writes and reads nothing, so a caller that
         * cannot name the show behaves exactly as before.
         */
        parentId: String? = null
    ): Stream? {
        val cap = com.kennyb1201.kbstream.data.settings.AppPreferences
            .getEffectiveAutoPlayQuality(context)
        // The viewer's own pick for THIS episode, when this resolve still
        // offers it. Checked before the ranking because a manual press is not a
        // ranking outcome: the ranker puts a cached debrid copy above a better
        // HTTP link by design, and that is exactly why the viewer had to switch
        // by hand in the first place. The memory never invents a link - a pick
        // the fresh list no longer carries falls through to the ranker below.
        val rememberedPick = SourcePickMemory.recall(
            context = context,
            parentId = parentId,
            season = season,
            episode = episode,
            fresh = remaining
        )
        // ... and it is still their ceiling's business (F3): a pick that breaks
        // it falls back to the ranker like any other row above the ceiling.
        if (rememberedPick != null && AutoPlayQuality.atOrUnderCap(rememberedPick, cap)) {
            return rememberedPick
        }
        // A blank identity answers false for every row (see [preferBinge]), so
        // the tier's own order stands untouched.
        val preferBinge = preferBinge(binge, addonOf)
        return AutoplayBitrate.gatedPick(
            candidates = remaining,
            ceilingMbps = com.kennyb1201.kbstream.data.settings.AppPreferences
                .getLearnedSustainableMbps(context),
            runtimeMinutes = runtimeMinutes,
            debridAddons = debridAddons,
            addonOf = addonOf,
            fullPick = { rows ->
                AutoPlayQuality.cappedPick(
                    pick = EpisodeMatch.autoplayPick(
                        rows,
                        season,
                        episode,
                        runtimeMinutes,
                        preferBinge = preferBinge
                    ),
                    candidates = rows,
                    season = season,
                    episode = episode,
                    runtimeMinutes = runtimeMinutes,
                    cap = cap
                )
            }
        )
    }
}
