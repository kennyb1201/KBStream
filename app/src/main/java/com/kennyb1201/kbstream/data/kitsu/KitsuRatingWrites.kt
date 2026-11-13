package com.kennyb1201.kbstream.data.kitsu

import android.content.Context
import com.kennyb1201.kbstream.data.anime.AnimeIdHub

/**
 * The Kitsu leg of the taste-rating fan-out (spec 2026-11-13 phase 2, F4).
 *
 * Ratings reach the trackers through ONE durable queue
 * (`TasteRatingSubmission`), and this is what its Kitsu leg calls. It exists as
 * a function of its own rather than inline in the drain for the reason the other
 * three legs do: the drain is the queue's business, and what a Kitsu write looks
 * like is Kitsu's.
 *
 * Two differences from the other legs, both forced by Kitsu's shape:
 *
 *  - Kitsu is addressed by ITS OWN anime id, not by IMDb/TMDB - so the title is
 *    resolved through the phase-1 ID hub, once per title per process.
 *  - a cleared signal is `ratingTwenty: null` on the existing entry, not a
 *    status change, and a title with no entry has nothing to clear.
 *
 * A removed rating and a set one both go through the same entry-id resolution
 * and cache as a progress write, so a rating after an episode costs no extra
 * lookup.
 */
internal const val KITSU_RATINGS_ENABLED = true

/** What one Kitsu rating attempt produced - the queue's own vocabulary. */
internal enum class KitsuRatingOutcome {
    LANDED,

    /** Kitsu has no id for this title, or this profile has no session. */
    NO_IDS,

    /** The title exists on Kitsu but the write could not be matched to it. */
    NOT_FOUND,

    /** The grant is dead: drop the entry and close the leg until reconnect. */
    AUTH,

    /** A 429 window: leave the entry queued, it is due after the park. */
    PAUSED,

    /** A transport error, a 5xx or an unreadable body. */
    NO_ANSWER
}

/**
 * One rating attempt for the queue.
 *
 * [title] is the only identity the queue has that Kitsu can be asked about;
 * [rating] is KBStream's 1-10 (null with [removal] means "take it back").
 */
internal suspend fun submitKitsuRating(
    context: Context,
    title: String?,
    rating: Int?,
    removal: Boolean
): KitsuRatingOutcome {
    if (!KitsuWrites.connected(context)) return KitsuRatingOutcome.NO_IDS

    val kitsuId = runCatching { AnimeIdHub.resolve(title).kitsuId }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return KitsuRatingOutcome.NO_IDS

    if (removal) {
        return when (val outcome = KitsuLibrary.clearRating(context, kitsuId)) {
            is KitsuWriteOutcome.Success -> KitsuRatingOutcome.LANDED
            is KitsuWriteOutcome.NeedsReconnect -> KitsuRatingOutcome.AUTH
            is KitsuWriteOutcome.EntryGone -> KitsuRatingOutcome.LANDED
            is KitsuWriteOutcome.RateLimited -> KitsuRatingOutcome.PAUSED
            is KitsuWriteOutcome.Failed -> KitsuRatingOutcome.NO_ANSWER
        }
    }

    val score = rating ?: return KitsuRatingOutcome.NO_IDS
    return when (val outcome = KitsuLibrary.pushRating(context, kitsuId, score)) {
        is KitsuWriteOutcome.Success -> KitsuRatingOutcome.LANDED
        is KitsuWriteOutcome.NeedsReconnect -> KitsuRatingOutcome.AUTH
        is KitsuWriteOutcome.EntryGone -> KitsuRatingOutcome.NOT_FOUND
        is KitsuWriteOutcome.RateLimited -> KitsuRatingOutcome.PAUSED
        is KitsuWriteOutcome.Failed -> KitsuRatingOutcome.NO_ANSWER
    }
}
