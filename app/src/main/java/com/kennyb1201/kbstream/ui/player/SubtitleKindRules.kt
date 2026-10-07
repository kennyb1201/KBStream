package com.kennyb1201.kbstream.ui.player

/**
 * What KIND of subtitle a row offers, read off the words that name it.
 *
 * A subtitle row carries very little: a language tag, and - when the container
 * or the add-on bothered - a title or a file name. Three rows that all read
 * "ENGLISH" tell the viewer nothing about which one is the SDH track, which is
 * the forced / signs track, and which is a commentary, so each row is tagged
 * from whatever text it has.
 *
 * The heuristics are the conventional ones (a `SDH` or `.hi.` token, "hearing
 * impaired", "forced", "commentary", "machine translated"), and they are
 * deliberately generous: a row that says "SDH" when it is only a full track is
 * a small confusion, while an SDH track nobody can identify is the complaint
 * this exists to answer. They scan the track's TITLE, the add-on's label and
 * the subtitle's URL - never the BCP-47 language field, where "hi" means Hindi
 * and "cc" is not a language at all.
 *
 * Pure, so [SubtitleKindRulesTest] pins every marker without a player.
 */
internal object SubtitleKindRules {

    /** Deaf / hard-of-hearing captioning: the dialogue plus the soundtrack. */
    const val SDH = "SDH"

    /** The signs / foreign-dialogue track. */
    const val FORCED = "FORCED"

    /** Transcribed from an audio commentary, not from the film's own mix. */
    const val COMMENTARY = "COMMENTARY"

    /** Machine translated rather than human translated. */
    const val MT = "MT"

    /**
     * The kinds [evidence] names, in the order a row should read them, without
     * duplicates. Empty when nothing in the text names one.
     */
    fun of(vararg evidence: String?): List<String> {
        val haystack = evidence.filterNotNull().joinToString(" ").lowercase()
        if (haystack.isBlank()) return emptyList()
        // Whole tokens, so "hi" cannot come from "this" and "mt" cannot come
        // from "format".
        val words = haystack
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .toSet()
        val tags = mutableListOf<String>()
        if (
            SDH.lowercase() in words ||
            "hi" in words ||
            "cc" in words ||
            "deaf" in words ||
            "captions" in words ||
            haystack.contains("hearing impaired") ||
            haystack.contains("hard of hearing") ||
            haystack.contains("closed caption") ||
            haystack.contains("for the deaf")
        ) {
            tags += SDH
        }
        if (
            "forced" in words ||
            "signs" in words ||
            // "Signs & Songs" is how a disc names the forced track.
            "songs" in words ||
            haystack.contains("foreign parts")
        ) {
            tags += FORCED
        }
        if ("commentary" in words) tags += COMMENTARY
        if (
            "mt" in words ||
            haystack.contains("machine translated") ||
            haystack.contains("machine-translated") ||
            haystack.contains("auto translated")
        ) {
            tags += MT
        }
        return tags
    }

    /**
     * The kinds as one tag string for a picker row ("SDH · FORCED"), or null
     * when the text names none - a row with no evidence stays exactly as it
     * read before.
     */
    fun tagText(vararg evidence: String?): String? =
        of(*evidence).takeIf { it.isNotEmpty() }?.joinToString(" \u00b7 ")
}
