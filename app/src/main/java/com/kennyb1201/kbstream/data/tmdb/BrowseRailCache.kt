package com.kennyb1201.kbstream.data.tmdb

/**
 * Browse pages a session remembers, for the Return trip.
 *
 * A browse page's first visit is already fast-pathed (page-1 rails first, in
 * declared order, skeleton gone on the first rail). The gap was RE-ENTRY: every
 * one of these pages is a screen-scoped ViewModel, so popping the page threw
 * its rails away, and coming back re-ran all six discover rails behind a fresh
 * skeleton - even though the viewer had just looked at those exact rails.
 *
 * So the memo cannot live in the page: it lives here, at the repository's edge,
 * for the life of the process. A second entry to the same page in the same
 * session is a hit; process death is the only thing that clears it besides a
 * profile switch (see ProfileManager.onActiveProfileChanged) and a manual retry,
 * which bypasses and replaces the entry because a retry means "this page is
 * wrong", not "show me the old one again".
 *
 * What is stored is the page's rails by DECLARED INDEX - the same slots the
 * stream publishes through - not a compacted list. A refreshed page must put
 * each rail back where it was declared, or a rail that was empty on the cached
 * visit would shift every rail after it and the page would visibly reshuffle
 * under the viewer.
 *
 * NOT cached: watched state. Cached rows carry the rows and nothing else, so
 * the screen's watched resolution runs on every hit exactly as it does on a
 * first visit - rows from the cache, badges always re-resolved.
 *
 * Deliberately not persisted to disk: a disk copy needs an invalidation design
 * (how old is too old, what does a TMDB catalog change mean) and that is a
 * separate spec. This is session memory only.
 */
internal object BrowseRailCache {

    /** How many visited pages a session keeps. */
    internal const val MAX_CACHED_PAGES = 8

    private val session = BrowseRailSessionCache()

    /**
     * A page's rails by declared index, or null when it was never loaded.
     *
     * A MUTABLE copy: the caller fills these same slots as the refresh streams
     * in, and doing that to the stored map would leave the cache holding a
     * half-refreshed page.
     */
    fun rails(key: String): MutableMap<Int, StudioSection>? = session.rails(key)

    /** Records what a page's load finished with. An empty page is never stored. */
    fun store(key: String, slots: Map<Int, StudioSection>) = session.store(key, slots)

    /** Drops every page, for a profile switch. */
    fun clear() = session.clear()

    /** Pages remembered right now, for the diagnostics dump. */
    fun pageCount(): Int = session.pageCount()

    /**
     * A page's identity: the page type plus whatever ids the route carries.
     *
     * Genre 12 and keyword 12 are two different pages, and so are a network and
     * a company that happen to share a number, which is why the type leads and
     * every id the loader is keyed by is part of the key. A null part is stored
     * as "-" so "no provider" and "provider 0" cannot collide.
     */
    fun keyOf(vararg parts: Any?): String =
        parts.joinToString("|") { part -> part?.toString() ?: "-" }
}

/**
 * The cache behind [BrowseRailCache], an instance so the rules can be tested
 * without the process-wide memo.
 *
 * Access-ordered so the page the viewer keeps re-entering is the LAST one to go
 * when a long browsing session fills the cap - the opposite of insertion order,
 * where the page behind the return trip would be the first casualty.
 */
internal class BrowseRailSessionCache(
    private val maxPages: Int = BrowseRailCache.MAX_CACHED_PAGES
) {

    private val pages =
        object : LinkedHashMap<String, Map<Int, StudioSection>>(8, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Map<Int, StudioSection>>?
            ): Boolean = size > maxPages
        }

    @Synchronized
    fun rails(key: String): MutableMap<Int, StudioSection>? =
        // Copied: the caller publishes these slots and keeps filling them as the
        // refresh streams in, and doing that to the stored map would leave the
        // cache holding a half-refreshed page.
        pages[key]?.let { slots -> HashMap(slots) }

    @Synchronized
    fun store(key: String, slots: Map<Int, StudioSection>) {
        // A page with no rails is not an answer to cache: it would serve
        // "nothing here" to the next entry before the retry that fixes it can
        // run (see the all-rails-failed branch the screens render).
        if (slots.isEmpty()) return
        pages[key] = HashMap(slots)
    }

    @Synchronized
    fun clear() {
        pages.clear()
    }

    @Synchronized
    fun pageCount(): Int = pages.size
}

/**
 * A page's rails in screen order: by declared index.
 *
 * The slots map is filled as rails arrive (and pre-filled from the cache on a
 * hit), so the index - not the insertion order of a ConcurrentHashMap - is what
 * keeps a rail where the page declared it.
 */
internal fun sectionsInRailOrder(
    slots: Map<Int, StudioSection>
): List<StudioSection> =
    slots.entries.sortedBy { entry -> entry.key }.map { entry -> entry.value }

/**
 * The slots a refresh leaves behind: the ones the fresh pass still carries.
 *
 * The refresh streams over the SAME slots the cache was published into, so a
 * rail that grows replaces its entry in place and a rail that is still loading
 * keeps the cached rows on screen - that is what makes the refresh invisible.
 * But a rail the fresh pass turns out to have no rows for is never reported at
 * all (an empty rail is dropped, not published as empty), so its cached entry
 * would otherwise survive the refresh and the page would keep a rail that no
 * longer exists. Reconciling against the fresh result drops exactly those.
 */
internal fun refreshedSlots(
    slots: Map<Int, StudioSection>,
    fresh: List<StudioSection>
): Map<Int, StudioSection> {
    if (fresh.isEmpty()) return emptyMap()
    val freshTitles = fresh.mapTo(HashSet()) { section -> section.title }
    return slots.filterValues { section -> section.title in freshTitles }
}
