package com.kennyb1201.kbstream.ui.detail

/**
 * The review rail's one merge rule (spec 2026-11-22, F1).
 *
 * The rail has two sources that answer independently - TMDB's paginated
 * reviews and Trakt's comments - and they used to publish with different
 * semantics: TMDB REPLACED the rail, Trakt appended to it. So whichever
 * answered second decided the result, and a Trakt-first open lost its comments
 * to the TMDB publish that landed after. The outcome was a coin flip on network
 * latency, and it is why a show with thousands of Trakt votes showed only
 * TMDB's handful of written reviews.
 *
 * Both legs now publish through the same rule, from the same place: TMDB first,
 * Trakt after, de-duped by id. Arrival order is no longer an input, the rail's
 * order is deterministic, and a third source is one more list plus one more
 * term here.
 *
 * Generic over the item and its id so the rule can be tested without a Moshi
 * model; the caller passes `{ it.id }`.
 */
internal fun <T> mergeReviewSources(
    tmdb: List<T>,
    trakt: List<T>,
    id: (T) -> String
): List<T> = (tmdb + trakt).distinctBy(id)
