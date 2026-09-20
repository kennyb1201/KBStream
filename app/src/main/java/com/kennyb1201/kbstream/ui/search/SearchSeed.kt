package com.kennyb1201.kbstream.ui.search

/**
 * One-shot carrier for a query that arrives from OUTSIDE the in-app search
 * field — a spoken query handed over by the TV's global search / mic
 * ([VoiceSearchActivity]), or a deep link.
 *
 * Why an object and not a screen argument: the Search screen is a data-less
 * singleton route (`Screen.Search`) reached from a dozen places, and the
 * activity-scoped `SearchViewModel` deliberately survives navigation. Seeding
 * is therefore a handoff, not state: the last writer wins, the first reader
 * consumes it, and nothing lingers to re-trigger on the next visit.
 */
internal object SearchSeed {

    /** Intent extra carrying the query into [MainActivity]. */
    const val EXTRA_QUERY = "com.kennyb1201.kbstream.extra.SEARCH_QUERY"

    @Volatile
    private var pending: String? = null

    fun set(query: String?) {
        pending = query?.takeIf { it.isNotBlank() }
    }

    /** Returns the pending query once, then forgets it. */
    fun consume(): String? {
        val query = pending ?: return null
        pending = null
        return query
    }
}
