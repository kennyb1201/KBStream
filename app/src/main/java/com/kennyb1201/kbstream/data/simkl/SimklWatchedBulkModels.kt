package com.kennyb1201.kbstream.data.simkl

data class SimklWatchedBulkRequest(
    val movies: List<MovieIds> = emptyList(),
    val shows: List<ShowIds> = emptyList()
) {
    data class MovieIds(
        val ids: Ids
    )

    data class ShowIds(
        val ids: Ids
    )

    data class Ids(
        val imdb: String
    )
}

data class SimklWatchedBulkResponse(
    val movies: List<SimklWatchedMovieItem>? = emptyList(),
    val shows: List<SimklWatchedShowItem>? = emptyList()
)

data class SimklWatchedMovieItem(
    val watched: Boolean? = null,
    val movie: SimklMovieRef? = null
)

data class SimklWatchedShowItem(
    val watched: Boolean? = null,
    val show: SimklShowRef? = null
)

data class SimklMovieRef(
    val ids: SimklIds? = null
)

data class SimklShowRef(
    val ids: SimklIds? = null
)

data class SimklIds(
    val imdb: String? = null
)

data class WatchedBulkImportResult(
    val watchedMovieImdbIds: Set<String>,
    val watchedShowImdbIds: Set<String>
)
