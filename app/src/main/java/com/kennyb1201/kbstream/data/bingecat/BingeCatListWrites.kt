package com.kennyb1201.kbstream.data.bingecat

import android.util.Log

/*
 * Outbound list writes for [BingeCatRepository]: the watchlist add/remove behind
 * the Detail Library button and the Library tab's remove, the Favorites
 * add/remove behind the thumbs (the favorites spec), and one user list's
 * add/remove behind the picker (step 3, `lists:write`).
 *
 * These live outside BingeCatRepository.kt only to keep that file readable -
 * they are still the repository's behavior, called through same-named members on
 * it. The gate, the failure contract (401 refresh, 429 park, 403 ->
 * reconnect, cache invalidation) and the profile pinning live in
 * [BingeCatRepository.submitSavedWrite] / [BingeCatRepository.submitListItemsWrite];
 * these functions only turn one app action into the body the route wants:
 *
 *  - the watchlist takes an `ids` object per title (`sync/watchlist`), and
 *  - a user list takes MEDIA REFERENCE STRINGS (`/lists/{ref}/items`).
 *
 * Both read their identity from the same pair the history writes use, and both
 * bail (false, no request) on a title this app cannot name rather than sending a
 * guessed id.
 */

private const val TAG = "BINGECAT_REPO"

/**
 * The two kinds `sync/{kind}` is addressed by (the contract's own `Kind` enum,
 * `favorites` | `watchlist`).
 *
 * Both take the SAME `SavedSelectionBatch` body and both are gated on
 * `lists:write` - the contract states the scope per operation
 * (`x-required-scopes: [lists:write]` on both the add and the remove), so one
 * leg's grant is the other's. They stay separate constants because the ROUTE
 * and the account's own list are separate.
 */
private const val WATCHLIST_KIND = "watchlist"
private const val FAVORITES_KIND = "favorites"

internal suspend fun BingeCatRepository.addToWatchlistImpl(
    isMovie: Boolean,
    imdbId: String?,
    tmdbId: Int?,
    title: String?
): Boolean {
    val batch = savedBatch(isMovie, imdbId, tmdbId)
        ?: return bail("watchlist add", imdbId, tmdbId)
    return submitSavedWrite(
        add = true,
        kind = WATCHLIST_KIND,
        batch = batch,
        label = "watchlist ${title ?: imdbId ?: tmdbId}"
    )
}

internal suspend fun BingeCatRepository.removeFromWatchlistImpl(
    isMovie: Boolean,
    imdbId: String?,
    tmdbId: Int?,
    title: String?
): Boolean {
    // The removal carries the same `ids` object the add does: a watchlist entry
    // is identified by its media, and the contract's remove batch is the same
    // `SavedSelectionBatch` (there is no timestamp to leave out).
    val batch = savedBatch(isMovie, imdbId, tmdbId)
        ?: return bail("watchlist remove", imdbId, tmdbId)
    return submitSavedWrite(
        add = false,
        kind = WATCHLIST_KIND,
        batch = batch,
        label = "watchlist ${title ?: imdbId ?: tmdbId}"
    )
}

/**
 * The thumbs' favorites leg: the mapped profile's Favorites gains this title.
 *
 * No history is recorded - `sync/favorites` and `sync/history` are different
 * routes, and nothing here calls the history one, so a thumb tap can never add
 * or remove a watch event.
 */
internal suspend fun BingeCatRepository.addToFavoritesImpl(
    isMovie: Boolean,
    imdbId: String?,
    tmdbId: Int?,
    title: String?
): Boolean {
    val batch = savedBatch(isMovie, imdbId, tmdbId)
        ?: return bail("favorites add", imdbId, tmdbId)
    return submitSavedWrite(
        add = true,
        kind = FAVORITES_KIND,
        batch = batch,
        label = "favorites ${title ?: imdbId ?: tmdbId}"
    )
}

/** The thumbs' unsave leg, on the same batch and the same contract. */
internal suspend fun BingeCatRepository.removeFromFavoritesImpl(
    isMovie: Boolean,
    imdbId: String?,
    tmdbId: Int?,
    title: String?
): Boolean {
    val batch = savedBatch(isMovie, imdbId, tmdbId)
        ?: return bail("favorites remove", imdbId, tmdbId)
    return submitSavedWrite(
        add = false,
        kind = FAVORITES_KIND,
        batch = batch,
        label = "favorites ${title ?: imdbId ?: tmdbId}"
    )
}

internal suspend fun BingeCatRepository.addToListImpl(
    ref: String,
    isMovie: Boolean,
    imdbId: String?,
    tmdbId: Int?,
    title: String?
): Boolean {
    val mediaRef = mediaRefFor(isMovie, imdbId, tmdbId)
        ?: return bail("list add", imdbId, tmdbId)
    return submitListItemsWrite(
        add = true,
        ref = ref,
        items = listOf(mediaRef),
        label = "list $ref ${title ?: mediaRef}"
    )
}

internal suspend fun BingeCatRepository.removeFromListImpl(
    ref: String,
    isMovie: Boolean,
    imdbId: String?,
    tmdbId: Int?,
    title: String?
): Boolean {
    val mediaRef = mediaRefFor(isMovie, imdbId, tmdbId)
        ?: return bail("list remove", imdbId, tmdbId)
    return submitListItemsWrite(
        add = false,
        ref = ref,
        items = listOf(mediaRef),
        label = "list $ref ${title ?: mediaRef}"
    )
}

/**
 * The `SavedSelectionBatch` body for one title - the watchlist's and the
 * favorites' alike, because the contract gives both kinds the same body.
 *
 * A null batch means this app cannot name the title, and no request is made:
 * [BingeCatWriteRules.writeIds] refuses a selector with neither a usable IMDb
 * nor TMDB id, and the contract refuses the empty batch too.
 */
private fun savedBatch(
    isMovie: Boolean,
    imdbId: String?,
    tmdbId: Int?
): BingeCatSavedBatch? {
    val parent = parentIdOf(imdbId, tmdbId) ?: return null
    val ids = BingeCatWriteRules.writeIds(parent, tmdbId) ?: return null
    return if (isMovie) {
        BingeCatSavedBatch(movies = listOf(BingeCatSavedMovie(ids)))
    } else {
        BingeCatSavedBatch(shows = listOf(BingeCatSavedShow(ids)))
    }
}

/** The one media reference a list-item write sends, or null when unnamed. */
private fun mediaRefFor(isMovie: Boolean, imdbId: String?, tmdbId: Int?): String? {
    val parent = parentIdOf(imdbId, tmdbId) ?: return null
    return BingeCatWriteRules.mediaRef(parent, tmdbId, isMovie)
}

/**
 * The app's own spelling of the title, in the form the write rules parse.
 *
 * IMDb first: it is the id the tracker mirrors prefer and the one a list-item
 * reference can carry without a type. The TMDB alternative is prefixed so the
 * rules can tell an id from a slug.
 */
private fun parentIdOf(imdbId: String?, tmdbId: Int?): String? =
    imdbId?.trim()?.takeIf { it.isNotBlank() }
        ?: tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" }

/** The unparseable-id bail: nothing the server could resolve, so nothing is sent. */
private fun bail(kind: String, imdbId: String?, tmdbId: Int?): Boolean {
    Log.i(TAG, "list write skipped: unparseable $kind id=$imdbId/$tmdbId")
    return false
}
