package com.kennyb1201.kbstream.data.tmdb

/**
 * The "browse this dimension by rail" page loaders (genre / keyword / network /
 * company), split out of [TmdbRepository] — a class that had grown past the
 * size where a single file stayed editable.
 *
 * All four share the same shape: pick the discover endpoint for the rail's
 * title, drop duplicates, apply the digital-release filter when it is on, then
 * the active profile's kids ceiling. They only read the repository's API
 * client, vote floors and filters, so they live here and [TmdbRepository] keeps
 * thin members with the original names — every call site is unchanged.
 */
internal object TmdbRailPages {

    suspend fun genrePage(
        repo: TmdbRepository,
        genreId: Int,
        title: String,
        page: Int
    ): TagRailPage {
        if (repo.apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                repo.api.discoverMovieByGenre(
                    genreId,
                    repo.apiKey,
                    "primary_release_date.desc",
                    repo.minRecentVoteCount,
                    releaseDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · POPULAR" -> runCatching {
                repo.api.discoverMovieByGenre(
                    genreId,
                    repo.apiKey,
                    "popularity.desc",
                    repo.minVoteCount,
                    releaseDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · TOP RATED" -> runCatching {
                repo.api.discoverMovieByGenre(
                    genreId,
                    repo.apiKey,
                    "vote_count.desc",
                    repo.minTopRatedVoteCount,
                    releaseDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "SERIES · RECENT" -> runCatching {
                repo.api.discoverTvByGenre(
                    genreId,
                    repo.apiKey,
                    "first_air_date.desc",
                    repo.minRecentVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                repo.api.discoverTvByGenre(
                    genreId,
                    repo.apiKey,
                    "popularity.desc",
                    repo.minVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                repo.api.discoverTvByGenre(
                    genreId,
                    repo.apiKey,
                    "vote_count.desc",
                    repo.minTopRatedVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return repo.finishRailPage(results)
    }

    suspend fun keywordPage(
        repo: TmdbRepository,
        keywordId: Int,
        title: String,
        page: Int
    ): TagRailPage {
        if (repo.apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                repo.api.discoverMovieByKeyword(
                    keywordId,
                    repo.apiKey,
                    "primary_release_date.desc",
                    repo.minRecentVoteCount,
                    releaseDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · POPULAR" -> runCatching {
                repo.api.discoverMovieByKeyword(
                    keywordId,
                    repo.apiKey,
                    "popularity.desc",
                    repo.minVoteCount,
                    releaseDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · TOP RATED" -> runCatching {
                repo.api.discoverMovieByKeyword(
                    keywordId,
                    repo.apiKey,
                    "vote_count.desc",
                    repo.minTopRatedVoteCount,
                    releaseDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "SERIES · RECENT" -> runCatching {
                repo.api.discoverTvByKeyword(
                    keywordId,
                    repo.apiKey,
                    "first_air_date.desc",
                    repo.minRecentVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                repo.api.discoverTvByKeyword(
                    keywordId,
                    repo.apiKey,
                    "popularity.desc",
                    repo.minVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                repo.api.discoverTvByKeyword(
                    keywordId,
                    repo.apiKey,
                    "vote_count.desc",
                    repo.minTopRatedVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return repo.finishRailPage(results)
    }

    suspend fun networkPage(
        repo: TmdbRepository,
        networkId: Int,
        title: String,
        page: Int
    ): TagRailPage {
        if (repo.apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "SERIES · RECENT" -> runCatching {
                repo.api.discoverByNetwork(
                    networkId,
                    repo.apiKey,
                    "first_air_date.desc",
                    repo.minRecentVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                repo.api.discoverByNetwork(
                    networkId,
                    repo.apiKey,
                    "popularity.desc",
                    repo.minVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                repo.api.discoverByNetwork(
                    networkId,
                    repo.apiKey,
                    "vote_count.desc",
                    repo.minTopRatedVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return repo.finishRailPage(results)
    }

    suspend fun companyPage(
        repo: TmdbRepository,
        companyId: Int,
        title: String,
        page: Int
    ): TagRailPage {
        if (repo.apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                repo.api.discoverMovieByCompany(
                    companyId = companyId,
                    apiKey = repo.apiKey,
                    sortBy = "primary_release_date.desc",
                    voteCountGte = repo.minRecentVoteCount,
                    releaseDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · POPULAR" -> runCatching {
                repo.api.discoverMovieByCompany(
                    companyId = companyId,
                    apiKey = repo.apiKey,
                    sortBy = "popularity.desc",
                    voteCountGte = repo.minVoteCount,
                    releaseDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · TOP RATED" -> runCatching {
                repo.api.discoverMovieByCompany(
                    companyId = companyId,
                    apiKey = repo.apiKey,
                    sortBy = "vote_count.desc",
                    voteCountGte = repo.minTopRatedVoteCount,
                    releaseDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "SERIES · RECENT" -> runCatching {
                repo.api.discoverTvByCompany(
                    companyId = companyId,
                    apiKey = repo.apiKey,
                    sortBy = "first_air_date.desc",
                    voteCountGte = repo.minRecentVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                repo.api.discoverTvByCompany(
                    companyId = companyId,
                    apiKey = repo.apiKey,
                    sortBy = "popularity.desc",
                    voteCountGte = repo.minVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                repo.api.discoverTvByCompany(
                    companyId = companyId,
                    apiKey = repo.apiKey,
                    sortBy = "vote_count.desc",
                    voteCountGte = repo.minTopRatedVoteCount,
                    firstAirDateLte = repo.today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return repo.finishRailPage(results)
    }
}
