package com.kennyb1201.kbstream.data.bingecat

/**
 * One Library row BingeCat contributed.
 *
 * Identity is TMDB (what every KBStream screen renders) with the IMDb id kept
 * when the read carried one; a row with neither is skipped rather than guessed
 * into a title (spec F4: unresolved items are absent, never broken cards).
 */
data class BingeCatRow(
    val title: String,
    val year: Int?,
    val tmdbId: Int?,
    val imdbId: String?,
    val isMovie: Boolean
) {
    val mediaType: String get() = if (isMovie) "movie" else "series"

    /** The watched-badge key this row is matched under inside BingeCat only. */
    fun watchedKey(): String? {
        val type = if (isMovie) "movie" else "series"
        return when {
            !imdbId.isNullOrBlank() -> "$type::$imdbId"
            tmdbId != null && tmdbId > 0 -> "$type::tmdb:$tmdbId"
            else -> null
        }
    }
}

/**
 * Whether this row IS the title the caller is holding, for the watchlist and
 * list membership checks (step 3's suffix and picker ticks).
 *
 * The same rule the MDBList and Trakt membership tests already use: the type
 * must agree (a movie and a show can share an id in some id spaces), and one id
 * must match on both sides. An id pair the row does not carry is not a match,
 * never a guess - a permanently saved-looking button is worse than an unsaved
 * one, because nothing on screen would ever correct it.
 */
fun BingeCatRow.matches(mediaType: String, imdbId: String?, tmdbId: Int?): Boolean {
    val wantedMovie = mediaType.lowercase() !in setOf("tv", "series")
    if (isMovie != wantedMovie) return false
    val queryImdb = imdbId?.takeIf { it.isNotBlank() }
    val queryTmdb = tmdbId?.takeIf { it > 0 }
    return (queryImdb != null && this.imdbId == queryImdb) ||
        (queryTmdb != null && this.tmdbId == queryTmdb)
}

/** One BingeCat user list, as Library needs it. */
data class BingeCatList(
    val ref: String,
    val name: String,
    val itemCount: Int
)

/**
 * Turns one wire media object into a Library row, or null when it cannot be
 * shown.
 *
 * Pure and top-level so the skip rule is a unit test: the reads deliver the ids
 * inline (the contract's `PublicMedia.ids`), so nothing here resolves anything
 * over the network, and a title with no id is exactly what it looks like - a
 * row this app has no page for.
 */
fun bingecatRow(media: BingeCatMedia?, isMovie: Boolean): BingeCatRow? {
    val title = media?.title?.takeIf { it.isNotBlank() } ?: return null
    val ids = media.ids
    val tmdb = ids?.tmdb?.takeIf { it > 0 }
    val imdb = ids?.imdb?.takeIf { it.isNotBlank() }
    if (tmdb == null && imdb == null) return null
    return BingeCatRow(
        title = title,
        year = media.year?.takeIf { it > 0 },
        tmdbId = tmdb,
        imdbId = imdb,
        isMovie = isMovie
    )
}

/** One `sync/{kind}` row, whichever media field it carries. */
fun bingecatSavedRow(item: BingeCatSavedItem): BingeCatRow? =
    bingecatRow(item.media, item.isMovie)

/** One list entry. */
fun bingecatListRow(item: BingeCatListItem): BingeCatRow? =
    bingecatRow(item.media, item.isMovie)

/** One history entry - read only to know what BingeCat says is watched. */
fun bingecatHistoryRow(item: BingeCatHistoryItem): BingeCatRow? =
    bingecatRow(item.media, item.type == "movie")

/** The list entries that could not be shown, for the diagnostics line. */
fun bingecatSkippedCount(items: List<BingeCatSavedItem>): Int =
    items.count { bingecatSavedRow(it) == null }

/**
 * The shelf id for a BingeCat list.
 *
 * [com.kennyb1201.kbstream.data.library.LibraryList.id] is an Int shared with
 * local, MDBList and Trakt shelves, and Trakt already owns the `Int.MIN_VALUE`
 * run. BingeCat therefore sits in the neighbouring run that no local id (a
 * negative creation time) and no listing id can reach.
 */
fun bingecatShelfId(index: Int): Int =
    Int.MIN_VALUE / 2 + index.coerceAtLeast(0)
