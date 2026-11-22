package com.kennyb1201.kbstream.ui.detail

/**
 * Which half of the rule answered - the branch name the diagnostics line carries
 * (spec 2026-11-17, F3 of the reveal fix).
 *
 * The names are constants rather than string literals at the call site so the
 * pause between "which branch fired" and "what the code does" is one search, and
 * so a contract test can pin them without copying prose.
 */
internal const val SEASON_ROW_REVEAL_FIT = "fit"

/** Both rows could not be whole together: the focused row won (F1). */
internal const val SEASON_ROW_REVEAL_DONT_FIT_NEIGHBOUR = "dont-fit-neighbour"

/** Measured on a settled list: nothing is cut, so the reveal has nothing to do. */
internal const val SEASON_ROW_REVEAL_SETTLED_ZERO = "settled-zero"

/** A row was not in the list's measure, so there was no decision to make. */
internal const val SEASON_ROW_REVEAL_UNMEASURED = "unmeasured"

/**
 * The rule's answer for one measurement: how far to scroll, and which half of the
 * rule produced it.
 *
 * [delta] keeps its meaning exactly - a scroll delta in the lazy list's own
 * pixels, positive forward - so the screen still applies it with
 * `animateScrollBy`. [branch] is what the trace records, because a paste that
 * says "the reveal did nothing" cannot tell a settled page from a don't-fit page
 * where the focused row was already whole, and those are very different bugs.
 */
internal data class SeasonRowRevealDecision(
    val delta: Int,
    val branch: String
)

/**
 * How far the detail list must scroll so the row the hand is on is seen WHOLE
 * beside the season poster row (spec 2026-11-17; priority corrected by the
 * reveal fix of the same day, F1).
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
 * **When both cannot be whole, the FOCUSED row wins.** The original F3 said a
 * poster is never seen half-cut and let the poster row take the whole delta; on
 * the TCL that put the cut on the episode cards the viewer had just focused
 * (Kenny, 2026-11-17 07:53, the Dexter: Resurrection photo). That is the wrong
 * trade: the focused row is the one being read and pressed, a cut poster row is
 * a peek, and the two preferences cannot both hold in a viewport that does not
 * fit both. So the sliver goes to the poster row, and the rule reduces to what it
 * always was in the fit case - the row the hand is on is always whole.
 *
 * Every argument is in the lazy list's own pixels: item offsets are relative to
 * [viewportStart] (which is negative when the list has a leading content
 * padding), and a row is `[start, end)` on that axis. The return value is a
 * scroll delta - positive scrolls forward, so items move up - which the caller
 * applies with `animateScrollBy`.
 *
 * [focusedStart] / [focusedEnd] are the focused item's band. On a horizontal rail
 * every card shares the neighbour row's vertical band, so the caller passes that
 * row's rect; the clamp is still expressed over an item rect because a vertical
 * rail's focused card can be shorter than its row. [focusGuard] keeps the item
 * away from the viewport edge by that many pixels - the focus ring and scale are
 * drawn outside the card bounds, and a chip flush with the edge is the one the
 * framework can dispose under the scroll it just watched. If keeping the poster
 * row whole and keeping the focused item inside that guarded band conflict, the
 * focused item wins and the poster takes the cut.
 *
 * Pure, and here rather than inside the screen, because this is the arithmetic a
 * contract test can assert exactly with no device and no focus tree.
 */
internal fun seasonRowRevealDecision(
    seasonStart: Int,
    seasonEnd: Int,
    neighbourStart: Int,
    neighbourEnd: Int,
    viewportStart: Int,
    viewportEnd: Int,
    focusedStart: Int = neighbourStart,
    focusedEnd: Int = neighbourEnd,
    focusGuard: Int = 0
): SeasonRowRevealDecision {
    // Nothing to clamp against when the page is already measuring the
    // neighbour itself (the horizontal-rail case) and no edge is being
    // reserved. Keeping the early exit makes the pre-guard decisions exact.
    if (focusGuard <= 0 && focusedStart == neighbourStart && focusedEnd == neighbourEnd) {
        return seasonRowRevealBase(
            seasonStart = seasonStart,
            seasonEnd = seasonEnd,
            neighbourStart = neighbourStart,
            neighbourEnd = neighbourEnd,
            viewportStart = viewportStart,
            viewportEnd = viewportEnd
        )
    }

    // A leading content padding makes viewportStart negative. That edge is the
    // list's own top padding, not a cut: keeping the row flush with it is
    // correct, and guarding it would scroll a page whose first row is already
    // whole. The bottom edge is the one the focus landing parks a chip against.
    val safeStart = if (viewportStart < 0) viewportStart else viewportStart + focusGuard
    val safeEnd = viewportEnd - focusGuard
    if (safeStart >= safeEnd) {
        // A guard wider than the viewport has no safe band at all. Leave the
        // page alone rather than guessing which cut is preferable.
        return SeasonRowRevealDecision(
            delta = 0,
            branch = SEASON_ROW_REVEAL_DONT_FIT_NEIGHBOUR
        )
    }

    // The row-whole arithmetic runs against the GUARDED viewport, so a row
    // that is whole also keeps the focus cue's overdraw inside the screen. The
    // item clamp below is the second half: a vertical rail's focused card can
    // be shorter than its neighbour row, and it must stay in that band too.
    val base = seasonRowRevealBase(
        seasonStart = seasonStart,
        seasonEnd = seasonEnd,
        neighbourStart = neighbourStart,
        neighbourEnd = neighbourEnd,
        viewportStart = safeStart,
        viewportEnd = safeEnd
    )

    // After scrolling by d the focused band is [start - d, end - d). It is
    // inside the guarded viewport exactly when d is in that interval.
    val lower = focusedEnd - safeEnd
    val upper = focusedStart - safeStart
    if (lower > upper) {
        // The focused item cannot fit the guarded band (it is taller than the
        // viewport): do not scroll and trade one cut for another.
        return SeasonRowRevealDecision(
            delta = 0,
            branch = SEASON_ROW_REVEAL_DONT_FIT_NEIGHBOUR
        )
    }

    return base.copy(delta = base.delta.coerceIn(lower, upper))
}

private fun seasonRowRevealBase(
    seasonStart: Int,
    seasonEnd: Int,
    neighbourStart: Int,
    neighbourEnd: Int,
    viewportStart: Int,
    viewportEnd: Int
): SeasonRowRevealDecision {
    // Scrolling forward by `d` moves every item up by `d`, so a row is fully
    // inside the viewport exactly when `d` is at least the share of it the
    // viewport's end is eating and at most the share the start is eating.
    val forwardNeeded = maxOf(seasonEnd - viewportEnd, neighbourEnd - viewportEnd)
    val forwardAllowed = minOf(seasonStart - viewportStart, neighbourStart - viewportStart)

    if (forwardNeeded <= forwardAllowed) {
        // Both rows fit together. A delta of zero falls inside the interval
        // whenever neither is cut, which is why the settled case costs no scroll.
        return SeasonRowRevealDecision(
            delta = 0.coerceIn(forwardNeeded, forwardAllowed),
            branch = SEASON_ROW_REVEAL_FIT
        )
    }

    // They do not fit together - a fact row with a wrapped awards card beside a
    // tall poster row is the shape, and a phone-sized viewport the other. There
    // is no landing that keeps both whole, so the FOCUSED row wins and the poster
    // row takes the sliver (F1). Same clamp, on the neighbour's interval.
    val neighbourNeeded = neighbourEnd - viewportEnd
    val neighbourAllowed = neighbourStart - viewportStart
    if (neighbourNeeded > neighbourAllowed) {
        // Even the focused row cannot be made whole (it is taller than the
        // viewport): nothing can be done, and a scroll would only trade one cut
        // for another.
        return SeasonRowRevealDecision(
            delta = 0,
            branch = SEASON_ROW_REVEAL_DONT_FIT_NEIGHBOUR
        )
    }
    return SeasonRowRevealDecision(
        delta = 0.coerceIn(neighbourNeeded, neighbourAllowed),
        branch = SEASON_ROW_REVEAL_DONT_FIT_NEIGHBOUR
    )
}

/** The delta alone, for callers that do not file a diagnostics line. */
internal fun seasonRowRevealScroll(
    seasonStart: Int,
    seasonEnd: Int,
    neighbourStart: Int,
    neighbourEnd: Int,
    viewportStart: Int,
    viewportEnd: Int,
    focusedStart: Int = neighbourStart,
    focusedEnd: Int = neighbourEnd,
    focusGuard: Int = 0
): Int = seasonRowRevealDecision(
    seasonStart = seasonStart,
    seasonEnd = seasonEnd,
    neighbourStart = neighbourStart,
    neighbourEnd = neighbourEnd,
    viewportStart = viewportStart,
    viewportEnd = viewportEnd,
    focusedStart = focusedStart,
    focusedEnd = focusedEnd,
    focusGuard = focusGuard
).delta
