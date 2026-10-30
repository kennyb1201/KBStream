package com.kennyb1201.kbstream.ui.player

/**
 * The credits panel's fit levels - the one decision behind "the pills and the
 * hint stay on screen" (see `BecauseYouWatchedUi.fitCreditsPanel`).
 *
 * Credits mode anchors the panel to the TOP of the screen with `WRAP_CONTENT`
 * height and 24dp margins, and the parent clips it - so a panel taller than the
 * viewport loses its BOTTOM: the PLAY / DETAILS pills on each pick card and the
 * hint line under them. On a 1080p UI (540dp tall, 492dp available) the full
 * arrangement needs ~540-565dp, which is exactly the field report (2026-10-28:
 * "the pills and the hint fall off the bottom edge", the focused pill itself cut
 * off).
 *
 * The header (kicker, title, thumbs), the pills and the hint never give way.
 * What gives way is artwork, in this order: the featured strip shrinks, then the
 * posters shrink, then the strip is dropped. The notch-clearance margin is never
 * touched - it exists to keep the pick row clear of the shrunk video.
 *
 * This file is deliberately free of Android types: the ordering rules are the
 * part worth pinning from a JVM test, and they are pinned in
 * `BywPanelFitAndFocusContractTest`.
 */

/** Everything at its built size. */
internal const val BYW_FIT_LEVEL_FULL = 0

/** Strip at 80dp backdrop and two description lines. */
internal const val BYW_FIT_LEVEL_COMPACT_STRIP = 1

/** ...and posters at 88x132dp, with their captions held to the same width. */
internal const val BYW_FIT_LEVEL_COMPACT_POSTERS = 2

/** ...and the strip hidden altogether: the floor, which always fits. */
internal const val BYW_FIT_LEVEL_STRIP_HIDDEN = 3

/**
 * Which fit level the credits panel should take next.
 *
 * Three rules, and the caller relies on all three:
 *
 *  - it never steps UP within a raise, so a level chosen for one layout is kept
 *    for the rest of the raise even once the content has settled;
 *  - it steps DOWN exactly one level per failing layout pass, so the caller can
 *    mutate that level in, request a layout and measure the REAL height again -
 *    never trusting an assumed saving;
 *  - it stops at [BYW_FIT_LEVEL_STRIP_HIDDEN], so there are at most three
 *    transitions per raise and no layout loop.
 *
 * The measured-total rule is what keeps a level honest: the pass never trusts an
 * assumed saving, it shrinks one step and measures the REAL height again. (The
 * clearance pass used to absorb a shrink by growing the row's top margin by the
 * same amount, so a level could cost nothing at all; it narrows the row instead
 * now, and every level shows in the total.)
 *
 * EQUALITY IS THE OVERFLOW SIGNAL, not slack. The panel is a `WRAP_CONTENT`
 * child of a clipping parent, so the parent measures it with an `AT_MOST`
 * height spec: an overflowing panel's measured height IS the available height,
 * never more. Reading `measured <= available` as "it fits" therefore made this
 * pass a no-op in exactly the case it exists for - and the Like / Dislike row
 * added to the header (about 44dp, field 2026-10-29: "no way to reach the
 * thumbs, and the play and details I can't see") pushed the pills and the hint
 * under that clip again. A panel that genuinely fits lands strictly under the
 * available height, so only equality has to be read as overflow here.
 */
internal fun fitLevelFor(
    measuredHeightPx: Int,
    availableHeightPx: Int,
    currentLevel: Int
): Int {
    // Nothing measured yet, or no height to fit into: not a verdict.
    if (availableHeightPx <= 0 || measuredHeightPx <= 0) return currentLevel
    // Strictly under is a fit; equality is the CLAMP (see the file comment): the
    // parent cannot report a height above its own, so a panel that measured the
    // full available height is an overflowing one, not a snug one.
    if (measuredHeightPx < availableHeightPx) return currentLevel
    return (currentLevel + 1).coerceAtMost(BYW_FIT_LEVEL_STRIP_HIDDEN)
}
