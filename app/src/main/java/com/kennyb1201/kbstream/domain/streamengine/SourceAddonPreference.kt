package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream

/**
 * Reorders a resolved source list around what this TITLE's addons have actually
 * done.
 *
 * Reported problem: a show where every AIOStreams link sat dead - the player
 * walked past them, or raised its "source would not open" card - while the same
 * episodes played fine from another addon. Nothing remembered that, so the next
 * episode put the same unplayable links back at the top of the list, and the
 * picker's All tab showed them first as if they were the best copies. A
 * bingeGroup cannot answer this: it says "same link as last time", and an
 * addon that will not open has no working link to continue from.
 *
 * So each title keeps a small record of its addons' last OUTCOME - one that
 * actually opened a file, or one whose link failed to open - and this rule turns
 * that record into an order:
 *
 *  1. streams from an addon that opened a file for this title,
 *  2. everything else, in the order the ranker put it (an addon the app has
 *     never seen play here is not punished for it),
 *  3. streams from an addon whose link failed to open here.
 *
 * The tiers are stable, so within one the ranker's own order survives: this
 * rule moves whole addons, it does not second-guess the ranking.
 *
 * Deliberately NOT a filter. A demoted addon stays in the list and stays
 * selectable: its links may work again tomorrow (a debrid cache refills, a
 * hoster recovers), the record expires on its own, and the viewer can always
 * play one by hand - which is what "the picker lists everything" means. Only
 * the automatic pick and the head of the list are constrained.
 *
 * When nothing is known about this title, or the list holds a single source,
 * [ordered] returns the list it was given, untouched.
 */
internal object SourceAddonPreference {

    /** The addons this title has a record for, split by what they last did. */
    data class Outcomes(
        val worked: Set<String> = emptySet(),
        val failed: Set<String> = emptySet()
    ) {
        val isEmpty: Boolean
            get() = worked.isEmpty() && failed.isEmpty()
    }

    /**
     * Tier of an addon that opened a file here: it goes first.
     */
    private const val TIER_WORKED = 0

    /** Tier of an addon with no record for this title: the ranker decides. */
    private const val TIER_UNKNOWN = 1

    /** Tier of an addon whose link would not open here: it goes last. */
    private const val TIER_FAILED = 2

    /**
     * Identity for comparing an addon across the picker and the player.
     *
     * The two ends name the same addon differently - the stream fetch reports
     * the manifest name and the player resolves the installed addon's display
     * name (a rename is one of the app's own features) - so the key is
     * lowercased and stripped of punctuation before anything is compared or
     * stored. A rename to something else entirely is the one case this cannot
     * follow, and it degrades to "unknown", which is where every unseen addon
     * already sits.
     */
    fun normalize(addon: String?): String =
        addon?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]"), "")
            .orEmpty()

    /**
     * The show a stream id belongs to: its id with the trailing
     * `:<season>:<episode>` pair removed when it has one.
     *
     * A series video id is `"<show>:<season>:<episode>"` and a movie id is the
     * show itself, so both ends of the record derive the same key from what
     * they hold - the player from its `parentId`, the source fetch from the
     * stream id it was handed. Getting this wrong would only mean a record that
     * never matches, which is why it is pinned rather than inlined twice.
     */
    fun showKeyOf(id: String?): String {
        val trimmed = id?.trim().orEmpty()
        val parts = trimmed.trimEnd(':').split(':')
        val pairNamed =
            parts.size >= 3 &&
                parts[parts.size - 1].toIntOrNull() != null &&
                parts[parts.size - 2].toIntOrNull() != null
        return if (pairNamed) parts.dropLast(2).joinToString(":") else trimmed
    }

    /**
     * The order [streams] should be listed in, given what [addonOf] says each
     * one came from and the [outcomes] recorded for this title.
     */
    fun ordered(
        streams: List<Stream>,
        addonOf: (Stream) -> String?,
        outcomes: Outcomes
    ): List<Stream> {
        if (outcomes.isEmpty || streams.size < 2) return streams

        val worked = outcomes.worked
        val failed = outcomes.failed
        return streams
            .withIndex()
            .sortedWith(
                compareBy(
                    { (_, stream) -> tierOf(addonOf(stream), worked, failed) },
                    // The ranker's own order inside a tier: `sortedWith` is
                    // stable, but being explicit about the tie-break keeps the
                    // rule honest if that ever changes.
                    { (index, _) -> index }
                )
            )
            .map { it.value }
    }

    private fun tierOf(
        addon: String?,
        worked: Set<String>,
        failed: Set<String>
    ): Int {
        val key = normalize(addon)
        if (key.isEmpty()) return TIER_UNKNOWN
        // A failure wins when an addon is somehow in both records: the record
        // keeps only the LAST outcome, so this is only reachable across a
        // hand-edited or half-written store - and "its links would not open" is
        // the safe way to be wrong.
        if (key in failed) return TIER_FAILED
        if (key in worked) return TIER_WORKED
        return TIER_UNKNOWN
    }
}
