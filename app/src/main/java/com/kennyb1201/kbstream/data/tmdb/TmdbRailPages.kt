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
 *
 * Each loader is now a thin wrapper: it resolves the English-only language
 * setting and hands the per-page fetch (`*PageItems`) to
 * [TmdbRepository.finishDeepRailPage], which also decides how many TMDB pages
 * a rail renders with (see `RAIL_DEPTH_TARGET_ITEMS`).
 */
internal object TmdbRailPages {

    /**
     * Rail titles a NETWORK screen loads, in display order. MOVIES rails
     * exist only when the entry carries the brand's TMDB company id: network
     * discover is TV-only, and a rail titled "MOVIES · …" against a network
     * id returns nothing (see [networkPage]).
     */
    internal fun networkRailTitles(companyId: Int?): List<String> = buildList {
        add("SERIES · RECENT")
        add("SERIES · POPULAR")
        add("SERIES · TOP RATED")
        if (companyId != null) {
            add("MOVIES · RECENT")
            add("MOVIES · POPULAR")
            add("MOVIES · TOP RATED")
        }
    }

    suspend fun genrePage(
        repo: TmdbRepository,
        genreId: Int,
        title: String,
        page: Int
    ): TagRailPage {
        if (repo.apiKey.isBlank()) return TagRailPage(emptyList(), false)

        // Settings' English-only switch (on by default) gates every rail on
        // this file's screens — see TmdbRepository.browseLanguage.
        val lang = repo.browseLanguage()

        return repo.finishDeepRailPage(
            page = page,
            cacheKey = "genre|$genreId|$title|$lang"
        ) { p ->
            genrePageItems(repo, genreId, title, p, lang)
        }
    }

    /**
     * [genrePage]'s discover queries for ONE TMDB page, before the filters.
     * Deepening a rail past that single page is
     * [TmdbRepository.finishDeepRailPage]'s job.
     */
    private suspend fun genrePageItems(
        repo: TmdbRepository,
        genreId: Int,
        title: String,
        page: Int,
        lang: String?
    ): List<StudioItem> {
        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                repo.api.discoverMovieByGenre(
                    genreId,
                    repo.apiKey,
                    "primary_release_date.desc",
                    repo.minRecentVoteCount,
                    releaseDateLte = repo.today,
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }
        return results
    }

    suspend fun keywordPage(
        repo: TmdbRepository,
        keywordId: Int,
        title: String,
        page: Int
    ): TagRailPage {
        if (repo.apiKey.isBlank()) return TagRailPage(emptyList(), false)

        // Settings' English-only switch (on by default) gates every rail on
        // this file's screens — see TmdbRepository.browseLanguage.
        val lang = repo.browseLanguage()

        return repo.finishDeepRailPage(
            page = page,
            cacheKey = "keyword|$keywordId|$title|$lang"
        ) { p ->
            keywordPageItems(repo, keywordId, title, p, lang)
        }
    }

    /**
     * [keywordPage]'s discover queries for ONE TMDB page, before the filters.
     */
    private suspend fun keywordPageItems(
        repo: TmdbRepository,
        keywordId: Int,
        title: String,
        page: Int,
        lang: String?
    ): List<StudioItem> {
        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                repo.api.discoverMovieByKeyword(
                    keywordId,
                    repo.apiKey,
                    "primary_release_date.desc",
                    repo.minRecentVoteCount,
                    releaseDateLte = repo.today,
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }
        return results
    }

    /**
     * One rail of a TV network's screen.
     *
     * TMDB has no movies-by-network discover (a network id simply is not a
     * company id), so a network's MOVIES rails run through the brand's
     * production company when [companyId] is known — that is the only
     * mapping TMDB offers, and it is why a network page used to be
     * series-only. Without a [companyId] the movie rails are empty, not
     * wrong: they return nothing rather than pulling in another brand's
     * catalog.
     */
    suspend fun networkPage(
        repo: TmdbRepository,
        networkId: Int,
        title: String,
        page: Int,
        companyId: Int? = null
    ): TagRailPage {
        if (repo.apiKey.isBlank()) return TagRailPage(emptyList(), false)

        // Settings' English-only switch (on by default) gates every rail on
        // this file's screens — see TmdbRepository.browseLanguage.
        val lang = repo.browseLanguage()

        if (title.startsWith("MOVIES")) {
            return companyId?.let { companyPage(repo, it, title, page) }
                ?: TagRailPage(emptyList(), false)
        }

        return repo.finishDeepRailPage(
            page = page,
            cacheKey = "network|$networkId|$title|$lang"
        ) { p ->
            networkPageItems(repo, networkId, title, p, lang)
        }
    }

    /**
     * [networkPage]'s series discover queries for ONE TMDB page, before the
     * filters (see [genrePageItems]).
     */
    private suspend fun networkPageItems(
        repo: TmdbRepository,
        networkId: Int,
        title: String,
        page: Int,
        lang: String?
    ): List<StudioItem> {
        val results = when (title) {
            "SERIES · RECENT" -> runCatching {
                repo.api.discoverByNetwork(
                    networkId,
                    repo.apiKey,
                    "first_air_date.desc",
                    repo.minRecentVoteCount,
                    firstAirDateLte = repo.today,
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }
        return results
    }

    suspend fun companyPage(
        repo: TmdbRepository,
        companyId: Int,
        title: String,
        page: Int
    ): TagRailPage {
        if (repo.apiKey.isBlank()) return TagRailPage(emptyList(), false)

        // Settings' English-only switch (on by default) gates every rail on
        // this file's screens — see TmdbRepository.browseLanguage.
        val lang = repo.browseLanguage()

        return repo.finishDeepRailPage(
            page = page,
            cacheKey = "company|$companyId|$title|$lang"
        ) { p ->
            companyPageItems(repo, companyId, title, p, lang)
        }
    }

    /**
     * [companyPage]'s discover queries for ONE TMDB page, before the filters
     * (see [genrePageItems]).
     */
    private suspend fun companyPageItems(
        repo: TmdbRepository,
        companyId: Int,
        title: String,
        page: Int,
        lang: String?
    ): List<StudioItem> {
        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                repo.api.discoverMovieByCompany(
                    companyId = companyId,
                    apiKey = repo.apiKey,
                    sortBy = "primary_release_date.desc",
                    voteCountGte = repo.minRecentVoteCount,
                    releaseDateLte = repo.today,
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
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
                    withOriginalLanguage = lang,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }
        return results
    }
}
