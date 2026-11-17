package com.kennyb1201.kbstream.ui.mobile

/**
 * The page a billboard pill's press acts on (2026-11-17, F2).
 *
 * The phone's billboard now keeps cycling while a pill holds focus. That is the
 * change Kenny asked for ("Let it cycle under focus too"), and it re-opens the
 * question F5 was written to answer: with the page able to change under the
 * viewer's hand, WHICH title does the press belong to?
 *
 * The answer is the one the viewer can see: the page that is current when the
 * press happens. So the press reads the pager at press time - never a page
 * captured when the pill took focus, which would be the page that has since
 * scrolled away and is exactly the stale-action bug the hold used to paper over.
 *
 * [settledIndex] is the fallback for the one frame where the pager has no page at
 * [currentIndex] (a feed that shrank under the hero). [pages] being non-empty is
 * already a precondition of the billboard, so this only returns null for an empty
 * list - the caller's "nothing to press" case, never a silent no-op on a live
 * page.
 */
internal fun <T> billboardPressPage(
    pages: List<T>,
    currentIndex: Int,
    settledIndex: Int
): T? = pages.getOrNull(currentIndex) ?: pages.getOrNull(settledIndex) ?: pages.firstOrNull()

/**
 * The page one manual step lands on, wrapping at both ends (2026-11-17, F1).
 *
 * The billboard's chevrons step the pager by one: right from the last page is the
 * first, left from the first is the last. That is the SAME wrap the auto-advance
 * uses (`(page + 1) % pages.size`), stated once so a tap and a timer tick cannot
 * disagree about where the sequence goes.
 *
 * [from] is clamped rather than trusted: a page index that has outlived a feed
 * that shrank steps from the nearest real page instead of wrapping off the end.
 * Null means there is nothing to step through.
 */
internal fun billboardWrapPage(size: Int, from: Int, step: Int): Int? {
    if (size <= 0) return null
    val start = from.coerceIn(0, size - 1)
    return ((start + step) % size + size) % size
}
