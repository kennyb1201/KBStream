package com.kennyb1201.kbstream.ui.home

/**
 * One-shot carrier for "open the Home rail manager", asked for from Home.
 *
 * Why an object and not a screen argument: the manager is
 * [com.kennyb1201.kbstream.ui.addons.CatalogManagerDialog], which is hosted by
 * the Add-ons screen and wired to that screen's view model - it cannot be
 * raised from Home without a second copy of that wiring. So Home's empty-state
 * button navigates to Add-ons and leaves this flag behind, the same handoff
 * shape [com.kennyb1201.kbstream.ui.search.SearchSeed] uses for the search
 * field: the last writer wins, the first reader consumes it, and nothing
 * lingers to re-open the manager on a later, unrelated visit.
 *
 * Set only by the "All rails are hidden" card, which is the one state where the
 * manager is the only way forward.
 */
internal object HomeRailManagerRequest {

    @Volatile
    private var pending = false

    fun request() {
        pending = true
    }

    /** True once, if the manager was asked for. */
    fun consume(): Boolean {
        val wasPending = pending
        pending = false
        return wasPending
    }
}
