package com.kennyb1201.kbstream.ui.player

/**
 * Which OpenSubtitles hit to attach when subtitles are fetched automatically.
 *
 * The search already asks the API for the preferred language, so the ideal case
 * is a page of hits all in that language and the pick is simply the best one by
 * how many people downloaded it. This still guards the two ways that fails
 * quietly: the API returns some rows tagged for another language (its language
 * filter is a hint, not a promise), and a query with no language preference has
 * nothing to match. Preferring a language match, then downloads, keeps a
 * mis-tagged row from being drawn over the picture in the wrong language when a
 * right one was sitting below it.
 *
 * Pure, so the choice is unit tested rather than discovered on the TV.
 */
internal object AutoSubtitleRules {

    /**
     * The hit to attach, or null when there is nothing to attach.
     *
     * [preferredLanguage] is the same BCP-47 tag the player's preferred
     * subtitle language holds; a blank one means "no preference", and then the
     * most-downloaded hit wins.
     */
    fun pick(
        results: List<SubtitleSearchResult>,
        preferredLanguage: String
    ): SubtitleSearchResult? {
        if (results.isEmpty()) return null
        val want = languagePrefix(preferredLanguage)
        // A stated preference is a REQUIREMENT, not a hint. Falling back to the
        // most-downloaded hit drew a foreign track over the picture - the one
        // visible wrong thing an automatic fetch can do, and the viewer's only
        // clue was the subtitles themselves. Attaching nothing hides nothing:
        // the picker still lists every row the search returned. (No preference
        // at all keeps the old rule: the most downloaded row wins.)
        val eligible = if (want == null) {
            results
        } else {
            results.filter { languagePrefix(it.language) == want }
        }
        // ...and among the rows that qualify, downloads still decide, which is
        // what "preferring a language match, then downloads" has always meant:
        // the old shape returned the FIRST match in API order, so a barely
        // downloaded row could beat a well-used one sitting below it.
        return eligible.maxByOrNull { it.downloads }
    }

    /**
     * The language's two-letter stem, lowercased: "en", "en-US" and "English"
     * all read as "en". Null for a blank or single-letter tag, which cannot be
     * compared meaningfully.
     */
    private fun languagePrefix(raw: String?): String? =
        raw?.trim()?.lowercase()?.take(2)?.takeIf { it.length == 2 && it.all(Char::isLetter) }
}
