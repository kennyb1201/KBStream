package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.tmdb.TmdbSearchCollectionResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchKeywordResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult

/**
 * A tiny in-memory cache of finished searches, keyed by the NORMALIZED query.
 *
 * Retyping a query used to re-fire every TMDB wave plus the add-on probes, even
 * when the answer was on screen a moment ago (typing "ali", correcting to "al",
 * then back to "ali" paid for three searches). Inside the TTL a repeat is
 * answered from here instead.
 *
 * Deliberately plain and small:
 *  - MEMORY ONLY. Nothing is written to disk, so a restart never serves a
 *    result set that belonged to another session - the same reason the badge
 *    sets are rebuilt from scratch on a profile switch.
 *  - [ttlMillis] because search results are a snapshot of a catalogue that
 *    moves; five minutes is the window where "the same query" is still the same
 *    question.
 *  - [maxEntries] with least-recently-written eviction, so a long session
 *    cannot grow this without bound.
 *
 * The clock is injected so the TTL is testable without sleeping.
 */
internal class SearchCache<T>(
    private val maxEntries: Int = 5,
    private val ttlMillis: Long = 5 * 60 * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private class Entry<T>(val value: T, val expiresAt: Long)

    // Insertion-ordered, and a re-put removes then re-inserts, so the first key
    // is always the least recently written one.
    private val entries = LinkedHashMap<String, Entry<T>>()

    /** The cached answer for [query], or null when absent or expired. */
    fun get(query: String): T? {
        val key = key(query)
        val entry = entries[key] ?: return null
        if (clock() >= entry.expiresAt) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    /** Remember [value] for [query], refreshing both its recency and its TTL. */
    fun put(query: String, value: T) {
        val key = key(query)
        entries.remove(key)
        entries[key] = Entry(value, clock() + ttlMillis)
        while (entries.size > maxEntries) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
    }

    /**
     * Forget everything. A profile switch calls this: one profile's results
     * must never be served to another, and the ceiling that shaped them is the
     * profile's, not the query's.
     */
    fun clear() {
        entries.clear()
    }

    /** How many answers are held (for tests and for the reset path's sake). */
    val size: Int get() = entries.size

    /**
     * Two queries are the same question when they differ only in case or
     * surrounding space - exactly the rule [SearchViewModel.commitSearch] uses
     * to keep one recent-search chip instead of two.
     */
    private fun key(query: String): String = query.trim().lowercase()
}

/**
 * Everything one finished search published, so a repeat inside the TTL can be
 * put back on screen without asking anyone.
 *
 * [titles] is the Titles rail's UNFILTERED master (the paging state's
 * `results`), not the filtered view: the rail's own type filter and sort are
 * views of it, and they have to keep working on a restored answer exactly as
 * they do on a fresh one. [titlesTotal] / [titlesTotalPages] are TMDB's counts,
 * so the rail's "showing N of M" and its paging still read true.
 *
 * [tmdbDedup] travels with the answer because the add-on wave de-dupes its rows
 * against the TMDB rail - restoring the titles without their index would let the
 * add-on rails echo them.
 */
internal data class SearchSnapshot(
    val titles: List<SearchTitleResult>,
    val titlesTotal: Int,
    val titlesTotalPages: Int,
    val actors: List<TmdbSearchPersonResult>,
    val studios: List<StudioSearchHit>,
    val collections: List<TmdbSearchCollectionResult>,
    val keywords: List<TmdbSearchKeywordResult>,
    val tmdbDedup: TmdbDedupIndex
)
