package com.kennyb1201.kbstream.ui.detail

/**
 * How far the detail list must scroll so the season poster row is seen WHOLE
 * beside the row the hand is on (spec 2026-11-17, F3).
 *
 * The poster row is 84dp x 2:3 plus its caption, in a row of its own. When the
 * row itself takes focus it is landed in full and that is correct - the field
 * report is about the two neighbours: on the row ABOVE, the poster row peeked in
 * at the viewport's bottom edge cut mid-poster; on the row BELOW, it peeked in at
 * the top edge the same way. The focused row had been landed minimally, so the
 * next row in the scroll direction was left with whatever sliver the viewport
 * had over - and a poster sliced through the middle is exactly what that sliver
 * looks like.
 *
 * So the page scrolls by the smallest delta that leaves BOTH rows whole: the row
 * the hand is on and the poster row. "Smallest" is what keeps the landing the
 * viewer just watched instead of replacing it - both rects are measured as they
 * are, and a delta of zero is the answer for the common case where nothing is
 * cut at all.
 *
 * Every argument is in the lazy list's own pixels: item offsets are relative to
 * [viewportStart] (which is negative when the list has a leading content
 * padding), and a row is `[start, end)` on that axis. The return value is a
 * scroll delta - positive scrolls forward, so items move up - which the caller
 * applies with `animateScrollBy`.
 *
 * Pure, and here rather than inside the screen, because this is the arithmetic a
 * contract test can assert exactly with no device and no focus tree.
 */
internal fun seasonRowRevealScroll(
    seasonStart: Int,
    seasonEnd: Int,
    neighbourStart: Int,
    neighbourEnd: Int,
    viewportStart: Int,
    viewportEnd: Int
): Int {
    // Scrolling forward by `d` moves every item up by `d`, so a row is fully
    // inside the viewport exactly when `d` is at least the share of it the
    // viewport's end is eating and at most the share the start is eating.
    val forwardNeeded = maxOf(seasonEnd - viewportEnd, neighbourEnd - viewportEnd)
    val forwardAllowed = minOf(seasonStart - viewportStart, neighbourStart - viewportStart)

    if (forwardNeeded <= forwardAllowed) {
        // Both rows fit together. A delta of zero falls inside the interval
        // whenever neither is cut, which is why the settled case costs no scroll.
        return 0.coerceIn(forwardNeeded, forwardAllowed)
    }

    // They do not fit together - a fact row with a wrapped awards card beside a
    // tall poster row is the shape, and a phone-sized viewport the other. There
    // is no landing that keeps both whole, so the poster row wins: the rule this
    // exists for is that a poster is never seen half-cut, and the focused row is
    // the one the hand is already on.
    val seasonNeeded = seasonEnd - viewportEnd
    val seasonAllowed = seasonStart - viewportStart
    if (seasonNeeded > seasonAllowed) return 0
    return 0.coerceIn(seasonNeeded, seasonAllowed)
}
