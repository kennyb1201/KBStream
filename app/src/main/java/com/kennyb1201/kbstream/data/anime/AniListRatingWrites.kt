package com.kennyb1201.kbstream.data.anime

import android.content.Context

/**
 * The AniList leg of the taste-rating fan-out (spec 2026-11-13 phase 2, F3).
 *
 * Ratings reach the trackers through ONE durable queue
 * (`TasteRatingSubmission`), and this is what its AniList leg calls. It exists as
 * a function of its own rather than inline in the drain for the reason the other
 * legs do: the drain is the queue's business, and what an AniList write looks
 * like is AniList's.
 *
 * Two differences from the other legs, both forced by AniList's shape:
 *
 *  - AniList is addressed by ITS OWN media id, not by IMDb/TMDB - so the title is
 *    resolved through the phase-1 ID hub, once per title per process;
 *  - taking a rating back is `scoreRaw: 0`, because AniList's list entry has no
 *    "no score" in a GraphQL input - 0 is the documented "unscored" value, and a
 *    cleared KBStream signal must not leave the viewer's old score standing.
 */
internal const val ANILIST_RATINGS_ENABLED = true

/** What one AniList rating attempt produced - the queue's own vocabulary. */
internal enum class AniListRatingOutcome {
    LANDED,

    /** AniList has no id for this title, or this profile has no session. */
    NO_IDS,

    /** The title exists on AniList but the write could not be matched to it. */
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
 * [title] is the only identity the queue has that AniList can be asked about;
 * [rating] is KBStream's 1-10 (null with [removal] means "take it back").
 */
internal suspend fun submitAniListRating(
    context: Context,
    title: String?,
    rating: Int?,
    removal: Boolean
): AniListRatingOutcome {
    if (!AniListWrites.connected(context)) return AniListRatingOutcome.NO_IDS

    val mediaId = runCatching { AnimeIdHub.resolve(title).anilistId }
        .getOrNull()
        ?.takeIf { it > 0 }
        ?: return AniListRatingOutcome.NO_IDS

    if (removal) {
        return when (AniListLibrary.clearRating(context, mediaId)) {
            is AniListWriteOutcome.Success -> AniListRatingOutcome.LANDED
            is AniListWriteOutcome.NeedsReconnect -> AniListRatingOutcome.AUTH
            is AniListWriteOutcome.EntryGone -> AniListRatingOutcome.LANDED
            is AniListWriteOutcome.RateLimited -> AniListRatingOutcome.PAUSED
            is AniListWriteOutcome.Failed -> AniListRatingOutcome.NO_ANSWER
        }
    }

    val score = rating ?: return AniListRatingOutcome.NO_IDS
    return when (AniListLibrary.pushRating(context, mediaId, score)) {
        is AniListWriteOutcome.Success -> AniListRatingOutcome.LANDED
        is AniListWriteOutcome.NeedsReconnect -> AniListRatingOutcome.AUTH
        is AniListWriteOutcome.EntryGone -> AniListRatingOutcome.NOT_FOUND
        is AniListWriteOutcome.RateLimited -> AniListRatingOutcome.PAUSED
        is AniListWriteOutcome.Failed -> AniListRatingOutcome.NO_ANSWER
    }
}
