package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode

/**
 * The TV Home's second layout, and the rules that shape it.
 *
 * Modern is what the TV Home has always been: the hero follows focus, and the
 * hero META resolve describes whichever card the viewer is on. Spotlight keeps a
 * big hero on top but makes it a BILLBOARD that cycles on its own feed, and moves
 * the preview to the focused rail card - the card expands in place, Netflix
 * style, while the hero keeps cycling. The two layouts share the same rails,
 * order, pins and hidden rows: this is presentation, not content.
 *
 * The pref is DEVICE-LOCAL, on purpose, and joins the layout family
 * (`poster_size`, `home_landscape_cards`, `landscape_posters`) that
 * `PrefsPayloadBuilder.SYNCED_PREF_KEYS` deliberately leaves out: a phone has no
 * rail focus to expand a card from, so the phone never offers this and a TV's
 * choice must not follow the account onto one.
 */
internal enum class HomeLayout(val stored: String, val label: String, val description: String) {
    MODERN(
        stored = "modern",
        label = "Modern",
        description = "The hero follows the card you are on. The layout Home has always used."
    ),
    SPOTLIGHT(
        stored = "spotlight",
        label = "Spotlight",
        description = "A big cycling billboard up top, and the focused card expands in place " +
            "with its details and trailer - the Netflix-style hover preview."
    );

    companion object {
        val DEFAULT = MODERN

        /** A stored value read back, tolerant of anything an older build left. */
        fun fromStored(value: String?): HomeLayout =
            entries.firstOrNull { it.stored == value?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * The expand-delay picker's options, in the order the setting lists them.
 *
 * 0 is "No expand" - the layout stays Spotlight and the cards simply never
 * preview. The rest are whole seconds, 1-10.
 */
internal object HomeExpandDelay {

    const val NO_EXPAND_MS = 0L

    /** Two seconds: slow enough that a held D-pad scroll never flashes a preview. */
    const val DEFAULT_MS = 2_000L

    val OPTIONS_MS: List<Long> = listOf(NO_EXPAND_MS) + (1..10).map { it * 1_000L }

    /** The option nearest [storedMs], so a hand-edited pref still lands on a chip. */
    fun normalize(storedMs: Long): Long =
        OPTIONS_MS.minByOrNull { option -> kotlin.math.abs(option - storedMs) } ?: DEFAULT_MS

    /** "No expand", "1s", ... "10s" - the picker's own words. */
    fun label(ms: Long): String =
        if (ms <= NO_EXPAND_MS) "No expand" else "${(ms / 1_000L).coerceIn(1L, 10L)}s"

    /** Whether a card may ever expand at all (see "No expand"). */
    fun expands(ms: Long): Boolean = ms > NO_EXPAND_MS
}

/**
 * The single trailer surface rule, as a value.
 *
 * The app has ONE pooled trailer player, and it can be attached to one surface
 * at a time. In Spotlight the expanded card wins while it is up; the billboard
 * hero gets the player back when the card collapses. This is the rule the two
 * surfaces read, so neither can decide it differently.
 */
internal object SpotlightTrailerRule {

    /** How long the trailer waits after the CARD expands (the hero's own delay). */
    const val EXPANDED_CARD_DELAY_MS = 6_000L

    /**
     * The hero billboard's trailer mode while a card is expanded: OFF, whatever
     * the viewer chose.
     *
     * The card owns the one pooled player, so the billboard shows its still - the
     * hero does not get a second surface. When the card collapses the chosen
     * mode is handed back, and the billboard's own cycle restarts on the page it
     * was showing rather than picking up a half-played trailer.
     */
    fun heroModeWhileExpanded(): BillboardTrailerMode = BillboardTrailerMode.OFF

    /**
     * Whether the expanded card plays its trailer on its own once it is up.
     *
     * AUTO does (after [EXPANDED_CARD_DELAY_MS] from the expansion, never from
     * focus - the two delays never stack into one timer); MANUAL offers the
     * button instead; OFF offers nothing, exactly as on the hero.
     */
    fun autoplaysInCard(mode: BillboardTrailerMode, heroTrailerAutoplay: Boolean): Boolean =
        mode == BillboardTrailerMode.AUTO && heroTrailerAutoplay

    /**
     * Whether the expanded card draws a Trailer button.
     *
     * MANUAL always offers it, and the hero-trailer autoplay switch does not get
     * a say: that switch turns off trailers nobody asked for, while this press IS
     * the ask - the same reason the phone billboard's Manual pill ignores it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun showsTrailerButton(mode: BillboardTrailerMode, heroTrailerAutoplay: Boolean): Boolean =
        mode == BillboardTrailerMode.MANUAL
}
