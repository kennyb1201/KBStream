package com.kennyb1201.kbstream.data.tmdb

/**
 * The root id a Stremio stream request names a title by.
 *
 * A stream request is `<root id>[:<season>:<episode>]`, and the add-ons resolve
 * that root against their own id index. The root the app arrives with is the
 * catalog's - AIOMetadata hands a series catalog's `tt3121722` out for a show
 * whose TMDB record (`57532`) says its IMDB id is `tt4873036` - and a catalog's
 * mapping can be stale or simply wrong. Trusting it blindly is what made every
 * PAW Patrol episode request the wrong title's streams (Dino Movie, on every
 * season 7 episode).
 *
 * So the root is taken from TMDB's own external-ids mapping whenever there is a
 * verified TMDB id to ask about, and the id the screen was opened with is only
 * the fallback. The same rule covers movies, where the root IS the whole id.
 *
 * Only a well-formed `tt...` answer is accepted. Anything else - a blank, a
 * null for a show TMDB has no IMDB record for, a `tvdb:`/`tmdb:` flavor - is a
 * request id no add-on could resolve, which is strictly worse than the id the
 * app already had.
 *
 * The resolved id is deliberately the TMDB *IMDB* id rather than the TMDB id:
 * episode stream ids are already built in the `tt...` flavor (see
 * [com.kennyb1201.kbstream.data.player.fileEpisodeStreamId]), and that is the
 * form playback-history rows and the episode-scheme store are keyed by, so one
 * canonical root is what keeps a title's request, its history and its detected
 * file numbering talking about the same show.
 */
internal fun preferredStreamRootId(routeId: String, resolvedImdbId: String?): String {
    val candidate = resolvedImdbId?.trim().orEmpty()
    return if (candidate.startsWith("tt") && candidate.length > 2) candidate else routeId
}
