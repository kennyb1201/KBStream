package com.kennyb1201.kbstream.data.tmdb

/**
 * What a TMDB detail lookup said about one title.
 *
 * The plain call ([TmdbRepository.getDetailByTmdbId]) answers a record or null,
 * which is all a screen needs: it either has metadata to draw or it does not.
 * A caller that WRITES something from the answer needs more than that, because
 * "TMDB says this id names no title" and "we could not ask TMDB" are different
 * facts that look identical as a null:
 *
 *  - a Continue Watching row filed under an id TMDB has never heard of is a
 *    zombie - no art, no resolved name - and its card should go;
 *  - the same row during an outage, a quota trip or a timeout is a card that
 *    must stay, or the rail would empty every time the network hiccups.
 *
 * So only a 404 - the definitive miss - is [NotFound]. Every other failure,
 * including a missing API key, is [Transient]: it is not a verdict about the
 * id. Neither miss is cached, so a retry with the right id (or after the
 * outage) can still answer.
 */
sealed interface TmdbDetailLookup {

    /** TMDB answered with the title. */
    data class Found(val detail: TmdbDetail) : TmdbDetailLookup

    /** TMDB answered 404: this id names no title of this type. */
    data object NotFound : TmdbDetailLookup

    /** No usable answer: offline, 429/5xx, timeout, no key, an unparseable body. */
    data object Transient : TmdbDetailLookup
}
