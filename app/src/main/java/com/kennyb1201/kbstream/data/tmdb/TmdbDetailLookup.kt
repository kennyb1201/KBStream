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
 *
 * [Unresolvable] is the third verdict, for the opposite failure: the card's id
 * is not a shape any TMDB lookup accepts (`tmdb:`, `tt...`, `tvdb:`, a bare
 * number), so there is nothing to ask about at all. It answers "no title can
 * ever resolve from this row", which is why it is allowed to drop a card for
 * the same reason [NotFound] is - while staying a DISTINCT verdict, so a trace
 * can tell "TMDB said this id does not exist" from "this id names nothing we
 * can ask TMDB" (the two need different fixes).
 */
sealed interface TmdbDetailLookup {

    /** TMDB answered with the title. */
    data class Found(val detail: TmdbDetail) : TmdbDetailLookup

    /** TMDB answered 404: this id names no title of this type. */
    data object NotFound : TmdbDetailLookup

    /** No usable answer: offline, 429/5xx, timeout, no key, an unparseable body. */
    data object Transient : TmdbDetailLookup

    /**
     * The id is no flavor any lookup accepts, so nothing can be resolved from it.
     *
     * Distinct from [NotFound] on purpose: that one is TMDB's answer about a real
     * id, this one is the app's answer about an id it cannot even ask about (a
     * slug, an add-on's own key). Both mean the row cannot name its show, but a
     * 404 is fixed by repairing the id to a real one while this one may need the
     * id shape itself understood first.
     */
    data object Unresolvable : TmdbDetailLookup
}

/**
 * Whether this verdict says the row's id names no title - the only verdicts a
 * Continue Watching card may be dropped on (see
 * [com.kennyb1201.kbstream.ui.home.dropsZombieCard]).
 *
 * [TmdbDetailLookup.Transient] is deliberately not one: "we could not ask" is
 * not a fact about the id, and dropping on it would empty the rail during an
 * outage.
 */
internal val TmdbDetailLookup.namesNoTitle: Boolean
    get() = this == TmdbDetailLookup.NotFound || this == TmdbDetailLookup.Unresolvable
