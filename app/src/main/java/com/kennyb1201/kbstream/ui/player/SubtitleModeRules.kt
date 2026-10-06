package com.kennyb1201.kbstream.ui.player

/**
 * How a session treats subtitles, as the viewer set it in Settings.
 *
 * Two of the three states exist because the old single preference could not
 * express them. A blank preferred language only told media3 "no preference",
 * and media3's own default selection would then arm a subtitle track anyway -
 * so there was no way to actually turn subtitles off. And there was no way to
 * ask for forced subtitles (the signs and foreign-dialogue track) at all.
 *
 * The value is stored as an Int, so a preference written by a newer build (or a
 * corrupted one) degrades to [ON] - the harmless, language-following behavior -
 * rather than to something the viewer never asked for.
 */
internal object SubtitleModeRules {

    /** Never arm a subtitle track on its own. */
    const val OFF = 0

    /** Only forced tracks (signs / foreign dialogue) show. */
    const val FORCED = 1

    /** Follow the preferred subtitle language, as before. */
    const val ON = 2

    /** What an absent or unreadable preference falls back to. */
    const val DEFAULT = ON

    /** The settings row, in display order: label to stored value. */
    val OPTIONS: List<Pair<String, Int>> = listOf(
        "Off" to OFF,
        "Forced only" to FORCED,
        "On" to ON
    )

    /** Clamps an unknown stored value to [DEFAULT]. */
    fun normalized(mode: Int): Int = if (mode in OFF..ON) mode else DEFAULT

    /** The settings label for [mode]. */
    fun label(mode: Int): String =
        OPTIONS.firstOrNull { it.second == normalized(mode) }?.first ?: "On"

    /**
     * Whether the auto-select pass may arm a subtitle track at all. [OFF] never
     * does; [FORCED] and [ON] both run, and the track rules decide which one.
     */
    fun autoSelects(mode: Int): Boolean = normalized(mode) != OFF
}
