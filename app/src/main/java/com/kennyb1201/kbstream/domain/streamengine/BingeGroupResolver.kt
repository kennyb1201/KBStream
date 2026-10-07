package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.settings.AppPreferences

/**
 * Applies the binge-group playback preferences to a freshly resolved source
 * list for the NEXT episode of a show that is mid-binge.
 *
 * Stremio addons tag their streams with behaviorHints.bingeGroup: streams in
 * the same group are the "same link" across consecutive episodes (same
 * provider/quality). When a group-tagged episode finishes and the next one
 * resolves, three user toggles decide what happens (all default ON):
 *
 *  - Prefer Binge Group: any stream whose bingeGroup matches the finished
 *    stream's group is promoted to the top of the list (preserving ranker
 *    order within the group) so auto-select continues seamlessly.
 *  - Reuse Binge Group: if NO stream carries the previous group, try to reuse
 *    the same addon's stream anyway — addon names match (e.g. the same
 *    "MediaFork 4K" entry across episodes). This covers providers that stop
 *    tagging consecutive files.
 *  - Fallback: if neither produced anything playable, keep the ranked top
 *    stream so autoplay never dead-ends; when OFF, autoplay stops and the
 *    picker is shown instead.
 *
 * When no binge context exists (BOTH the previous group and the previous
 * addon are blank, "Auto-play Next Episode" content unrelated to the
 * just-finished stream, or every toggle off) the list is returned untouched —
 * the normal ranker order applies. A blank group on its own is still enough
 * to run the reuse tier: the providers that tier covers are the ones that
 * stopped tagging consecutive files.
 */
object BingeGroupResolver {

    /**
     * Reorders [streams] for auto-select of the next episode.
     *
     * @param previousBingeGroup behaviorHints.bingeGroup of the stream that
     *        just finished (null/blank = no context, list untouched).
     * @param previousAddonName best-effort addon display name parsed from the
     *        previous stream's title line, used by the reuse tier.
     */
    fun orderedForNextEpisode(
        context: android.content.Context,
        streams: List<Stream>,
        previousBingeGroup: String?,
        previousAddonName: String? = null
    ): List<Stream> {
        if (streams.isEmpty()) return streams

        val prefer = AppPreferences.getBingeGroupPrefer(context)
        val reuse = AppPreferences.getBingeGroupReuse(context)
        val fallback = AppPreferences.getBingeGroupFallback(context)

        // Every toggle off: user wants stock behavior regardless of context.
        if (!prefer && !reuse && !fallback) return streams

        val group = previousBingeGroup?.trim().orEmpty()
        val addon = normalizeAddon(previousAddonName)

        // Neither a group nor an addon: no context, so the normal ranker order
        // is the answer (unchanged). ONE of the two is enough, though. The
        // reuse tier exists for providers that stop tagging consecutive files
        // - and those streams are exactly the ones that arrive with a blank
        // group, so bailing out on a blank group made that tier unreachable
        // for the case it was written for. That is the reported "it played
        // fine from my other addon, why does the next episode not remember".
        if (group.isEmpty() && addon.isEmpty()) return streams

        val groupMatch = streams.filter {
            !it.bingeGroup.isNullOrBlank() && it.bingeGroup.equals(group, ignoreCase = true)
        }

        // Tier 1 — same binge group.
        if (prefer && groupMatch.isNotEmpty()) {
            return groupMatch + streams.filterNot { it in groupMatch }
        }

        // Tier 2 — same addon (parsed from the stream title), when reuse is on.
        if (reuse) {
            if (addon.isNotEmpty()) {
                val addonMatch = streams.filter { normalizeAddon(titleAddonName(it)) == addon }
                if (addonMatch.isNotEmpty()) {
                    return addonMatch + streams.filterNot { it in addonMatch }
                }
            }
        }

        // Tier 3 — no group and no addon match: either hand back the ranked
        // top stream (fallback ON) or hand back nothing so the caller shows
        // the picker (fallback OFF → autoplay stops).
        return if (fallback) streams else emptyList()
    }

    /** True when the caller should show the stream picker instead of playing. */
    fun shouldStopAutoplay(ordered: List<Stream>): Boolean = ordered.isEmpty()

    /**
     * Best-effort addon display name from a stream's title line. Stremio
     * stream names are conventionally "AddonName\nquality" (newline or space
     * separated); the first token is the addon identity.
     */
    private fun titleAddonName(stream: Stream): String? =
        stream.name?.trim()
            ?.split('\n', ' ')
            ?.firstOrNull { it.isNotBlank() }

    /** Lowercase, punctuation-stripped identity for addon-name comparison. */
    private fun normalizeAddon(raw: String?): String =
        raw?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]"), "")
            .orEmpty()
}
