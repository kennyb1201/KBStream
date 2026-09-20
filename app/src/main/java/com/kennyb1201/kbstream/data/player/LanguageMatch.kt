package com.kennyb1201.kbstream.data.player

/**
 * Language matching for track selection.
 *
 * Streams tag their tracks with whatever the muxer wrote, and that is almost
 * never the two-letter form the settings UI stores: MKV and MP4 carry the
 * ISO 639-2 three-letter code ("eng", "spa", "ger", "chi"). Comparing the two
 * as strings can only ever fail, and the failure is silent in both directions
 * — audio falls back to the muxer's first track (so a multi-language release
 * plays in whatever language it was authored in), and a subtitle preference
 * reads as "this file has no track in that language", which disables
 * subtitles.
 *
 * Both spellings of the 639-2 codes are accepted, because muxers emit the
 * bibliographic form ("ger", "fre", "chi") about as often as the
 * terminological one ("deu", "fra", "zho").
 *
 * Only the languages this app offers are mapped; anything else still matches
 * itself, so an untranslated tag is unaffected. Undetermined tags
 * ("und"/"unknown"/"mul") never match anything — a track that does not say
 * what it is must not be picked for a specific language.
 */
object LanguageMatch {

    /** Canonical (ISO 639-1) code -> every tag that means it. */
    private val GROUPS: Map<String, Set<String>> = mapOf(
        "en" to setOf("en", "eng"),
        "es" to setOf("es", "spa"),
        "fr" to setOf("fr", "fre", "fra"),
        "de" to setOf("de", "ger", "deu"),
        "ja" to setOf("ja", "jpn"),
        "ko" to setOf("ko", "kor"),
        "zh" to setOf("zh", "chi", "zho", "cmn", "yue"),
        "pt" to setOf("pt", "por"),
        "it" to setOf("it", "ita"),
        "ru" to setOf("ru", "rus")
    )

    private val BY_TAG: Map<String, String> =
        GROUPS.entries.flatMap { (canonical, tags) -> tags.map { it to canonical } }.toMap()

    /** Tags that mean "no language stated", which must never match a request. */
    private val UNDETERMINED = setOf("und", "unknown", "mul", "zxx")

    /**
     * Lowercase, stripped of region/script subtags: "EN-us" -> "en",
     * "pt_BR" -> "pt". Region matters for display, never for "which track did
     * the user ask for" — a Brazilian Portuguese track is a Portuguese track.
     */
    private fun normalized(tag: String?): String? =
        tag?.trim()
            ?.lowercase()
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.takeIf { it.isNotBlank() }

    /**
     * The comparable form of a language tag: "eng" -> "en", "EN-us" -> "en",
     * "fi" -> "fi". Null when the tag states no language at all.
     */
    fun canonical(tag: String?): String? {
        val normalized = normalized(tag) ?: return null
        if (normalized in UNDETERMINED) return null
        return BY_TAG[normalized] ?: normalized
    }

    /**
     * True when [trackTag] is the language [preferred] asked for, across the
     * two- and three-letter spellings. False when either side states nothing.
     */
    fun matches(preferred: String?, trackTag: String?): Boolean {
        val want = canonical(preferred) ?: return false
        val have = canonical(trackTag) ?: return false
        return want == have
    }
}
