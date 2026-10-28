package com.kennyb1201.kbstream.data.library

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The taste signals' translation layer: one thumb, one number, on the scale
 * both trackers already speak.
 *
 * Simkl and Trakt both rate on an integer 1-10 (the same scale the local store
 * uses, which is why the store was built on it), so ONE mapping serves both and
 * no per-tracker table exists:
 *
 *  - **LIKE -> 8.** The strong-positive value that leaves 9-10 above it for an
 *    explicit numeric rating (the numeric-ratings follow-up), without claiming
 *    a perfect score for a thumb.
 *  - **DISLIKE -> 2.** Its mirror: the strong-negative value that leaves 1 for
 *    an explicit worst rating.
 *  - **A cleared signal is a REMOVAL, not a low rating.** Taking a rating back
 *    is `submitRating(rating = null)`, which the trackers answer with their
 *    remove endpoint - a dislike must stay visible as a rating the viewer gave,
 *    and a clear must actually take the rating away.
 *
 * Pure by construction - no Android, no network, no clock of its own - so every
 * rule here is pinned by a plain JVM test, and the same functions serve the
 * submission queue, the two tracker legs and the diagnostics line.
 */
object TasteRatingMapping {

    /** A thumbs-up, on the trackers' 1-10 scale. */
    const val LIKE_RATING = 8

    /** A thumbs-down, on the trackers' 1-10 scale. */
    const val DISLIKE_RATING = 2

    /** The value a thumb means, or null when there is no thumb to map. */
    fun thumbToRating(signal: String?): Int? = when (signal) {
        TasteSignals.LIKE -> LIKE_RATING
        TasteSignals.DISLIKE -> DISLIKE_RATING
        else -> null
    }

    /**
     * A numeric rating forced into the scale.
     *
     * The store already refuses a write outside 1-10, so this is the second net
     * on the submission path: nothing derived from a stored value may leave the
     * scale, whatever a future writer believes it stored.
     */
    fun clamp(rating: Int): Int =
        rating.coerceIn(TasteSignals.RATING_MIN, TasteSignals.RATING_MAX)

    /**
     * The number a REMOVAL body carries.
     *
     * A remove request shares its shape with an add (Trakt matches the entry on
     * its ids and never reads the value), so the body still needs a number, and
     * the honest one to send is the value the title carried before the clear.
     * A thumb-only signal never held a number, so its own mapping stands in -
     * and 8 is the last resort for a title whose cleared entry recorded neither.
     * The value is presence only: it is never read as an opinion, and the call
     * is a remove either way.
     */
    fun removalWireRating(rating: Int?, signal: String?): Int =
        rating?.let(::clamp) ?: thumbToRating(signal) ?: LIKE_RATING

    /**
     * A store timestamp as the trackers want it (`rated_at`): ISO-8601 UTC to
     * the second, e.g. `2026-10-27T12:34:56Z`. Null when there is no timestamp
     * to send, which both trackers read as "now".
     */
    fun ratedAtIso(epochMs: Long): String? =
        if (epochMs <= 0L) {
            null
        } else {
            Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()
        }

    /**
     * Whether a media type is the movies bucket. Everything else is shows.
     *
     * Only a spelling that actually names movies lands here: a null or blank
     * type is NOT assumed to be a film, because the assumption would send an
     * unknown title to the movies endpoint - where the trackers answer 404 for a
     * show - instead of leaving it in the bucket the two fit-less spellings
     * share. The store's own identity rule (HiddenTitles.normalizedType) is
     * deliberately not reused: it must fold an unknown type into ONE bucket so a
     * title cannot be counted twice, which is a different question.
     */
    fun isMovie(mediaType: String?): Boolean {
        val raw = mediaType?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return false
        return HiddenTitles.normalizedType(raw) == "movie"
    }

    // ------------------------------------------------------------------
    // Reading the store's own key spellings back out
    // ------------------------------------------------------------------

    /**
     * The IMDb id inside a store key set, or null.
     *
     * The store's keys are [HiddenTitles.keysFor]'s: a non-TMDB id is stored
     * untyped (`"id::tt0133093"`), a TMDB id typed (`"movie::tmdb:603"`), and a
     * derived name key (`"title::movie::dune::2021"`) carries no id at all. Only
     * the id spellings answer here, which is what makes a title the store knows
     * by name alone come back as "no id" - and be refused before any request -
     * rather than being sent with something invented.
     */
    fun imdbIdOf(keys: List<String>): String? = keys
        .firstOrNull { it.startsWith(IMDB_KEY_PREFIX) }
        ?.removePrefix(IMDB_KEY_PREFIX)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    /** The TMDB id inside a store key set, or null. */
    fun tmdbIdOf(keys: List<String>): Int? = keys
        .firstOrNull { TMDB_KEY_MARKER in it }
        ?.substringAfterLast(TMDB_KEY_MARKER)
        ?.takeWhile(Char::isDigit)
        ?.toIntOrNull()

    /** `"id::tt0133093"` - the store's spelling for a non-TMDB id. */
    private const val IMDB_KEY_PREFIX = "id::"

    /** `"movie::tmdb:603"` - the store's spelling for a TMDB id. */
    private const val TMDB_KEY_MARKER = "tmdb:"
}
