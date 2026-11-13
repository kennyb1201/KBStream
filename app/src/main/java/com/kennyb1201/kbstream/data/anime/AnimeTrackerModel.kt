package com.kennyb1201.kbstream.data.anime

/**
 * One anime on a viewer's list, and what one leg's read produced (spec
 * 2026-11-13, F2).
 *
 * Pure - no JSON, no Android - so the rail's mapping rules can be tested on the
 * JVM, and so the readers in [AnimeTrackerLegs] only have to translate wire
 * shapes into these.
 */
internal enum class AnimeTrackerStatus {
    /** Being watched now: a Continue Watching candidate. */
    IN_PROGRESS,

    /** All aired episodes watched: an Upcoming candidate, never a CW card. */
    COMPLETED,

    /** Planning, paused or dropped: not this rail's business. */
    OTHER
}

/** One anime on a viewer's list, as the legs hand it to the rail. */
internal data class AnimeTrackerEntry(
    /** Every name the service carries, best first; the TMDB bridge tries them all. */
    val titles: List<String>,
    /** Episodes watched, as the service counts them (absolute, from episode 1). */
    val progress: Int,
    /** Total episodes the service lists, or null when it does not know. */
    val episodes: Int?,
    val status: AnimeTrackerStatus,
    /** The next airing episode's epoch seconds, when the service has one. */
    val nextAiringAtSeconds: Long? = null
)

/** What one leg's read produced. */
internal sealed interface AnimeTrackerRead {
    data class Ok(val entries: List<AnimeTrackerEntry>) : AnimeTrackerRead

    /**
     * A username is saved but nothing was readable: no such user, a private
     * profile, or a body this client cannot parse. Never reported as an empty
     * list - "we could not read it" and "it is empty" are different facts, and
     * only the second may clear a leg's cards.
     */
    data class Unreadable(val reason: String) : AnimeTrackerRead

    /** No username saved: the leg is dormant and the Settings row says so. */
    data object NotConfigured : AnimeTrackerRead

    /** The read itself failed (transport, 429). The previous cards stand. */
    data class Failed(val error: Throwable) : AnimeTrackerRead
}
