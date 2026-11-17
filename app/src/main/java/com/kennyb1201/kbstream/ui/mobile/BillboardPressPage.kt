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
