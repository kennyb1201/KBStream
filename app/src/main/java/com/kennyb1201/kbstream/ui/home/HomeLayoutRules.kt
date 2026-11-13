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

    /**
     * How long the trailer waits after the CARD expands.
     *
     * It is the VIEWER'S hero-trailer delay, handed in from the pref
     * (`getHeroTrailerDelayMs`, options 0/2/4/6/8s): the card and the billboard
     * are one trailer surface rule, so a viewer who changes the hero's delay
     * must not find the card still waiting six seconds. Still counted from the
     * EXPANSION, never from focus - the expand delay and the trailer delay do
     * not stack into one timer.
     */
    fun expandedCardDelayMs(heroTrailerDelayMs: Long): Long =
        heroTrailerDelayMs.coerceAtLeast(0L)

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
     * The viewer's autoplay pref decides, and OFF is the only mode that vetoes
     * it (spec 2026-11-12, F4). The old rule demanded AUTO as well, back when
     * MANUAL had a TRAILER pill in the panel to press - and the field build
     * showed an autoplay-pref viewer with a panel that never played, because a
     * MANUAL billboard mode silently switched the card's trailer off. The pills
     * are gone (F2), so MANUAL no longer HAS a manual path here; treating it as
     * "off for cards too" would leave those viewers with no trailer at all.
     * Still counted from the EXPANSION, never from focus ([expandedCardDelayMs]).
     */
    fun autoplaysInCard(mode: BillboardTrailerMode, heroTrailerAutoplay: Boolean): Boolean =
        mode != BillboardTrailerMode.OFF && heroTrailerAutoplay
}

/**
 * Where the expanded card's panel is drawn, in pixels, inside [viewport].
 *
 * THE PANEL IS SIZED FROM THE VIEWPORT, NOT FROM THE CARD (spec 2026-11-12,
 * build 262). It used to be the card times [growth] and nothing else, which is
 * how the field got a panel the width of a rail card: a tiny backdrop and a
 * title, meta line and synopsis with no room, all of them ellipsized. The
 * expansion is a READER, so it is drawn at a real reader's size - a fixed
 * fraction of the screen ([PANEL_WIDTH_FRACTION]) whose 16:9 backdrop plus its
 * text block set the height ([PANEL_BACKDROP_RATIO] + [PANEL_INFO_FRACTION]) -
 * and still grows at least [growth] times the card, so it is never smaller than
 * the card it came from.
 *
 * It ANCHORS to the card - its left edge is the card's, and its top is the
 * card's top unless growing down would run past the bottom edge, in which case
 * it FLIPS to grow upward from the card's bottom - and it clamps to the
 * viewport on both axes. This is arithmetic on the same coordinates the rail
 * measured the card in (`boundsInRoot`), so the panel and the rail agree about
 * where the edge is - there is no second screen-size estimate to drift.
 *
 * A still-too-big panel is capped rather than allowed to overhang: the panel is
 * what the viewer reads, so all of it has to be on screen.
 */
internal data class SpotlightPanelBox(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

internal fun spotlightPanelBox(
    cardLeftPx: Float,
    cardTopPx: Float,
    cardRightPx: Float,
    cardBottomPx: Float,
    growth: Float,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    marginPx: Float
): SpotlightPanelBox {
    val margin = marginPx.coerceAtLeast(0f)
    val usableWidth = (viewportWidthPx - 2f * margin).coerceAtLeast(0f)
    val usableHeight = (viewportHeightPx - 2f * margin).coerceAtLeast(0f)
    val cardWidth = (cardRightPx - cardLeftPx).coerceAtLeast(0f)
    val cardHeight = (cardBottomPx - cardTopPx).coerceAtLeast(0f)
    // A reader's width, floored by the card's own growth so a tiny card can
    // never produce a panel smaller than the one this rule always drew.
    val width =
        (usableWidth * PANEL_WIDTH_FRACTION)
            .coerceAtLeast(cardWidth * growth)
            .coerceAtMost(usableWidth)
    // The backdrop is 16:9 and the text block is a fixed share of the width, so
    // the height follows the width - a wider panel is a taller one, which is
    // what gives the synopsis its lines. Capped by the screen like the width.
    val height =
        (width * (PANEL_BACKDROP_RATIO + PANEL_INFO_FRACTION))
            .coerceAtMost(usableHeight)
    val left = cardLeftPx.coerceIn(margin, (margin + usableWidth - width).coerceAtLeast(margin))
    val below = cardTopPx
    val above = cardBottomPx - height
    val top = if (below + height <= margin + usableHeight) {
        below.coerceAtLeast(margin)
    } else {
        above.coerceAtLeast(margin)
    }
    return SpotlightPanelBox(
        left = left.toInt(),
        top = top.toInt(),
        width = width.toInt(),
        height = height.toInt()
    )
}

/**
 * How much of the usable viewport width the expanded panel takes.
 *
 * 0.62 of a 1920 screen is ~1190px: a 16:9 backdrop ~670px tall, and a text
 * block with room for a two-line title, a two-line meta line and four or five
 * lines of synopsis. Substantially wider than a rail card at any rail's poster
 * size - which is the whole point of the fix - and still leaves the card's
 * neighbours visible around it, so focus is never lost.
 */
private const val PANEL_WIDTH_FRACTION = 0.62f

/** The backdrop's own shape: 16:9, the shape a still and a trailer both are. */
private const val PANEL_BACKDROP_RATIO = 9f / 16f

/**
 * The text block's share of the panel width, under the backdrop.
 *
 * Expressed against the WIDTH so the text grows with the panel: at 1190px it is
 * ~357px of title, meta and synopsis, which is several lines of the panel's
 * type - not the one truncated line the card-width panel had.
 */
private const val PANEL_INFO_FRACTION = 0.30f
