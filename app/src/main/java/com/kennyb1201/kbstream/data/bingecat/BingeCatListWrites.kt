package com.kennyb1201.kbstream.data.bingecat

import android.util.Log

/*
 * Outbound list writes for [BingeCatRepository]: the watchlist add/remove behind
 * the Detail Library button and the Library tab's remove, and one user list's
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
 * The kind the watchlist route is addressed by.
 *
 * `favorites` is the sibling the same route also accepts; the saved list is
 * step 1's read-only section and is deliberately not written here (step 3's
 * scope), so only this name is used.
 */
private const val WATCHLIST_KIND = "watchlist"

internal suspend fun BingeCatRepository.addToWatchlistImpl(
    isMovie: Boolean,
    imdbId: String?,
    tmdbId: Int?,
    title: String?
): Boolean {
    val batch = watchlistBatch(isMovie, imdbId, tmdbId)
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
    val batch = watchlistBatch(isMovie, imdbId, tmdbId)
        ?: return bail("watchlist remove", imdbId, tmdbId)
    return submitSavedWrite(
        add = false,
        kind = WATCHLIST_KIND,
        batch = batch,
        label = "watchlist ${title ?: imdbId ?: tmdbId}"
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
 * The watchlist body for one title.
 *
 * A null batch means this app cannot name the title, and no request is made:
 * [BingeCatWriteRules.writeIds] refuses a selector with neither a usable IMDb
 * nor TMDB id, and the contract refuses the empty batch too.
 */
private fun watchlistBatch(
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
