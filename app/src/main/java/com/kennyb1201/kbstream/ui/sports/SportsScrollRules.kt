package com.kennyb1201.kbstream.ui.sports

/**
 * How far one D-pad press scrolls the hub when there is nothing to focus in
 * that direction.
 *
 * The case this exists for: a game the playlist cannot carry is deliberately not
 * focusable, so focus cannot walk past it, and a section of them has no focus
 * target below for the list to scroll to - the viewer's "it won't scroll down
 * into the games". The press becomes a scroll instead, and this is its distance.
 *
 * Roughly the height of the last row on screen, because a row is the unit the
 * viewer is reading: scrolling by one puts the next row where the last one was.
 * The bounds matter as much as the size:
 *
 *  - never less than a quarter of the viewport, because the last visible item
 *    can be a section heading, and a press that scrolls 40dp of a 640dp list
 *    reads as a press that did nothing at all;
 *  - never more than the viewport, because a page larger than the screen skips
 *    content outright - a card that was never on screen to be focused.
 *
 * A non-positive viewport (before the first layout pass) has no step: the
 * caller has nothing to scroll yet.
 */
internal fun sportsScrollStep(rowHeight: Int, viewportHeight: Int): Int {
    if (viewportHeight <= 0) return 0
    val row = if (rowHeight > 0) rowHeight else viewportHeight
    return row.coerceIn(viewportHeight / 4, viewportHeight)
}
