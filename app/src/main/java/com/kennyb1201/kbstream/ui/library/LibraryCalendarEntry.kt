package com.kennyb1201.kbstream.ui.library

/**
 * A one-shot request to open the Library on its Calendar pane, with the D-pad
 * inside the calendar.
 *
 * The Home Upcoming rail is the request's only author: its header is the one
 * place in the app that says "there is a whole year of this", and pressing it
 * has to land in Library with the pane already selected rather than dropping
 * the viewer on ALL and making them find the chip. A hand-off object rather
 * than a route argument because Library is a shell [Screen] with no payload of
 * its own (see MainActivity), and because the request must survive exactly one
 * composition of the screen and then be gone - re-entering Library later must
 * not reopen the calendar on its own.
 *
 * Mirrors [com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus]: written by the
 * screen that navigates, consumed once by the screen that arrives.
 */
object LibraryCalendarEntry {

    @Volatile
    private var pending = false

    /** Called by the Home rail's header before navigating to Library. */
    fun request() {
        pending = true
    }

    /** True exactly once per [request], for the Library screen that arrives. */
    fun consume(): Boolean {
        val requested = pending
        pending = false
        return requested
    }
}
