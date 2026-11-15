package com.kennyb1201.kbstream.data.reporting

import android.os.SystemClock

/**
 * What one search run cost, split by leg (spec 2026-11-15, search speedup F1).
 *
 * There was no `search.*` measurement anywhere in the tree - no label, no count -
 * so "search feels slow" could only be blamed on guesses. The flow is a debounce,
 * then six TMDB waves and an add-on wave; any of those could be the cost, and the
 * fixes are different for each (shorten the debounce / stream a leg earlier /
 * widen the probe fan-out). This records the split the paste needs:
 *
 *  - `search.titles` - submit → the TMDB rails published, i.e. the first built-in
 *    results on screen. It contains the debounce, the slowest TMDB leg and the
 *    merge, which is why the legs are recorded separately.
 *  - `search.addon` - submit → the add-on rails published. Its own publish path
 *    (see the ViewModel's consolidate step), so the two numbers say whether the
 *    add-on wave is what the viewer is waiting for.
 *  - `search.leg.*` - each TMDB wave on its own: movies, tv, person, studio,
 *    collection, keyword.
 *  - the debounce, as a FACT rather than a timing: it is a constant, and the line
 *    has to be able to say whether a slow search was the debounce or the network.
 *  - whether the answer came from the short-lived search cache (a repeat inside
 *    the TTL fires no wave at all).
 *
 * Held here as well as in [PerfTrace] because the report is taken from Settings,
 * and a session's browsing can evict the newest samples: the facts of the LAST
 * run are a handful of values and per-process, so the line describes the search
 * that just happened rather than whatever is left in the ring.
 */
internal object SearchRunTrace {

    private val lock = Any()

    private var submittedAtMs = 0L

    private var cachedAnswer = false

    private var debounceMs = 0L

    private var titlesMs = -1L

    private var addonMs = -1L

    private var titlesCount = -1

    private var addonGroups = -1

    private var legs: List<Pair<String, Long>> = emptyList()

    /** One run's figures, for the diagnostics line. */
    data class Facts(
        val debounceMs: Long,
        val cached: Boolean,
        val titlesMs: Long,
        val addonMs: Long,
        val titlesCount: Int,
        val addonGroups: Int,
        val legs: List<Pair<String, Long>>
    )

    /**
     * Stamps the submit - the keystroke that won the debounce, or a repeat
     * answered from the cache.
     */
    fun markSubmitted(
        cached: Boolean,
        debounce: Long,
        atMs: Long = SystemClock.elapsedRealtime()
    ) {
        synchronized(lock) {
            submittedAtMs = atMs
            cachedAnswer = cached
            debounceMs = debounce
            titlesMs = -1L
            addonMs = -1L
            titlesCount = -1
            addonGroups = -1
            legs = emptyList()
        }
    }

    /**
     * Times one TMDB wave under `search.leg.<name>`.
     *
     * A suspend-legal wrapper rather than a caller-side stopwatch, so every wave
     * is measured the same way and a wave that throws still records its cost.
     */
    suspend fun <T> timedLeg(
        name: String,
        clock: () -> Long = { SystemClock.elapsedRealtime() },
        block: suspend () -> T
    ): T {
        val startedAt = clock()
        try {
            return block()
        } finally {
            val ms = clock() - startedAt
            PerfTrace.record("search.leg.$name", ms)
            synchronized(lock) {
                legs = legs.filterNot { it.first == name } + (name to ms)
            }
        }
    }

    /** Closes the titles leg: submit → the TMDB rails on screen. */
    fun onTitlesPublished(count: Int, atMs: Long = SystemClock.elapsedRealtime()) {
        val leg = synchronized(lock) {
            if (submittedAtMs <= 0L || titlesMs >= 0L) return
            titlesCount = count
            titlesMs = (atMs - submittedAtMs).coerceAtLeast(0L)
            titlesMs
        }
        PerfTrace.record("search.titles", leg)
    }

    /** Closes the add-on leg: submit → the add-on rails on screen. */
    fun onAddonsPublished(groups: Int, atMs: Long = SystemClock.elapsedRealtime()) {
        val leg = synchronized(lock) {
            if (submittedAtMs <= 0L || addonMs >= 0L) return
            addonGroups = groups
            addonMs = (atMs - submittedAtMs).coerceAtLeast(0L)
            addonMs
        }
        PerfTrace.record("search.addon", leg)
    }

    /** Clears the run, so the next report cannot describe a superseded one. */
    fun reset() {
        synchronized(lock) {
            submittedAtMs = 0L
            cachedAnswer = false
            debounceMs = 0L
            titlesMs = -1L
            addonMs = -1L
            titlesCount = -1
            addonGroups = -1
            legs = emptyList()
        }
    }

    /** The last run's facts, or null when nothing has been searched this session. */
    fun facts(): Facts? = synchronized(lock) {
        if (submittedAtMs <= 0L) return null
        Facts(
            debounceMs = debounceMs,
            cached = cachedAnswer,
            titlesMs = titlesMs,
            addonMs = addonMs,
            titlesCount = titlesCount,
            addonGroups = addonGroups,
            legs = legs
        )
    }
}
