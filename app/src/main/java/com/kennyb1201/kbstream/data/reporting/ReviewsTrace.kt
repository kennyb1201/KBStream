package com.kennyb1201.kbstream.data.reporting

/**
 * What the review rail's two legs did, in one line per Detail load
 * (spec 2026-11-22, F3).
 *
 * The rail is fed by two independent reads - TMDB's paginated reviews and
 * Trakt's comments - and a paste could not tell "the Trakt leg never fired"
 * from "it fired and returned nothing" from "it landed and was overwritten".
 * That is exactly the question the MobLand report needed answered by hand, so
 * each leg records its outcome here.
 *
 * One line, overwritten per load: it is a statement about the title on screen
 * now, not a history. That is why it is not folded into [DetailTrace]'s
 * sixteen-line ring, which is focus history and would evict the walk a trap
 * report exists to show.
 *
 * Counts and reasons only - never a title, an id or review text.
 */
internal object ReviewsTrace {

    private val lock = Any()

    /** Reviews the TMDB leg published, or null before it has answered. */
    private var tmdb: Int? = null

    /** Trakt's count, or `empty:<reason>` for a leg that answered nothing. */
    private var trakt: String? = null

    /** A new Detail load owns the line: the previous title's legs are not it. */
    fun beginLoad() {
        synchronized(lock) {
            tmdb = null
            trakt = null
        }
    }

    /** The TMDB leg published [count] reviews. */
    fun noteTmdb(count: Int) {
        synchronized(lock) { tmdb = count }
    }

    /** The Trakt leg published [count] comments. */
    fun noteTrakt(count: Int) {
        synchronized(lock) { trakt = count.toString() }
    }

    /**
     * The Trakt leg answered nothing, and why: `no-token`, `none` (zero
     * comments), `guard` (the page was retargeted), or `error:<reason>`.
     */
    fun noteTraktEmpty(reason: String) {
        synchronized(lock) { trakt = "empty:$reason" }
    }

    /** `reviews: tmdb=3 trakt=12`, or null before a load has answered. */
    fun summary(): String? =
        synchronized(lock) {
            if (tmdb == null && trakt == null) {
                null
            } else {
                "reviews: tmdb=${tmdb ?: "pending"} trakt=${trakt ?: "pending"}"
            }
        }

    /** Clears the line (tests). */
    fun reset() = beginLoad()
}
