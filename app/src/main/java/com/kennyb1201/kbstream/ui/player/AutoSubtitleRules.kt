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
        if (want != null) {
            results.firstOrNull { languagePrefix(it.language) == want }?.let { return it }
        }
        return results.maxByOrNull { it.downloads } ?: results.first()
    }

    /**
     * The language's two-letter stem, lowercased: "en", "en-US" and "English"
     * all read as "en". Null for a blank or single-letter tag, which cannot be
     * compared meaningfully.
     */
    private fun languagePrefix(raw: String?): String? =
        raw?.trim()?.lowercase()?.take(2)?.takeIf { it.length == 2 && it.all(Char::isLetter) }
}
