package com.kennyb1201.kbstream.ui.home

import androidx.compose.ui.unit.dp

/**
 * Where the rails list stops when a rail takes focus, and what it has to leave
 * visible below it.
 *
 * Home is a hero above a scrolling list of rails, and every rail is a section
 * title above a row of cards. Focus pulls a rail up so it lands just under the
 * hero — and that same pull-up decides how much of the NEXT rail shows beneath
 * it, which is the part that keeps going wrong:
 *
 *  - an unfloored landing went negative for a poster rail and ate the focused
 *    rail's OWN title (its top half scrolled under the hero);
 *  - flooring the landing at the focused rail's header fixed that, but the
 *    floor was read as "room for a whole title", which cost the space below
 *    and left the next rail's title as a row of white dots at the bottom edge
 *    — the report this exists for.
 *
 * The mistake was treating the floor as reserved room. A rail's title sits
 * INSIDE the rail, directly under its top edge, so any landing at or below
 * [HeaderFloor] shows that title in full — the floor only has to keep it clear
 * of the hero. Room for the NEXT title is then a plain packing problem, and on
 * a 1080p TV it is tight enough to be worth writing down:
 *
 *   540dp panel - 259dp hero = ~281dp of rails viewport (the hero is capped at
 *   48% of the screen), and a poster rail measures ~234dp: a ~25dp title, a
 *   4dp:3 poster box of 180dp plus the 24dp its focused glow needs, and no
 *   bottom padding at all [RailBottomContentPadding is 0 for this reason —
 *   the card box already reserves what its focus needs]. Add the section gap
 *   and one 25dp title line and the next title can only fit if the landing is
 *   within a few dp of the hero. So the landing is small on purpose.
 *
 * On the numbers above a focused poster rail lands at [HeaderFloor] with the
 * next rail's title fully on screen plus ~6dp of its posters, while a rail of
 * landscape cards is short enough to take the roomier [HeaderInset] and still
 * show ~30dp of the rail below it.
 *
 * The band takes the title's MEASURED height once a rail has reported one
 * (`SectionTitle` reports it): a device with a raised font scale draws a taller
 * title, and a band hardcoded to the default type scale would put the next
 * title back under the panel edge — the same bug, one font size later.
 */
internal object RailLanding {

    /**
     * Preferred landing: the focused rail's section this far below the top of
     * the rails viewport. Mirrors KB's MODERN_ROW_HEADER_FOCUS_INSET, and is
     * what a rail short enough to afford the band gets.
     */
    val HeaderInset = 40.dp

    /**
     * The closest a rail may land.
     *
     * This is clearance from the hero, not room for a whole title: the title is
     * part of the rail and every dp of landing spent on air above it is a dp
     * taken from the title of the rail below. Kept small deliberately — the
     * viewport is barely taller than a poster rail plus one line of text, so
     * this number is what decides whether the next section is legible at all.
     */
    val HeaderFloor = 4.dp

    /**
     * Space between two rail sections.
     *
     * Charged twice — it is real space the landing has to leave, and it is part
     * of the band it leaves — so it is the cheapest dp on this screen. Two
     * poster rows already read as two sections without a rule or a divider
     * between them: the card box keeps ~15dp of glow room below each poster and
     * the title carries its own padding.
     */
    val SectionGap = 12.dp

    /**
     * How much of the next rail's cards should show under its title. The point
     * is the section boundary being legible, not the cards; whatever the
     * landing cannot spare is given up here first.
     */
    val NextTitleSliver = 8.dp

    /** Stand-in for the title height until a rail has reported its own. */
    val FallbackTitleHeight = 32.dp
}

/**
 * The leading edge a newly focused rail should land on, in pixels.
 *
 * [revealTargetPx] is where the rail would have to sit for the band below it
 * ([railRevealBandPx]) to fit: the viewport, less the rail, less the band. It
 * is [topInsetPx] when the rail is short enough to afford the preferred landing
 * and negative when the rail is too tall for the band to fit at all — hence the
 * floor, which is what keeps a poster rail's own title out from under the hero.
 *
 * Deliberately depends only on the rail's SIZE, never on where it currently is:
 * every child of a focused rail computes the same landing, so a horizontal
 * focus move inside a row cannot bounce the list.
 */
internal fun railLandingTarget(
    topInsetPx: Float,
    floorPx: Float,
    revealTargetPx: Float
): Float = minOf(topInsetPx, revealTargetPx).coerceAtLeast(floorPx)

/**
 * The band the landing has to leave below a focused rail: the section gap, one
 * line of the next rail's title, and a sliver of its cards.
 *
 * [titleHeightPx] is the measured height of a rail title, or 0 before any rail
 * has reported one — in which case [fallbackTitlePx] stands in. The measured
 * height wins outright rather than as a lower bound: it is the real line box,
 * and on a panel this tight an over-estimate is paid for by the next rail's
 * title.
 */
internal fun railRevealBandPx(
    sectionGapPx: Float,
    titleHeightPx: Float,
    fallbackTitlePx: Float,
    sliverPx: Float
): Float = sectionGapPx + (if (titleHeightPx > 0f) titleHeightPx else fallbackTitlePx) + sliverPx
