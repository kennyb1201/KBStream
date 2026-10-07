package com.kennyb1201.kbstream.ui.player

/**
 * Clean SDH: keeps the dialogue of a deaf/hard-of-hearing caption and drops the
 * parts that serve the soundtrack instead - the sound descriptions
 * ("[door slams]", "(whispering)", "{music}"), the lyric lines, and the speaker
 * labels ("JOHN:", "- MAN:") that name whoever is talking.
 *
 * SDH is a caption track, not a translation: it is authored for a viewer who
 * cannot hear the mix, so it also carries what the mix is doing. A viewer who
 * wants the words and not the annotations has no way to ask for that in the
 * container, which is what this filter is for (mpv spells the same idea
 * `--sub-filter-sdh`).
 *
 * Only applied when the viewer turns it on (see AppPreferences
 * `getCleanSdhCaptions`), because it is lossy by design: a parenthesised remark
 * in a track that is NOT SDH is dialogue this would remove.
 *
 * Pure, so [SdhCaptionCleanerTest] pins every rule without a player.
 */
internal object SdhCaptionCleaner {

    /**
     * Sound descriptions, in any of the three shapes captions use.
     *
     * Both braces are escaped, and the closing one is the one that matters:
     * Android's regex engine is ICU, and ICU reads an unescaped `}` (or a `{`
     * that opens no repetition) as rule syntax and refuses the WHOLE pattern.
     * The throw happens inside this object's initializer, so the first cue a
     * viewer sees with clean SDH on is an ExceptionInInitializerError - a
     * fatal, not a caption that stays dirty (Sentry ANDROID-W).
     *
     * The JVM's own parser accepts a bare `}` as a literal, so a unit test on
     * the desktop never sees this; [IcuSafeRegexContractTest] pins the rule for
     * every pattern in the app instead.
     */
    private val description = Regex("""\[[^\]]*]|\{[^}]*\}|\([^)]*\)""")

    /**
     * A leading speaker label, with any dialogue dash kept: "JOHN: ", "- MAN: ",
     * "• SARAH: ". A name is a capitalised word or two, punctuated only with the
     * marks a name uses, before the colon.
     */
    private val speakerLabel = Regex("""^(\s*[-–—]\s*)?[\p{Lu}][\p{Lu}\p{L}'’.\- ]{0,24}:\s*""")

    /** The note marks that wrap a lyric or a musical cue. */
    private val note = Regex("""[♪♫]""")

    private val runs = Regex("""[ \t]{2,}""")

    /**
     * [text] with its descriptions and speaker labels removed. A line left with
     * nothing but punctuation disappears; so does a line carrying a note mark,
     * which is a lyric or a musical cue rather than something anybody says.
     * Returns an empty string when the cue was nothing but captions - the
     * caller draws nothing rather than an empty box.
     */
    fun cleaned(text: String): String {
        if (text.isEmpty()) return text
        return text.split('\n').mapNotNull { line ->
            if (note.containsMatchIn(line)) return@mapNotNull null
            val withoutDescriptions = description.replace(line, " ")
            val withoutSpeaker = speakerLabel.replace(withoutDescriptions) { match ->
                match.groupValues[1]
            }
            val collapsed = runs.replace(withoutSpeaker, " ").trim()
            collapsed.takeIf { cleaned -> cleaned.any { it.isLetterOrDigit() } }
        }.joinToString("\n")
    }
}
